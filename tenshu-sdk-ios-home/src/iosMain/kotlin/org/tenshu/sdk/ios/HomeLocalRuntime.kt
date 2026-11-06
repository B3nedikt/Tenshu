package org.tenshu.sdk.ios

import app.tenshu.home.data.ClientDeviceSource
import app.tenshu.home.data.ClientHubStatusSource
import app.tenshu.home.data.ClientRoutineSource
import app.tenshu.home.data.HomeRepositories
import app.tenshu.home.data.homeControlReachability
import app.tenshu.home.data.readOnlySyncedHomeRepositories
import app.tenshu.reachability.ControlReachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.Koin
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.epc.RealmKeyMaterialBridge
import org.tenshu.sync.common.SyncManager

/**
 * The route-1 **in-process** Home runtime — the Home twin of [CalendarLocalRuntime] /
 * [PhotosLocalRuntime], over the [FirstPartyLocalRuntime] substrate
 * (`ios-first-party-route-1-migration.md`).
 *
 * It runs the production proxy graph in Home's own process over the shared replica Control
 * writes. Nothing here talks to a hub: every Home data source is synced realm rows read from the
 * local store — `HomeDevice` + `DeviceState`, `HubStatus`, `Routine` + `RoutineRun`.
 *
 * ⚠ **No new service wiring.** `:home-device-service`, `:hub-management-service` and
 * `:routine-service` are all registered by the iOS `PlatformProxyModule`.
 *
 * ⚠⚠ **Read-only.** The surface gets [readOnlySyncedHomeRepositories]: no device control, no
 * pairing, and every automation write refused. Adding them means swapping that one call for the
 * full composition `homeClientModule` uses. ⚠ Even then routines never *execute* on an iPhone
 * (`RoutineExecution.NEVER`, `routines-engine.md` RE-D13): "run now" stays unavailable here.
 *
 * Whole-blob key custody (migration proposal §4 → **C**): first-party apps are realm co-owners
 * on iOS route 1 — `docs/sync/threat-model.md` A4 names Home.
 */
object HomeLocalRuntime {

    /** Home's client id for the in-process `LocalClientConnection` (route-1, not Control's). */
    const val HOME_CLIENT_ID: String = "app.tenshu.home.ios"

    /** Home's per-app Keychain bucket — the assembly's delegate and [deprovision] hit the same items. */
    private const val SECURE_STORAGE_SERVICE = "app.tenshu.home.ios.secure-storage"

    private val runtime = FirstPartyLocalRuntime(
        FirstPartyLocalRuntime.Config(
            clientId = HOME_CLIENT_ID,
            secureStorageService = SECURE_STORAGE_SERVICE,
        ),
    )

    /** One assembled runtime: the Koin graph plus the repositories built over its client. */
    class Assembly internal constructor(
        internal val koin: Koin,
        val tenshuClient: TenshuClient,
        val repositories: HomeRepositories,
        internal val reachability: ControlReachability,
        internal val reachScope: CoroutineScope,
    )

    private var installed: Assembly? = null

    /** Whether [install] has run (the shipping gate reads this to skip re-assembly). */
    fun isInstalled(): Boolean = installed != null

    /**
     * The shipping gate probe: whether Control has provisioned the route-1 key material into the
     * shared Keychain. ⚠ One shared blob, not a per-app grant — Control does not list Home, so a
     * Control that has provisioned any first-party app has provisioned this one.
     */
    fun isProvisioned(): Boolean = runtime.isProvisioned()

    /**
     * Assemble a runtime without touching the global surface — the testable entry point.
     * [replicaDirectory] / [secureStorage] override the store + Keychain for hermetic tests;
     * production omits them.
     */
    fun assemble(
        replicaDirectory: String? = null,
        secureStorage: SecureStorageDelegate? = null,
        handedOffMaterial: RealmKeyMaterial? = null,
        relayBaseUrl: String? = null,
    ): Assembly {
        val base = runtime.assembleBase(
            replicaDirectory = replicaDirectory,
            secureStorage = secureStorage,
            handedOffMaterial = handedOffMaterial,
            relayBaseUrl = relayBaseUrl,
        )
        val client = base.tenshuClient
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // ⚠ This graph's own scope ends with the graph when the shared store's host stops it
        // (`ios-shared-store-sqlite.md` Appendix B.16).
        runtime.onCurrentGraphStop { scope.cancel() }
        // ⚠ One routine source for both the automation list and the activity feed: the feed is
        // the routines' own receipts, so two sources would be two subscriptions to the same rows.
        return Assembly(
            koin = base.koin,
            tenshuClient = client,
            repositories = readOnlySyncedHomeRepositories(
                hubStatus = ClientHubStatusSource(client),
                devices = ClientDeviceSource(client),
                routines = ClientRoutineSource(client),
            ),
            reachability = homeControlReachability(client, probeScope = scope, controlHasRealm = app.tenshu.reachability.RealmProbe.None("iOS route-1: provisioning is ControlProvisioning's, not this engine's")),
            reachScope = scope,
        )
    }

    /**
     * Production path: read the durable realm-key hand-off ([RealmKeyMaterialBridge]) and assemble
     * over the shared replica, then install the real repositories on [HomeSurface]. Idempotent;
     * the runtime is process-lifetime.
     *
     * ⚠ View models must be built AFTER this: they bind whatever the surface holds at
     * construction. `ControlSetUpGate` defers building the shell until provisioning is known.
     */
    fun install() {
        if (installed != null) return
        runtime.rebuildWith(::rebuildGraph)
        val assembly = assemble(
            handedOffMaterial = RealmKeyMaterialBridge.read(),
            relayBaseUrl = devRelayOverride(),
        )
        installed = assembly
        HomeSurface.install(
            homeRepositories = assembly.repositories,
            reachability = assembly.reachability.state,
        )
        // The graph is complete; only now the shared store's host takes it, and may stop it at once
        // (a not-ready stand-in). Nothing when the process has no binding.
        runtime.graphReady()
    }

    /**
     * ⚠ How the shared store's host rebuilds this app's graph after a fence or a not-ready open
     * (`ios-shared-store-sqlite.md` Appendix B.16), on the main thread: the re-provision path
     * ([install] after `installed` is dropped). Registered by [install]; never called
     * without a binding.
     */
    private fun rebuildGraph() {
        installed = null
        install()
    }

    /**
     * Tear down Home's side of the route-1 provisioning: seal the running graph
     * (`SyncManager.lock()`), stop the reachability probe, purge Home's per-app custody copies
     * (so the persisted joiner payload cannot silently resurrect the adoption), and restore the
     * not-connected surface. The shared replica is left alone (Control's store).
     */
    fun deprovision() {
        val assembly = installed
        installed = null
        HomeSurface.installNotConnected()
        if (assembly != null) {
            assembly.reachScope.cancel()
            runCatching { assembly.koin.get<SyncManager>().lock() }
            runtime.purgeCustody(assembly.koin.get<SecureStorageDelegate>())
        } else {
            runtime.purgeCustody(runtime.standaloneDelegate())
        }
    }

    /** Whether Home still holds adopted custody at rest (the joiner payload) — the deprovision trigger. */
    fun hasResidualCustody(): Boolean {
        installed?.let { return runtime.hasResidualCustody(it.koin.get<SecureStorageDelegate>()) }
        return runtime.hasResidualCustody(runtime.standaloneDelegate())
    }
}
