package org.tenshu.sdk.ios

import app.tenshu.reachability.MutableSchemaSkewSource
import kotlinx.coroutines.flow.map
import org.tenshu.sync.client.runtimeIdentity
import org.tenshu.sync.client.schemaFence
import org.tenshu.sync.client.syncService
import org.tenshu.sync.fencedServices
import org.tenshu.sync.isControlHosted

import app.tenshu.contacts.data.ContactsRepository
import app.tenshu.contacts.data.TenshuContactsRepository
import app.tenshu.contacts.data.contactsControlReachability
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
 * The route-1 **in-process** Contacts runtime — the Contacts twin of [MapsLocalRuntime] /
 * [TimelineLocalRuntime] / [FitLocalRuntime] / [DriveLocalRuntime], over the
 * [FirstPartyLocalRuntime] substrate (`ios-first-party-route-1-migration.md`).
 *
 * It runs the production proxy graph in Contacts' own process over the shared App-Group replica
 * Control writes, so it works whenever Control has provisioned key material once — no live
 * channel, and (by construction, being in-process) none of the client-resubscription gap
 * `core-client-resubscription.md` describes. Like Maps, Contacts is **born** on route 1: there
 * is no EPC tier to migrate off, so there is no `EpcContactsClient` beside this.
 *
 * ⚠ **No new service wiring.** Both services Contacts reads are already hosted by the route-1
 * graph: `:contact-service` (the contact graph itself) and `:preference-service` (the D6 own
 * name, which is the *platform* name Drive and Fit's profiles also read — not an app-private
 * copy). That is what makes this file thin.
 *
 * Whole-blob key custody (migration proposal §4 → **C**): first-party apps are realm co-owners
 * on iOS route 1.
 */
object ContactsLocalRuntime {

    /** Contacts' client id for the in-process `LocalClientConnection` (route-1, not Control's). */
    const val CONTACTS_CLIENT_ID: String = "app.tenshu.contacts.ios"

    /** Contacts' per-app Keychain bucket — the assembly's delegate and [deprovision] hit the same items. */
    private const val SECURE_STORAGE_SERVICE = "app.tenshu.contacts.ios.secure-storage"

    /** The app-agnostic route-1 substrate (assembly + adopt + lifecycle), configured for Contacts. */
    private val runtime = FirstPartyLocalRuntime(
        FirstPartyLocalRuntime.Config(
            clientId = CONTACTS_CLIENT_ID,
            secureStorageService = SECURE_STORAGE_SERVICE,
        ),
    )

    /** One assembled runtime: the Koin graph plus the repository built over its client. */
    class Assembly internal constructor(
        internal val koin: Koin,
        val tenshuClient: TenshuClient,
        val contactsRepository: ContactsRepository,
        internal val reachScope: CoroutineScope,
    )

    private var installed: Assembly? = null

    /** Whether [install] has run (the shipping gate reads this to skip re-assembly). */
    fun isInstalled(): Boolean = installed != null

    /**
     * The shipping gate probe: whether Control has provisioned the route-1 key material into the
     * shared Keychain. `ContactsIosApp` routes on it — provisioned → [install] + live, absent →
     * the honest not-connected surface.
     */
    fun isProvisioned(): Boolean = runtime.isProvisioned()

    /**
     * Assemble a runtime without touching the global surface — the testable entry point.
     * [replicaDirectory] / [secureStorage] override the store + Keychain for hermetic tests;
     * production omits them.
     *
     * The **one** reachability engine is built here and shared, exactly as `contactsModules()`
     * does on Compose (`fit-app.md` §9.14 — one signal per app, not one per repository).
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
            appOverrides = null,
        )
        val client = base.tenshuClient
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // ⚠ This graph's own scope ends with the graph when the shared store's host stops it
        // (`ios-shared-store-sqlite.md` Appendix B.16).
        runtime.onCurrentGraphStop { scope.cancel() }
        // SK-2/SK-3: route 1 links its own proxy, so it publishes its own RuntimeIdentity and
        // owns its own realm epoch. Read both rather than assert either — the same code path that
        // tells an Android app "Control hosts you" tells this one "you host yourself".
        val schemaAlerts = MutableSchemaSkewSource()
        // ⚠⚠ Its OWN row, by host id: the replica is shared, so it holds every co-resident app's.
        schemaAlerts.bindHost(
            client.syncService.runtimeIdentity(base.hostAppId).map { it.isControlHosted() },
            scope,
        )
        schemaAlerts.bindFence(
            client.syncService.schemaFence().map { it?.fencedServices() ?: emptyList() },
            scope,
        )
        return Assembly(
            koin = base.koin,
            tenshuClient = client,
            contactsRepository = TenshuContactsRepository(
                client,
                reachability = contactsControlReachability(
                    client,
                    probeScope = scope,
                    schemaFence = schemaAlerts.fence,
                    proxyHost = schemaAlerts.host,
                    controlHasRealm = app.tenshu.reachability.RealmProbe.None("iOS route-1: provisioning is ControlProvisioning's, not this engine's"),
                ),
            ),
            reachScope = scope,
        )
    }

    /**
     * Production path: read the durable realm-key hand-off ([RealmKeyMaterialBridge]) and
     * assemble over the shared replica — **live** (adopted realm device) when Control has
     * provisioned the material, read-only-sealed otherwise — then install the real repository on
     * [ContactsSurface]. Idempotent; the runtime is process-lifetime.
     */
    fun install() {
        if (installed != null) return
        runtime.rebuildWith(::rebuildGraph)
        val assembly = assemble(
            handedOffMaterial = RealmKeyMaterialBridge.read(),
            relayBaseUrl = devRelayOverride(),
        )
        installed = assembly
        ContactsSurface.installRepository(assembly.contactsRepository)
        // The graph is complete; only now the shared store's host takes it, and may stop it at
        // once (a not-ready stand-in). Nothing when the process has no binding.
        runtime.graphReady()
    }

    /**
     * ⚠ How the shared store's host rebuilds this app's graph after a fence or a not-ready open, on
     * the main thread: the re-provision path ([install] after `installed` is dropped;
     * `ios-shared-store-sqlite.md` Appendix B.16). Registered by [install]; never called
     * without a binding.
     */
    private fun rebuildGraph() {
        installed = null
        install()
    }

    /**
     * Tear down Contacts' side of the route-1 provisioning (the teardown twin): seal the running
     * graph (`SyncManager.lock()`), stop the reachability probe, purge Contacts' per-app custody
     * copies (so the persisted joiner payload cannot silently resurrect the adoption), and
     * restore the honest not-connected surface. The shared replica is left alone (Control's
     * store).
     */
    fun deprovision() {
        val assembly = installed
        installed = null
        ContactsSurface.installNotConnected()
        if (assembly != null) {
            assembly.reachScope.cancel()
            runCatching { assembly.koin.get<SyncManager>().lock() }
            runtime.purgeCustody(assembly.koin.get<SecureStorageDelegate>())
        } else {
            runtime.purgeCustody(runtime.standaloneDelegate())
        }
    }

    /** Whether Contacts still holds adopted custody at rest (the joiner payload) — the deprovision trigger. */
    fun hasResidualCustody(): Boolean {
        installed?.let { return runtime.hasResidualCustody(it.koin.get<SecureStorageDelegate>()) }
        return runtime.hasResidualCustody(runtime.standaloneDelegate())
    }
}
