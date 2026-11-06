package org.tenshu.sdk.ios

import app.tenshu.mia.data.ConversationRepository
import app.tenshu.mia.data.TenshuConversationRepository
import org.koin.core.Koin
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.epc.RealmKeyMaterialBridge
import org.tenshu.sync.common.SyncManager

/**
 * The route-1 **in-process** Mia runtime — the Mia twin of [CalendarLocalRuntime] /
 * [ContactsLocalRuntime] / [MapsLocalRuntime], over the [FirstPartyLocalRuntime] substrate
 * (`ios-first-party-route-1-migration.md`; `mia-query-layer.md` MQ-D2's "in-process on iOS
 * route-1", §19).
 *
 * It runs the production proxy graph in Mia's own process over the shared App-Group replica
 * Control writes, so `askMia` is answered here — planner, lowering, validator and the reads
 * themselves — with no channel to Control at all.
 *
 * ⚠ **No new service wiring.** `:mia-service` and every source it reads are already hosted by the
 * route-1 graph (the iOS `PlatformProxyModule`). That is what makes this file thin.
 *
 * ⚠⚠ **Read-only, and that is the proxy's doing, not this file's.** The iOS `PlatformProxyModule`
 * builds the Mia factory with `MiaActionOffer.WITHHELD`, so an utterance asking for a device
 * command, a routine run or a new event is refused in planning and no confirmation token exists.
 * Nothing here could turn that back on.
 *
 * ⚠ **No reachability engine, unlike the siblings.** Theirs drives `ControlNotConnectedBanner`
 * above screens that go on showing cached rows; Mia has no such screen — a chat with nothing
 * asked is empty either way — and the banner's sentence ("Can't reach Tenshu Control") describes
 * a channel this runtime does not have. The two states that matter are both covered without it:
 * never provisioned is the set-up screen, and a turn that could not be asked says so itself
 * ([COULD_NOT_LOOK]).
 *
 * ⚠⚠ **What this process holds** (threat model A4 / A39): the whole realm key blob, like every
 * other route-1 app — Decision C, first-party apps are realm co-owners on iOS. `MiaChainValidator`
 * bounds what `askMia` reads; it is not a bound on what this process could read.
 */
object MiaLocalRuntime {

    /** Mia's client id for the in-process `LocalClientConnection` (route-1, not Control's). */
    const val MIA_CLIENT_ID: String = "app.tenshu.mia.ios"

    /** Mia's per-app Keychain bucket — the assembly's delegate and [deprovision] hit the same items. */
    private const val SECURE_STORAGE_SERVICE = "app.tenshu.mia.ios.secure-storage"

    /**
     * What a turn says when the in-process proxy could not be asked at all.
     *
     * ⚠⚠ **Not the shared "I can't reach Control right now".** On Android and desktop that names
     * the cause the user can act on; here it would be false in every case, because there is no
     * Control channel to lose — the proxy is this process. What can actually fail is local (a
     * sealed graph after Control tore the realm down, a schema this build cannot read), and the
     * honest sentence names no app the user would then go and open for nothing.
     */
    internal const val COULD_NOT_LOOK: String =
        "Something went wrong on this device, so I couldn't look that up. Nothing was searched."

    /** The app-agnostic route-1 substrate (assembly + adopt + lifecycle), configured for Mia. */
    private val runtime = FirstPartyLocalRuntime(
        FirstPartyLocalRuntime.Config(
            clientId = MIA_CLIENT_ID,
            secureStorageService = SECURE_STORAGE_SERVICE,
        ),
    )

    /** One assembled runtime: the Koin graph plus the repository built over its client. */
    @OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
    class Assembly internal constructor(
        // Public for Photos' cross-app tests (`PhotosOwnedInferenceStoresTest` and
        // `PhotosOnDeviceAiTest` assemble Mia's graph beside Photos'), hidden from Swift so the
        // surface is unchanged (`ios-per-app-frameworks.md` P4; Drive's assembly does the same).
        @kotlin.native.HiddenFromObjC val koin: Koin,
        val tenshuClient: TenshuClient,
        val conversationRepository: ConversationRepository,
    )

    private var installed: Assembly? = null

    /** Whether [install] has run (the shipping gate reads this to skip re-assembly). */
    fun isInstalled(): Boolean = installed != null

    /**
     * The shipping gate probe: whether Control has provisioned the route-1 key material into the
     * shared Keychain. `MiaIosApp` routes on it — provisioned → [install] + live, absent → the
     * shared *Set Up Tenshu Control* screen.
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
            appOverrides = null,
        )
        val client = base.tenshuClient
        return Assembly(
            koin = base.koin,
            tenshuClient = client,
            // ⚠ ONE repository for the process: it holds the conversation (MQ-D8, in memory by
            // design), and both of the surface's view models must observe the same thread.
            conversationRepository = TenshuConversationRepository(
                client = client,
                couldNotLook = COULD_NOT_LOOK,
            ),
        )
    }

    /**
     * Production path: read the durable realm-key hand-off ([RealmKeyMaterialBridge]) and
     * assemble over the shared replica, then install the real repository on [MiaSurface].
     * Idempotent; the runtime is process-lifetime.
     */
    fun install() {
        if (installed != null) return
        runtime.rebuildWith(::rebuildGraph)
        val assembly = assemble(
            handedOffMaterial = RealmKeyMaterialBridge.read(),
            relayBaseUrl = devRelayOverride(),
        )
        installed = assembly
        MiaSurface.installRepository(assembly.conversationRepository)
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
     * Tear down Mia's side of the route-1 provisioning (the teardown twin): seal the running
     * graph (`SyncManager.lock()`), purge Mia's per-app custody
     * copies (so the persisted joiner payload cannot silently resurrect the adoption), and
     * restore the honest not-connected surface. The shared replica is left alone (Control's
     * store).
     *
     * ⚠ The conversation goes with it: the repository that held it is dropped, and a thread of
     * answers about a realm this device has just been removed from is not something to keep.
     */
    fun deprovision() {
        val assembly = installed
        installed = null
        MiaSurface.installNotConnected()
        if (assembly != null) {
            runCatching { assembly.koin.get<SyncManager>().lock() }
            runtime.purgeCustody(assembly.koin.get<SecureStorageDelegate>())
        } else {
            runtime.purgeCustody(runtime.standaloneDelegate())
        }
    }

    /** Whether Mia still holds adopted custody at rest (the joiner payload) — the deprovision trigger. */
    fun hasResidualCustody(): Boolean {
        installed?.let { return runtime.hasResidualCustody(it.koin.get<SecureStorageDelegate>()) }
        return runtime.hasResidualCustody(runtime.standaloneDelegate())
    }
}
