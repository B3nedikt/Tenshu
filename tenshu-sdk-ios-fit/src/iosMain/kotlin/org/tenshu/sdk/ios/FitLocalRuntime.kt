package org.tenshu.sdk.ios

import kotlinx.coroutines.cancel
import app.tenshu.reachability.MutableSchemaSkewSource
import kotlinx.coroutines.flow.map
import org.tenshu.sync.client.runtimeIdentity
import org.tenshu.sync.client.schemaFence
import org.tenshu.sync.fencedServices
import org.tenshu.sync.isControlHosted

import app.tenshu.fit.data.FitProjectionRepository
import app.tenshu.fit.data.HealthImportBootstrap
import app.tenshu.fit.data.HealthImporter
import app.tenshu.fit.data.ActivityFileParser
import app.tenshu.fit.data.HealthKitImporter
import app.tenshu.fit.data.TakeoutParser
import app.tenshu.fit.data.TenshuAutoDetectPreferences
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus
import org.tenshu.core.getOrNull
import app.tenshu.fit.data.xml.IosXmlReader
import app.tenshu.fit.data.RecordedWorkoutOutbox
import app.tenshu.fit.data.iosRecordedWorkoutOutbox
import app.tenshu.fit.model.CategoryId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.Koin
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.epc.RealmKeyMaterialBridge
import org.tenshu.core.core.TContext
import org.tenshu.sync.common.SyncManager

/**
 * The route-1 **in-process** Fit runtime (ios-first-party-route-1-migration.md) — the Fit twin of
 * `DriveLocalRuntime`, a thin consumer of the [FirstPartyLocalRuntime] substrate.
 *
 * Where [EpcFitClient] talks to Control's proxy over an EPC **channel** (route 2 — needs Control
 * actively hosting), this runs the **production proxy graph in Fit's own process** over the shared
 * App-Group replica Control writes, so Fit works whenever Control has provisioned key material once
 * — no live channel, no "Connect an app" dependency. Control's iOS proxy hosts `:health-service`,
 * so the in-process graph serves Fit's
 * `Workout`/`DailyActivity`/… reads with no new service wiring; the only Fit-specific bit is building
 * a [FitProjectionRepository] over the resulting in-process client instead of Drive's file repos.
 *
 * Key custody = whole-blob (proposal §4 → **C**): a route-1 Fit reads the same [RealmKeyMaterial]
 * Drive does (first-party apps are realm co-owners on iOS route-1). No per-app scoping.
 */
object FitLocalRuntime {

    /** Fit's client id for the in-process `LocalClientConnection` (route-1, not Control's). */
    const val FIT_CLIENT_ID: String = "app.tenshu.fit.ios"

    /** Fit's per-app Keychain bucket — the assembly's delegate and [deprovision] hit the same items. */
    private const val SECURE_STORAGE_SERVICE = "app.tenshu.fit.ios.secure-storage"

    /** The app-agnostic route-1 substrate (assembly + adopt + lifecycle), configured for Fit. */
    private val runtime = FirstPartyLocalRuntime(
        FirstPartyLocalRuntime.Config(
            clientId = FIT_CLIENT_ID,
            secureStorageService = SECURE_STORAGE_SERVICE,
        ),
    )

    /** One assembled runtime: the Koin graph, the client surface, and the pieces [install] needs. */
    class Assembly internal constructor(
        internal val koin: Koin,
        val client: EpcFitClient.FitClient,
        internal val importer: HealthImporter,
        internal val repository: FitProjectionRepository,
    )

    private var installed: Assembly? = null

    /** Whether [install] has run (the shipping gate reads this to skip re-assembly). */
    fun isInstalled(): Boolean = installed != null

    /**
     * The shipping gate probe (ios-drive-parity.md): whether Control has provisioned the
     * route-1 key material into the shared Keychain. `RootView` routes on it — provisioned → [install]
     * + live Fit, absent → the not-connected surface.
     */
    fun isProvisioned(): Boolean = runtime.isProvisioned()

    /**
     * Assemble a runtime without touching the global surface — the testable entry point. Mirrors
     * `DriveLocalRuntime.assemble`: [replicaDirectory]/[secureStorage] override the store +
     * Keychain for hermetic tests; production omits them. [outbox] defaults to the durable recorder
     * outbox and is passed `null` by tests (no app-container writes, the throw-path recorder
     * shape). The per-launch [HealthImportBootstrap] is **not** started here (it is [install]'s
     * duty) so an assembly for a test never kicks a background refresh coroutine.
     */
    fun assemble(
        replicaDirectory: String? = null,
        secureStorage: SecureStorageDelegate? = null,
        handedOffMaterial: RealmKeyMaterial? = null,
        relayBaseUrl: String? = null,
        outbox: RecordedWorkoutOutbox? = iosRecordedWorkoutOutbox(),
        importer: HealthImporter = HealthKitImporter(),
    ): Assembly {
        val base = runtime.assembleBase(
            replicaDirectory = replicaDirectory,
            secureStorage = secureStorage,
            handedOffMaterial = handedOffMaterial,
            relayBaseUrl = relayBaseUrl,
            appOverrides = null,
        )
        val client = base.tenshuClient
        // `core-schema-skew-surface.md`: route 1 links its own proxy, so this app IS
        // the runtime owner — a fence here means "update Fit", never "update Control".
        //
        // ⚠⚠ The announce half is deliberately absent: with one binary on both
        // sides of the boundary there are no two vectors to disagree, so skew B is unreachable
        // here by construction (§3). The fence half is NOT — route 1 has its own realm epoch and
        // can fall behind its peers exactly like any other device.
        val schemaAlerts = MutableSchemaSkewSource()
        val alertScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // ⚠ `ios-shared-store-sqlite.md` B.16 (228): this graph's own scope ends with the graph
        // when the shared store's host stops it.
        runtime.onCurrentGraphStop { alertScope.cancel() }
        schemaAlerts.bindFence(
            client.syncService.schemaFence().map { it?.fencedServices() ?: emptyList() },
            alertScope,
        )
        // ⚠ Read, not asserted. This runtime publishes its own `RuntimeIdentity` (the route-1
        // override in `FirstPartyLocalRuntime.assembleBase`), so the same code path that tells an
        // Android app "Control hosts you" tells this one "you host yourself" — no branch here.
        // ⚠⚠ Its OWN row, by host id: the replica is shared, so it holds every co-resident app's.
        schemaAlerts.bindHost(
            client.syncService.runtimeIdentity(base.hostAppId).map { it.isControlHosted() },
            alertScope,
        )
        val repository = FitProjectionRepository(
            proxyHost = schemaAlerts.host,
            schemaFence = schemaAlerts.fence,
            client = client,
            context = base.koin.get<TContext>(),
            importer = importer,
            outbox = outbox,
            // Google Fit never feeds HealthKit either, so a Takeout archive is the ONLY route to
            // real Google Fit history on iOS. iOS has both halves the parser needs:
            // `IosArchiveReader` for the container and `IosXmlReader` for GPX/TCX.
            fileParser = TakeoutParser(ActivityFileParser(xml = IosXmlReader())),
            // The Tenshu Health row's status. ⚠ Route 1 has a real preference store, so
            // unlike the EPC tier this is not the Noop.
            autoDetect = TenshuAutoDetectPreferences(
                client = client,
                resolveDeviceId = {
                    runCatching { client.syncService.getSyncStatus().getOrNull()?.deviceId?.get() }
                        .getOrNull()
                },
            ),
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("iOS route-1: provisioning is ControlProvisioning's, not this engine's"),
        )
        val backgroundRefresh = buildBackgroundRefresh(repository, importer)
        return Assembly(
            koin = base.koin,
            client = EpcFitClient.FitClient(
                tenshuClient = client,
                fitRepository = repository,
                backgroundRefresh = backgroundRefresh,
            ),
            importer = importer,
            repository = repository,
        )
    }

    /**
     * The background-refresh action (fit-app.md §11.5): replay the recorder outbox (always),
     * then refresh Apple Health into the realm, gated on `autoRefreshReady` so an unattended import
     * never writes fixture data or nags a grant-less device. Best-effort throughout. Identical to
     * [EpcFitClient]'s, but over the in-process repository.
     */
    private fun buildBackgroundRefresh(
        repository: FitProjectionRepository,
        importer: HealthImporter,
    ): suspend () -> String = {
        runCatching { repository.drainPendingWorkouts() }
        val ready = runCatching { importer.autoRefreshReady() }.getOrDefault(false)
        if (ready) {
            runCatching { repository.startImport(CategoryId.entries.toSet(), automatic = true) }
            "bg-refresh: drained + imported"
        } else {
            "bg-refresh: drained (import not ready)"
        }
    }

    /**
     * Production path: read the durable realm-key hand-off ([RealmKeyMaterialBridge]) and assemble
     * over the shared replica — **live** (adopted realm device) when Control has provisioned the
     * material, read-only-sealed otherwise — then install the real [FitProjectionRepository] on
     * [FitSurface] (the route-1 twin of [FitSurface.installRealClient]) and start the per-launch
     * HealthKit refresh. Idempotent; the runtime is process-lifetime.
     */
    fun install() {
        if (installed != null) return
        runtime.rebuildWith(::rebuildGraph)
        val assembly = assemble(
            handedOffMaterial = RealmKeyMaterialBridge.read(),
            relayBaseUrl = devRelayOverride(),
        )
        installed = assembly
        FitSurface.installLocalRuntime(assembly.client.fitRepository, assembly.client.backgroundRefresh)
        // Offline-basemap regions (C3): the in-process graph already hosts `MapRegionService`
        // (`buildProxyModule` registers its schema modules and the iosMain
        // `platformImplementationFactory` binds its ops), so the resolver just needs this
        // runtime's client — no new service wiring. Best-effort, as everywhere else: an
        // unreachable catalog leaves the bundled demo basemap.
        FitSurface.installRegionResolver(fitRegionResolver(assembly.client.tenshuClient))
        // Launch refresh, exactly as EpcFitClient wires it: gated on a REAL source
        // (`autoRefreshReady`) so the reference fixture is never auto-written into the durable
        // synced realm. Record ops are content-addressed, so the refresh folds onto existing rows.
        //
        // ⚠⚠ Kept, not discarded: [install] is idempotent and the runtime is process-lifetime, so
        // this hook is the only way to ask for another pass — the shell drives
        // `FitSurface.refreshHealthImport()` from `scenePhase == .active` (fit-app.md §9.18).
        val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bootstrap = HealthImportBootstrap(
            repository = assembly.repository,
            scope = bootstrapScope,
            importer = assembly.importer,
        )
        FitSurface.installForegroundRefresh { bootstrap.refresh(reason = "foreground") }
        // ⚠ `ios-shared-store-sqlite.md` B.16 (228): the import's scope and its foreground hook are
        // this graph's; a stopped graph's foreground must not import into its released store. The
        // rebuild's install builds both anew.
        runtime.onCurrentGraphStop {
            bootstrapScope.cancel()
            FitSurface.installForegroundRefresh(null)
        }
        // The graph is complete; only now the shared store's
        // host takes it, and may stop it at once (a not-ready stand-in). Nothing when the process has no binding.
        runtime.graphReady()
    }

    /**
     * ⚠ `ios-shared-store-sqlite.md` Appendix B.16: how the shared store's host rebuilds
     * this app's graph after a fence or a not-ready open, on the main thread: the re-provision path
     * ([install] after `installed` is dropped). Registered by [install]; never called
     * without a binding.
     */
    private fun rebuildGraph() {
        installed = null
        install()
    }

    /**
     * The background-refresh entry (fit-app.md §11.5) the `BGAppRefreshTask` handler
     * drives. On iOS route-1 **Fit is its own background syncer** — adoption rehydrates from the
     * persisted joiner payload with no user present — so an OS wake can run a REAL drain + import,
     * unlike the EPC path which no-ops unless Control is co-resident + hosting. Ensures the live
     * runtime, runs the drain/import, and holds a bounded window for pushes/pulls to flush.
     */
    suspend fun runBackgroundRefresh(maxWaitSeconds: Int = 15): String {
        if (!isProvisioned()) return "bg-refresh: not provisioned"
        // The assembly's open is admitted by the implicit token (`ios-shared-store-sqlite.md` B.5
        // (80)).
        install()
        // ⚠⚠ `ios-shared-store-sqlite.md` B.15: the wake's work under an entry token. Unwrapped
        // with no binding installed.
        return SharedReplicaEntries.withProtection("fit-refresh") {
            val status = FitSurface.runBackgroundRefresh(maxWaitSeconds = 0)
            kotlinx.coroutines.delay(maxWaitSeconds * 1000L)
            status
        } ?: "bg-refresh: no protected time, or it expired (S0b B.15)"
    }

    /**
     * Tear down Fit's side of the route-1 provisioning: the shipping gate
     * calls this when the bridge material has VANISHED while Fit holds adopted custody. Seals the
     * running graph (`SyncManager.lock()`), purges Fit's per-app custody copies (so the persisted
     * joiner payload cannot silently resurrect the adoption next launch), and restores the honest
     * not-connected surface. The shared replica is left alone (Control's store).
     */
    fun deprovision() {
        val assembly = installed
        installed = null
        FitSurface.installNotConnected()
        if (assembly != null) {
            runCatching { assembly.koin.get<SyncManager>().lock() }
            runtime.purgeCustody(assembly.koin.get<SecureStorageDelegate>())
        } else {
            runtime.purgeCustody(runtime.standaloneDelegate())
        }
    }

    /** Whether Fit still holds adopted custody at rest (the joiner payload) — the deprovision trigger. */
    fun hasResidualCustody(): Boolean {
        installed?.let { return runtime.hasResidualCustody(it.koin.get<SecureStorageDelegate>()) }
        return runtime.hasResidualCustody(runtime.standaloneDelegate())
    }
}
