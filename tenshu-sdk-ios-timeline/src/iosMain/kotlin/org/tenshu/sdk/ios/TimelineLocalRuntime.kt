package org.tenshu.sdk.ios

import kotlinx.coroutines.cancel
import app.tenshu.reachability.MutableSchemaSkewSource
import kotlinx.coroutines.flow.map
import org.tenshu.sync.client.runtimeIdentity
import org.tenshu.sync.client.schemaFence
import org.tenshu.sync.client.syncService
import org.tenshu.sync.fencedServices
import org.tenshu.sync.isControlHosted

import app.tenshu.timeline.data.FileTimelineImporter
import app.tenshu.timeline.data.TimelineImportFile
import app.tenshu.timeline.data.TimelineProjectionRepository
import app.tenshu.timeline.data.geocode.PlaceGeocodingControl
import app.tenshu.timeline.data.wifi.IosConnectedWifi
import app.tenshu.timeline.data.wifi.IosWifiFingerprintControl
import app.tenshu.timeline.data.wifi.IosWifiFingerprintStore
import app.tenshu.timeline.data.wifi.IosWifiRecognition
import app.tenshu.timeline.data.wifi.SyncedWifiFingerprintStore
import app.tenshu.timeline.data.wifi.WifiPlaceFingerprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.Koin
import org.tenshu.core.entity.DataId
import org.tenshu.core.common.app.OpenTargetResult
import org.tenshu.preference.client.TenshuOwnerProfile
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.epc.RealmKeyMaterialBridge
import org.tenshu.core.core.TContext
import org.tenshu.sync.common.SyncManager

/**
 * The route-1 **in-process** Timeline runtime (ios-first-party-route-1-migration.md P3) — the Timeline
 * twin of [FitLocalRuntime] / [DriveLocalRuntime], over the P1 [FirstPartyLocalRuntime] substrate.
 *
 * Where [EpcTimelineClient] talks to Control's proxy over an EPC **channel** (route 2 — needs Control
 * actively hosting), this runs the production proxy graph in Timeline's own process over the shared
 * App-Group replica Control writes, so it works whenever Control has provisioned key material once —
 * no live channel. Control's iOS proxy already hosts `:timeline-service` + `:place-service`, so the
 * graph serves the `Moment`/`Trip`/`Place` reads with no new service wiring; the only Timeline-specific
 * bit is building a [TimelineProjectionRepository] over the in-process client.
 *
 * Whole-blob key custody (proposal §4 → **C**): first-party apps are realm co-owners on iOS route-1.
 * Simpler than Fit — no importer / outbox / background-refresh (Timeline's writes come from capture,
 * not import); the geocoding [PlaceLabelEnricher] rail is omitted here (as [EpcTimelineClient] omits it
 * absent a `PlaceGeocodingControl`) and can be wired later.
 */
object TimelineLocalRuntime {

    /** Timeline's client id for the in-process `LocalClientConnection` (route-1, not Control's). */
    const val TIMELINE_CLIENT_ID: String = "app.tenshu.timeline.ios"

    /** Timeline's per-app Keychain bucket — the assembly's delegate and [deprovision] hit the same items. */
    private const val SECURE_STORAGE_SERVICE = "app.tenshu.timeline.ios.secure-storage"

    /** The app-agnostic route-1 substrate (assembly + adopt + lifecycle), configured for Timeline. */
    private val runtime = FirstPartyLocalRuntime(
        FirstPartyLocalRuntime.Config(
            clientId = TIMELINE_CLIENT_ID,
            secureStorageService = SECURE_STORAGE_SERVICE,
        ),
    )

    /** One assembled runtime: the Koin graph plus the client surface built over it. */
    class Assembly internal constructor(
        internal val koin: Koin,
        val client: EpcTimelineClient.TimelineClient,
        /**
         * The opt-in Wi-Fi recognition stack (§4.5), assembled here because it is the one place
         * that holds both the client (for the synced fingerprint mirror) and the repository (for
         * the rename binding). `TimelineCaptureRuntime` borrows the *same instance* — one store,
         * one `WifiPlaceFingerprint.state`, so the Settings status line reflects what capture
         * actually did rather than a second facade's separate opinion.
         */
        internal val wifi: IosWifiRecognition,
    )

    private var installed: Assembly? = null

    /** Whether [install] has run (the shipping gate reads this to skip re-assembly). */
    fun isInstalled(): Boolean = installed != null

    /** The installed runtime, for sibling runtimes needing its client (capture, M4). */
    internal fun assemblyOrNull(): Assembly? = installed

    /**
     * The shipping gate probe: whether Control has provisioned the route-1 key material into the
     * shared Keychain. `TimelineIosApp` routes on it — provisioned → [install] + live, absent → the
     * (route-2 EPC / mock) fallback.
     */
    fun isProvisioned(): Boolean = runtime.isProvisioned()

    /**
     * Assemble a runtime without touching the global surface — the testable entry point. Mirrors
     * [FitLocalRuntime.assemble]: [replicaDirectory]/[secureStorage] override the store + Keychain
     * for hermetic tests; production omits them.
     */
    fun assemble(
        replicaDirectory: String? = null,
        secureStorage: SecureStorageDelegate? = null,
        handedOffMaterial: RealmKeyMaterial? = null,
        relayBaseUrl: String? = null,
        /**
         * The place-name opt-in, which gates the transit-stop + picker lookups (both reach Photon via
         * the in-process proxy). ⚠ `null` means no lookups — absence is never consent.
         */
        geocodingControl: PlaceGeocodingControl? = null,
    ): Assembly {
        val base = runtime.assembleBase(
            replicaDirectory = replicaDirectory,
            secureStorage = secureStorage,
            handedOffMaterial = handedOffMaterial,
            relayBaseUrl = relayBaseUrl,
            appOverrides = null,
        )
        val client = base.tenshuClient
        val context = base.koin.get<TContext>()
        // Wi-Fi place recognition (§4.5 / F4). The synced hashed-tag mirror wraps the device-local
        // NSUserDefaults store, so this device inherits bindings its siblings learned instead of
        // relearning them — relearning is what re-fragments a place. `start()` hydrates once and
        // then follows; the capture path additionally awaits a refresh per wake, because on iOS a
        // cold relaunch would otherwise resolve the waking dwell against an empty mirror.
        val connected = IosConnectedWifi()
        val wifiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // ⚠ S0b-3 (B.16 (228)): the Wi-Fi stack's scope and the live capture are this graph's:
        // both end when the shared store's host stops it, and the rebuild starts capture anew.
        runtime.onCurrentGraphStop {
            wifiScope.cancel()
            TimelineCaptureRuntime.detachIfOver(client)
        }
        // B.16 (238): given up with no rebuild to follow, the parked manager has no successor.
        runtime.onCurrentGraphAbandoned { TimelineCaptureRuntime.abandonParked() }
        val store = SyncedWifiFingerprintStore(
            local = IosWifiFingerprintStore(),
            client = client,
            context = context,
            scope = wifiScope,
        ).also { it.start() }
        val wifiControl = IosWifiFingerprintControl()
        val wifi = IosWifiRecognition(
            fingerprint = WifiPlaceFingerprint(wifiControl, connected, store),
            control = wifiControl,
            connected = connected,
            store = store,
            scope = wifiScope,
        )
        // SK-2/SK-3: route 1 links its own proxy, so it publishes its own RuntimeIdentity and
        // owns its own realm epoch. Read both rather than assert either - the same code path that
        // tells an Android app "Control hosts you" tells this one "you host yourself".
        val schemaAlerts = MutableSchemaSkewSource()
        // ⚠⚠ Its OWN row, by host id: the replica is shared, so it holds every co-resident app's.
        schemaAlerts.bindHost(
            client.syncService.runtimeIdentity(base.hostAppId).map { it.isControlHosted() },
            wifiScope,
        )
        schemaAlerts.bindFence(
            client.syncService.schemaFence().map { it?.fencedServices() ?: emptyList() },
            wifiScope,
        )
        val repository = TimelineProjectionRepository(
            client,
            context,
            wifi = wifi.fingerprint,
            ownerProfile = TenshuOwnerProfile(client),
            geocodingConsent = geocodingControl,
            schemaFence = schemaAlerts.fence,
            proxyHost = schemaAlerts.host,
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("iOS route-1: provisioning is ControlProvisioning's, not this engine's"),
        )
        return Assembly(
            koin = base.koin,
            client = EpcTimelineClient.TimelineClient(
                tenshuClient = client,
                timelineRepository = repository,
            ),
            wifi = wifi,
        )
    }

    /**
     * Production path: read the durable realm-key hand-off ([RealmKeyMaterialBridge]) and assemble
     * over the shared replica — **live** (adopted realm device) when Control has provisioned the
     * material, read-only-sealed otherwise — then install the real [TimelineProjectionRepository] on
     * [TimelineSurface]. Idempotent; the runtime is process-lifetime.
     */
    fun install() = install(startCapture = false)

    /** [install], and for the rebuild the capture over the new graph before it is handed over. */
    private fun install(startCapture: Boolean) {
        if (installed != null) return
        runtime.rebuildWith(::rebuildGraph)
        val assembly = assemble(
            handedOffMaterial = RealmKeyMaterialBridge.read(),
            relayBaseUrl = devRelayOverride(),
            // The same app-global control the Settings toggle writes, not a second instance.
            geocodingControl = TimelineSurface.placeGeocodingControl,
        )
        installed = assembly
        TimelineSurface.installRepository(assembly.client.timelineRepository)
        // Wi-Fi recognition rides the live runtime only — the not-connected tier has no store to
        // learn into and no place to rename. Same gate as capture and the geocoding rail.
        TimelineSurface.installWifi(assembly.wifi)
        // Offline-basemap regions (C3): the in-process graph already hosts `MapRegionService`
        // (`buildProxyModule` registers its schema modules and the iosMain
        // `platformImplementationFactory` binds its ops), so the resolver just needs this
        // runtime's client — no new service wiring. Best-effort, as everywhere else: an
        // unreachable catalog leaves the bundled demo basemap.
        TimelineSurface.installRegionResolver(
            timelineRegionResolver(assembly.client.tenshuClient),
        )
        // B.16 (228): at a rebuild, capture over the new graph (it was detached when the old one
        // stopped), registered before the hand-off below so that a stand-in's stop reaches it.
        if (startCapture) TimelineCaptureRuntime.startIfEnabled()
        // S0b-3: the graph is complete; only now the shared store's
        // host takes it, and may stop it at once (a not-ready stand-in). Nothing with the switch off.
        runtime.graphReady()
    }

    /**
     * ⚠ S0b-3 (`ios-shared-store-sqlite.md` Appendix B.16): how the shared store's host rebuilds
     * this app's graph after a fence or a not-ready open, on the main thread: the re-provision path
     * ([install] after `installed` is dropped). Registered by [install]; never called with the
     * SQLite switch off.
     */
    private fun rebuildGraph() {
        installed = null
        install(startCapture = true)
    }

    /**
     * Open a photo in whichever app owns the file (`control-cross-app-open.md` TT-2a on iOS).
     *
     * ⭐ Timeline is the **second caller** the cross-app-open design exists to be proven by: a
     * contract exercised only by Mia would be a contract shaped like Mia. It names the FILE's id
     * and never an app — which app shows it is Control's resolution, here running in-process.
     *
     * ⚠ Returns a plain outcome string rather than the sealed result: SwiftUI needs something it
     * can switch on and show, and a Kotlin sealed hierarchy across the bridge buys nothing here.
     * `"opened"` means the open was accepted, not that the user is looking at it (CO-D9).
     *
     * ⚠ Returns `"unavailable"` before [install] — no client, nothing to ask.
     */
    suspend fun openPhotoInOwningApp(path: String): String {
        val client = installed?.client?.tenshuClient ?: return "unavailable"
        val target = DataId.StringDataId.create(
            context = null,
            type = FILE_TYPE,
            keyValue = path,
        )
        return when (val result = IosOpenTargetCaller.open(client, target)) {
            OpenTargetResult.Delivered, OpenTargetResult.LaunchRequested -> "opened"
            is OpenTargetResult.Refused -> result.reason
            OpenTargetResult.Unreachable -> "unreachable"
        }
    }

    /**
     * ⚠ A literal, like `HandlerTable`'s and the chat cell's: `:tenshu-sdk-ios-timeline` must not depend on
     * `:file-service` to know a file's type id, and the owning side pins the same string.
     */
    private const val FILE_TYPE = "tenshu/file"

    /**
     * Import Google Timeline export files (`names[i]` → `texts[i]`, the JSON) into the realm through
     * the shared [FileTimelineImporter.importFiles] — the call Timeline's *Import from Google Takeout*
     * makes on Android and desktop, so the places, visits and movement traces it writes are the rows
     * such an import syncs onto this device. Returns the number of records written, or -1 before
     * [install].
     *
     * ⚠⚠ **No iOS screen calls this, and a release binary refuses it.** The iOS app has no import
     * (its listing says so — `store/timeline/claims.md`); the one caller is the store-screenshot
     * seed (`timeline-ios/Sources/StoreFixtureSeed.swift`, DEBUG-only, `store-screenshots.md` §3f).
     * The `isDebugBinary` gate keeps that claim true of a shipped app, as the dev-relay seam does.
     *
     * No place-name enricher: route 1 offers no geocoding opt-in, so an iOS import looks nothing up.
     */
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    suspend fun importGoogleTimelineForStoreFixture(names: List<String>, texts: List<String>): Int {
        check(kotlin.native.Platform.isDebugBinary) { "the Google Timeline import is a debug-only seed on iOS" }
        require(names.size == texts.size) { "${names.size} names for ${texts.size} files" }
        val assembly = installed ?: return -1
        return importGoogleTimeline(assembly, names, texts)
    }

    /** The body of [importGoogleTimelineForStoreFixture] over a given [assembly] — for the test. */
    internal suspend fun importGoogleTimeline(assembly: Assembly, names: List<String>, texts: List<String>): Int =
        FileTimelineImporter(
            client = assembly.client.tenshuClient,
            context = assembly.koin.get<TContext>(),
            enricher = null,
        ).importFiles(names.zip(texts) { name, text -> TimelineImportFile(name, text.encodeToByteArray()) })

    /**
     * Tear down Timeline's side of the route-1 provisioning (the teardown twin): seal the running
     * graph (`SyncManager.lock()`), purge Timeline's per-app custody copies (so the persisted joiner
     * payload cannot silently resurrect the adoption), and restore the default surface. The shared
     * replica is left alone (Control's store). Timeline's not-connected surface is the honest
     * [NotConnectedTimelineRepository] — empty reads plus the "Can't reach Tenshu Control" banner;
     * the mock is a test/preview surface only.
     */
    fun deprovision() {
        val assembly = installed
        installed = null
        TimelineSurface.installNotConnected()
        if (assembly != null) {
            // End the fingerprint mirror's collector before the graph is sealed, or it keeps
            // reading a locked realm for the rest of the process.
            assembly.wifi.close()
            runCatching { assembly.koin.get<SyncManager>().lock() }
            runtime.purgeCustody(assembly.koin.get<SecureStorageDelegate>())
        } else {
            runtime.purgeCustody(runtime.standaloneDelegate())
        }
    }

    /** Whether Timeline still holds adopted custody at rest (the joiner payload) — the deprovision trigger. */
    fun hasResidualCustody(): Boolean {
        installed?.let { return runtime.hasResidualCustody(it.koin.get<SecureStorageDelegate>()) }
        return runtime.hasResidualCustody(runtime.standaloneDelegate())
    }
}
