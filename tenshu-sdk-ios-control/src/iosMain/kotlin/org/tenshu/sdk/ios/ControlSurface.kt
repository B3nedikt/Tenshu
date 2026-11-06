package org.tenshu.sdk.ios

import androidx.paging.map as pagingMap
import app.tenshu.error.ErrorCopyKey
import app.tenshu.control.ui.screens.sync.PairingError
import app.tenshu.control.data.AppRegistry
import app.tenshu.control.data.BiometricPromptCopy
import app.tenshu.control.data.BiometricPromptHost
import app.tenshu.control.data.BiometricUnlockDelegate
import app.tenshu.control.data.EnrollResult
import app.tenshu.control.data.IosBiometricUnlockDelegate
import app.tenshu.control.data.Event
import app.tenshu.control.data.EventRepository
import app.tenshu.control.data.MockAppRegistry
import app.tenshu.control.data.MockEventRepository
import app.tenshu.control.data.MockPublisherClient
import app.tenshu.control.data.MockRealmDataClient
import app.tenshu.control.data.NotificationMapper
import app.tenshu.control.data.RealmDataClient
import app.tenshu.control.data.SyncRepository
import app.tenshu.control.data.TenshuEventRepository
import app.tenshu.control.di.ProxyGraphJob
import app.tenshu.control.di.buildPlatformProxyModule
import app.tenshu.control.di.buildProxyModule
import app.tenshu.control.di.closeProxyGraph
import app.tenshu.control.di.proxyGraphJob
import org.koin.core.module.Module
import app.tenshu.control.data.PublisherClient
import app.tenshu.control.epc.EpcPairingHost
import app.tenshu.control.sync.HostPairingMonitor
import app.tenshu.control.ui.screens.apps.AppsViewModel
import app.tenshu.control.ui.screens.data.DataViewModel
import app.tenshu.control.ui.screens.home.HomeScreenViewModel
import app.tenshu.control.ui.screens.locked.LockedError
import app.tenshu.control.ui.screens.locked.LockedUiState
import app.tenshu.control.ui.screens.locked.LockedViewModel
import app.tenshu.control.ui.screens.newuser.NewUserError
import app.tenshu.control.ui.screens.newuser.NewUserStep
import app.tenshu.control.ui.screens.newuser.NewUserUiState
import app.tenshu.control.ui.screens.newuser.NewUserViewModel
import app.tenshu.control.ui.screens.recovery.RecoveryError
import app.tenshu.control.ui.screens.recovery.RecoveryStep
import app.tenshu.control.ui.screens.recovery.RecoveryUiState
import app.tenshu.control.ui.screens.recovery.RecoveryViewModel
import app.tenshu.control.ui.screens.settings.SettingsViewModel
import app.tenshu.control.ui.screens.services.FreeAccountSignupStep
import app.tenshu.control.ui.screens.services.FreeAccountSignupUiState
import app.tenshu.control.ui.screens.services.FreeAccountSignupViewModel
import app.tenshu.control.data.MockShareManager
import app.tenshu.control.data.ShareManager
import app.tenshu.control.notifications.MockPendingRequestsRepository
import app.tenshu.control.notifications.PendingRequest
import app.tenshu.control.notifications.PendingRequestAlerts
import app.tenshu.control.notifications.PendingRequestsRepository
import app.tenshu.control.notifications.TenshuPendingRequestsRepository
import app.tenshu.control.notifications.unreadRequestCount
import app.tenshu.control.ui.screens.notifications.NotificationsViewModel
import app.tenshu.control.ui.screens.shares.SharesUiState
import app.tenshu.control.ui.screens.shares.SharesViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.tenshu.core.TResult
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.epc.RealmKeyMaterialBridge
import org.tenshu.sync.client.syncService
import org.tenshu.sync.revokeDevice
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.proxy.pendingTombstoneCount
import org.tenshu.sync.proxy.purgeOutboundQueues
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import app.tenshu.control.ui.screens.sync.AddDeviceMode
import app.tenshu.control.ui.screens.sync.PairingStep
import app.tenshu.control.ui.screens.sync.PairingWizardUiState
import app.tenshu.control.ui.screens.sync.PairingWizardViewModel
import app.tenshu.control.ui.screens.sync.SyncDashboardUiState
import app.tenshu.control.ui.screens.sync.formatLastSeen
import org.tenshu.identity.deviceDisplayLabel
import app.tenshu.control.ui.screens.sync.SyncDashboardViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.koin.core.Koin
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.ExecutionContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.IosSecureStorageDelegate
import org.tenshu.core.common.epc.ControlSessionProvisioner
import org.tenshu.sync.proxy.SyncManagerImpl
import org.tenshu.core.common.util.PairingUri
import org.tenshu.core.common.util.nextSecureRandomBytes
import org.tenshu.core.getOrNull
import org.tenshu.core.paging.TenshuPagingPresenter
import kotlin.coroutines.CoroutineContext

/**
 * Wires the **real** Control view-model layer (`:control-shared-logic`) to iOS so a SwiftUI screen
 * can resolve Control's view models and observe their `StateFlow` / paged `Flow` natively — the
 * Control twin of [DriveReadSurface] (`docs/proposals/ios-control-handover.md`).
 *
 * The data sources are **injected** repositories: not-connected by default, the real proxy-backed
 * runtime once [installRealRuntime] runs, and the `:control-shared-logic` `Mock*` impls only
 * through [installFakes].
 *
 * Control's Koin DI lives in `:control-shared` (Compose, not on iOS), so this is the minimal
 * hand-wiring the SwiftUI client needs instead of `viewModelsModule()` — VMs are constructed
 * directly, mirroring [DriveReadSurface].
 */
object ControlSurface {

    // A couple of realm-data types so the Data section renders non-empty over fakes (File + a
    // made-up type that exercises the unknown-type generic fallback card). Declared first:
    // object-property initializers run top-to-bottom, and the installers below reference it.
    private val DEFAULT_REALM_DATA_TYPES = listOf("tenshu/file", "tenshu/contact")

    // ⚠⚠ **These default to "not connected", never to a Mock/Fake** — see
    // [NotConnectedControlSyncRepository] for the full reasoning. A fake default would keep
    // fabricated data off a user's screen only because `ControlIosApp.init` happens to call
    // [installRealRuntime] on every non-screenshot boot: an ordering guarantee, not a structural
    // one.
    //
    // ⚠ The fakes are what the DEBUG screenshot routes render over, and they are **opt-in** via
    // [installFakes], which those routes call explicitly. Do not make a fake the default: the safe
    // state must be the one you get by doing nothing.
    private var installedEventRepository: EventRepository = NotConnectedEventRepository()
    private var installedRealmDataClient: RealmDataClient = NotConnectedRealmDataClient()
    private var installedAppRegistry: AppRegistry = NotConnectedAppRegistry()
    private var installedSyncRepository: SyncRepository = NotConnectedControlSyncRepository()
    private var installedBiometricDelegate: BiometricUnlockDelegate = NotConnectedBiometricUnlockDelegate()
    private var installedShareManager: ShareManager = NotConnectedShareManager()
    private var installedPendingRequests: PendingRequestsRepository = NotConnectedPendingRequestsRepository()
    private var installedPublisherClient: PublisherClient = NotConnectedPublisherClient()

    // Retains the real proxy runtime's Koin. Its lazily-resolved singletons capture the
    // owning scope — the AsyncTaskExecutor's execution-context delegate resolves more `get()`s on
    // first use — so the container must outlive construction; holding it here keeps it (and the
    // whole proxy graph) alive for the process. Null until installRealRuntime() runs.
    private var realRuntimeKoin: Koin? = null

    /**
     * ⚠⚠ The SQLite shared replica, for Control (`ios-shared-store-sqlite.md` Appendix B.14): on
     * in every build (no switch, B.22). A `lazy` so nothing is read before first use.
     */
    private val sharedReplica: ControlSharedReplica by lazy { ControlSharedReplica.production() }

    // The process-lifetime host-pairing monitor (Control twin of the Koin `single` in `:control-shared`),
    // built lazily over [installedSyncRepository] and cached so every `pairingWizardViewModel()` shares
    // one live host session — exactly as the Compose app wires a single monitor. Lazy (not a field
    // initializer) so its 2s poll loop doesn't start until the pairing UI is first opened.
    private var installedHostPairingMonitor: HostPairingMonitor? = null

    // The proxy-side EPC app-pairing host, resolved off the real proxy graph in installRealRuntime().
    // Null over fakes (DEBUG screenshot routes) — epcPairingController() then substitutes a sample
    // host so the "Connect an app" screen still renders a waiting state without a relay.
    private var installedEpcPairingHost: EpcPairingHost? = null

    /** Swap the events source (a configured [MockEventRepository] for a specific Home state). */
    fun installEventRepository(repository: EventRepository) {
        installedEventRepository = repository
    }

    /** Swap the realm-data source backing the Data section's generic card list. */
    fun installRealmDataClient(client: RealmDataClient) {
        installedRealmDataClient = client
    }

    /** Swap the app registry backing the Apps section. */
    fun installAppRegistry(registry: AppRegistry) {
        installedAppRegistry = registry
    }

    /** Swap the sync repository backing the genesis + recovery state machines. */
    fun installSyncRepository(repository: SyncRepository) {
        installedSyncRepository = repository
    }

    /** Swap the biometric delegate the genesis flow probes for credential choice. */
    fun installBiometricDelegate(delegate: BiometricUnlockDelegate) {
        installedBiometricDelegate = delegate
    }

    /**
     * Swap the commerce account client backing the email-OTP sign-in screen. The default
     * is [NotConnectedPublisherClient]; [installRealRuntime] resolves the real one off the proxy
     * graph, and [installFakes] opts the DEBUG screenshot routes into [MockPublisherClient].
     *
     * ⚠ Which *real* client that is — [app.tenshu.control.data.CommercePublisherClient] or
     * [MockPublisherClient] — is decided in `platformProxyModule` by the billing-mock gate, not
     * here. A plain debug build binds the mock and never touches commerce; see
     * `ios-services-status-and-purchase.md` §3.8.
     */
    fun installPublisherClient(client: PublisherClient) {
        installedPublisherClient = client
    }

    /**
     * Swap the process-lifetime host-pairing monitor backing the "Show a code" tab.
     * [installRealRuntime] installs one built over the real proxy; until then the default is lazily
     * built over the installed [SyncRepository].
     */
    fun installHostPairingMonitor(monitor: HostPairingMonitor) {
        installedHostPairingMonitor = monitor
    }

    /** The cached default monitor, or a fresh one over the installed sync repo on first use. */
    private fun hostPairingMonitor(): HostPairingMonitor =
        installedHostPairingMonitor ?: HostPairingMonitor(installedSyncRepository)
            .also { installedHostPairingMonitor = it }

    /**
     * Install the **fake** repositories — sample events, sample pending requests, sample shares, a
     * well-formed-but-inert pairing link.
     *
     * ⚠ These are not the default (see the `installed*` declarations). This is an explicit opt-in,
     * and its callers are the DEBUG screenshot deep-links in
     * `ControlIosApp.init`, which need populated screens with no relay: a capture of the
     * notification center or the shares inventory over a *real* runtime would be empty, which is
     * exactly what those screenshots must not be.
     *
     * ⚠⚠ **Never call this on a path a user can reach.** Everything it installs answers
     * confidently and wrongly — including writes: `createRealm` and `approvePairingRequest`
     * report success, and `unlockLocal` accepts any passphrase.
     */
    fun installFakes() {
        installedEventRepository = MockEventRepository()
        installedRealmDataClient = MockRealmDataClient(DEFAULT_REALM_DATA_TYPES)
        installedAppRegistry = MockAppRegistry()
        installedSyncRepository = FakeControlSyncRepository()
        installedBiometricDelegate = FakeControlBiometricUnlockDelegate()
        installedShareManager = MockShareManager()
        installedPendingRequests = MockPendingRequestsRepository()
        // ⚠ The mock, not a not-connected client: a screenshot route that opened the sign-in screen
        // over `NotConnectedPublisherClient` would capture an error, and one over the *real* client
        // would email a stranger a code. Neither is a picture of the flow.
        //
        // ⚠⚠ Over an **in-memory** delegate, not the Keychain one `standaloneSecureStorage()`
        // builds. Two reasons, and the second is the load-bearing one: a capture must not depend on
        // whatever a previous mock signup left in the bucket (the same route would photograph a
        // bound account one day and the sign-in form the next), and `installFakes` runs inside
        // `ControlIosApp.init`, where a Koin container plus a Keychain read is real work — and real
        // failure surface — on a path whose whole job is to draw a screen.
        installedPublisherClient = MockPublisherClient(InMemorySecureStorageDelegate())
        installedHostPairingMonitor = null
        installedEpcPairingHost = null
        realRuntimeKoin = null
    }

    /**
     * Swap every installed repository to the **real, ProxyModule-backed** Control runtime
     * (`docs/proposals/ios-control-handover.md` § "C4-RT"). Assembles
     * `proxyModule` (which `includes(coreCommonModule())`) + the iosMain `platformProxyModule` — the
     * same **production** wiring the android/desktop apps boot from (the "mirror production wiring"
     * rule), NOT a test proxy — and resolves the real `TenshuSyncRepository` / `HostPairingMonitor` /
     * `TenshuRealmDataClient` / `TenshuAppRegistry` over a real `TenshuClient`, plus the inert iOS
     * `BiometricUnlockDelegate`.
     *
     * Genesis (`NewUserViewModel.createRealm()`) then mints a real master key + A4 device key and
     * seeds the realm into the shared SQLite replica. Local-first (ADR 0019): genesis
     * completes with **no relay** — relay registration is deferred until a storage subscription opens
     * the gate. The SwiftUI screens bind through the same `installed*` seams, so nothing above this
     * line changes; only the data source does.
     *
     * Idempotent: a second call is a no-op (the runtime is process-lifetime).
     */
    fun installRealRuntime() {
        if (realRuntimeKoin != null) return
        // Debug-only TENSHU_DEV_RELAY env override so the sim harnesses (-genesisAutoComplete
        // etc.) can run against a dev :sync-relay-server:run instead of the production relay.
        // B.14: the SQLite store's bindings, last (the stand-in when the binding could not be
        // installed). On the main thread, before the graph exists, as the binding requires.
        val replicaModule = sharedReplica.runtimeModule()
        val koin = koinApplication { modules(runtimeModules() + replicaModule) }.koin
        realRuntimeKoin = koin
        val client = koin.get<TenshuClient>()
        sharedReplica.bindLoops(koin)
        // The sync-activity capsule (`ios-sync-activity-indicator.md` §6a.2). Control does not go
        // through `FirstPartyLocalRuntime.assembleBase`, so it installs the surface itself.
        // ⚠ The id is the one this graph hands `initSyncService` (`PlatformProxyModule`, with no
        // `hostAppId` override): the surface reads the row THIS runtime writes.
        // ⚠ `RealmStorageSurface` is deliberately not installed here: Control mounts no storage
        // bar, and its capsule is told so (`showsStorageBar: false`).
        SyncActivitySurface.install(client, hostAppId = org.tenshu.sync.RuntimeIdentity.CONTROL_APP_ID)
        installedSyncRepository = koin.get()
        installedBiometricDelegate = koin.get()
        installedHostPairingMonitor = koin.get<HostPairingMonitor>()
        installedRealmDataClient = koin.get()
        installedAppRegistry = koin.get()
        installedEpcPairingHost = koin.get()
        // EventRepository is `dataModule`'s binding (Compose `:control-shared`, not on iOS) — build
        // the same `TenshuEventRepository` directly over the real client + a fresh NotificationMapper.
        installedEventRepository = TenshuEventRepository(client, NotificationMapper())
        // `ShareManager` *is* a proxy-module binding (`ProxyModule.kt`), so resolve rather than
        // construct — the Koin graph owns its lifetime, and a second instance would hold a second
        // rosterRevision, so a removal in one would not converge the other.
        installedShareManager = koin.get()
        // `PendingRequestsRepository` has no iOS binding to resolve (the Compose app's is a
        // `:control-shared` Koin `single`), so build the client-backed one here — the
        // `TenshuEventRepository` treatment. ⚠ On a scope of this graph's root job (Q45): the 30 s
        // grant poll lives as long as the graph, and a rebuild's Koin close stops the old one's.
        installedPendingRequests = TenshuPendingRequestsRepository(
            client,
            scope = proxyGraphJob(koin).scope(ProxyGraphJob.Owners.CONTROL_SURFACE_PENDING_REQUESTS),
        )
        // The commerce account client is a `platformProxyModule` single (process-lifetime, so the
        // persisted session rehydrates once and feeds the entitlement clients' synchronous token
        // provider) — resolve it, never construct a second one.
        installedPublisherClient = koin.get()
        // At a rebuild, the OS alerts over the new graph's feed, if the scene had started them
        // (B.16 (228)); never at the first install, where `startRequestAlerts` has not run.
        if (requestAlertsStarted) requestAlerts.start(installedPendingRequests.requests)
        // ⚠⚠ B.16: the graph handed to the host of §6 **last**, once everything above is installed
        // over it (a not-ready stand-in is stopped as it is handed over). The host stops it at a
        // fence and rebuilds it in the foreground (or at once after this device's forget, (217)).
        sharedReplica.graphInstalled(koin, stop = { stopGraph(koin) }, rebuild = ::rebuildRealRuntime)
    }

    /**
     * ⚠ The host's rebuild of Control's graph (B.16, §6 step 4), on the main thread: the old graph
     * orphaned (its store already released by the host) and [installRealRuntime] run again, which
     * reinstalls every `installed*` seam over the new graph. Only the switched host calls it.
     */
    private fun rebuildRealRuntime() {
        val old = realRuntimeKoin
        realRuntimeKoin = null
        // ⚠⚠ B.16 (229): the old graph is closed, not orphaned: its singletons are dropped.
        // Q45: its root job is cancelled first, so every scope the proxy module made stops too.
        runCatching { old?.let(::closeProxyGraph) }
        installRealRuntime()
    }

    /**
     * ⚠⚠ §6 step 2 for Control's graph (B.16 (218)): every part
     * of the old graph that would go on talking to the relay is stopped. Its `SyncManager` is locked
     * (in memory only: `lock()` drops the session's keys and persists nothing, so the rebuilt graph
     * starts as a relaunch does), its host-pairing session stopped (the ephemeral key zeroed), and
     * the OS-alert collector over its feed cancelled. Never throws.
     */
    private fun stopGraph(koin: Koin) {
        runCatching { koin.get<SyncManager>().lock() }
        // The proxy's pairing context and ephemeral key, in memory, at once; then the monitor's
        // own countdown and flows (through an op, which answers a failed result, never a throw).
        runCatching { koin.get<SyncManager>().stopPairing() }
        runCatching { koin.getOrNull<HostPairingMonitor>()?.stopPairing() }
        requestAlerts.stop()
    }

    /**
     * The modules Control's in-process proxy is assembled from — [installRealRuntime]'s graph, in
     * one place so the per-app service-version guard (`DeclaredServiceVersionsTest`,
     * `ios-per-app-frameworks.md` §6.2) assembles exactly what Control does. Control does not go
     * through `FirstPartyLocalRuntime.assembleBase`, so this is the second path that decides which
     * services, at which versions, a route-1 store is opened with.
     *
     * Fresh module instances rather than the shared `proxyModule` / `platformProxyModule` vals: the
     * same content, and [installRealRuntime] runs once per process, so production is unchanged;
     * a test that assembles it gets singletons of its own (Koin caches them on the module).
     * The `TENSHU_DEV_RELAY` override (debug binaries only) comes last, so it wins.
     *
     * ⚠ [controlOnlyModule] carries what Control binds and no other route-1 app may: the Face ID
     * realm unlock. In the shared `platformProxyModule` it would put LocalAuthentication into every
     * app's binary though only Control's screens resolve it (`ios-per-app-frameworks.md` §3.3). It
     * adds no service, so the declared vector is unchanged.
     */
    internal fun runtimeModules(): List<Module> =
        listOfNotNull(buildProxyModule(), buildPlatformProxyModule(), controlOnlyModule(), devRelayOverrideModule())

    /**
     * Face ID / Touch ID realm unlock: the wrap-key is biometric-protected in a user-presence-gated
     * Keychain item (SecAccessControl). Enrolled from Settings and consumed by the returning-user
     * unlock screen (`LockedViewModel.onUnlockBiometric`, through [installRealRuntime]); the
     * passphrase floor stays the universal fallback.
     */
    private fun controlOnlyModule(): Module = module {
        single<BiometricUnlockDelegate> { IosBiometricUnlockDelegate() }
    }

    /**
     * The Home view model plus a presenter already collecting its paged event feed into a
     * snapshot the SwiftUI list observes (the `TenshuPagingPresenter` bridge, as Drive does).
     *
     * ⚠ The presenter pages [ControlEventRow], not the shared `Event` it is mapped from — see
     * that type for why the sealed hierarchy is flattened before it reaches Swift. The mapping
     * rides `PagingData.map`, so it runs per *loaded page*, never over the whole feed.
     */
    class HomeBound internal constructor(
        val viewModel: HomeScreenViewModel,
        val presenter: TenshuPagingPresenter<ControlEventRow>,
    )

    /** Build the Home surface; collect on the main dispatcher (live on iOS). */
    fun homeBound(): HomeBound = homeBound(CoroutineScope(Dispatchers.Main), Dispatchers.Main)

    /** Home surface with explicit dispatchers (a smoke can inject `Dispatchers.Default`). */
    fun homeBound(scope: CoroutineScope, presenterContext: CoroutineContext): HomeBound {
        val viewModel = HomeScreenViewModel(installedEventRepository)
        val presenter = TenshuPagingPresenter<ControlEventRow>(scope, presenterContext)
        presenter.collect(
            viewModel.eventFlow.map { paging -> paging.pagingMap(ControlEventRow::from) },
        )
        return HomeBound(viewModel, presenter)
    }

    /**
     * The read-only Services status (ios-services-status-and-purchase.md): plan,
     * source (local account vs synced realm), used/limit bytes, account email.
     * Over the real runtime it binds the commerce `PublisherClient` + the
     * `StorageStatus` row; over fakes (DEBUG screenshot routes) it renders the
     * empty floor so the Settings section still shows.
     */
    fun servicesStatusModel(): ServicesStatusModel {
        val koin = realRuntimeKoin ?: return ServicesStatusModel.empty()
        return ServicesStatusModel.over(
            publisherClient = koin.get(),
            client = koin.get(),
        )
    }

    /** The Data section view model (observe `uiState: StateFlow<DataUiState>`). */
    fun dataViewModel(): DataViewModel = DataViewModel(installedRealmDataClient)

    // ---- Commerce sign-in ------------------------------------------------------------------

    /**
     * The email-OTP sign-in / sign-up view model — the **shared** `FreeAccountSignupViewModel`
     * (`:control-shared-logic`, so iOS runs the same state machine Compose does), bound to the
     * installed [PublisherClient]. Observe `uiState`, drive with `onEmailChange` / `onSubmit` /
     * `onCodeChange` / `onVerify` / `onResend` / `onEditEmail`.
     *
     * **Why iOS needs it.** `StorageSyncGate` holds a
     * personal realm local-only until a subscription is bound, and `joinedFundedRealm` is false for
     * a host — so a realm created on iOS never syncs. The documented rescue is to sign in from a
     * *desktop* peer, which an iOS-only user does not have
     * (`ios-services-status-and-purchase.md` §3.5). Verifying here binds this realm's `ownerId`,
     * the mint writer fetches the signed Free record, and the gate opens on this device.
     *
     * ⚠ Nothing else has to be wired for that: `platformProxyModule` passes
     * `publisherClient.account` to `initEntitlementStorage` alongside the commerce minter, so the
     * entitlement follows a successful verify with no further plumbing.
     *
     * ⚠⚠ A fresh instance per call, deliberately: the flow is modal and its state (typed email,
     * pending OTP) must not survive a cancelled screen and reappear behind the next one. The
     * *session* it binds is process-lifetime and lives on the client, not here.
     */
    fun signInViewModel(): FreeAccountSignupViewModel =
        FreeAccountSignupViewModel(installedPublisherClient)

    /**
     * The step, flattened to a Swift-friendly key — the [genesisStepKey] treatment, so no
     * SKIE-bridged sealed class reaches SwiftUI (the `SyncDashboardView` discipline).
     */
    fun signInStepKey(state: FreeAccountSignupUiState): String = when (state.step) {
        FreeAccountSignupStep.Form -> "form"
        FreeAccountSignupStep.EnterCode -> "code"
        FreeAccountSignupStep.Done -> "done"
    }

    /**
     * Whether the CTA may fire: the view model gates `onVerify` on a complete code and ignores the
     * call otherwise, and a button that silently does nothing reads as a broken app. Kept here
     * rather than in Swift so the length lives next to the state machine that enforces it.
     */
    fun signInCanVerify(state: FreeAccountSignupUiState): Boolean =
        !state.busy && state.code.length == FreeAccountSignupViewModel.OTP_LENGTH

    // ---- Shares ----------------------------------------------------------------------------

    /**
     * The Data → Shares view model — observe `uiState`, call `onRevokeClicked(shareId)` /
     * `onRevokeConfirmed()` / `onRevokeDismissed()`. Over the real runtime it reads the
     * realm-wide `FileService.shares` projection plus the shared-realm memberships;
     * over fakes it renders `MockShareManager`'s sample.
     */
    fun sharesViewModel(): SharesViewModel = SharesViewModel(installedShareManager)

    /** Flatten the link-share summaries into Swift-friendly, pre-formatted rows. */
    fun shareRows(state: SharesUiState): List<ControlShareRow> = state.shares.map(::shareRow)

    /** Flatten the shared-folder summaries, keeping both unknowns nullable (see [ControlSharedFolderRow]). */
    fun sharedFolderRows(state: SharesUiState): List<ControlSharedFolderRow> =
        state.sharedFolders.map(::sharedFolderRow)

    // ---- Notification center ---------------------------------------------------------------

    /**
     * The notification surface: the view model to act through, plus one combined snapshot flow
     * to observe. See [ControlNotificationsSnapshot] for why the view model's four flows are
     * combined here rather than in SwiftUI.
     *
     * ⚠ Actions take a **request id**, not a row: the sealed leaf never crosses the bridge, so
     * [acceptRequest]/[declineRequest]/[dismissRequest] re-resolve it Kotlin-side.
     */
    class NotificationsBound internal constructor(
        val viewModel: NotificationsViewModel,
        val snapshots: StateFlow<ControlNotificationsSnapshot>,
    )

    fun notificationsBound(): NotificationsBound =
        notificationsBound(CoroutineScope(Dispatchers.Main))

    // ---- OS elevation for inbound requests -------------------------------------------------
    //
    // `ios-notification-elevation.md` (D1-rev). Two drivers, exactly as Android has:
    // [startRequestAlerts] while this process lives, and [runPendingRequestRefresh] on the
    // BGAppRefresh wake that `ControlBackgroundRefresh.swift` owns. Both feed the shared
    // `PendingRequestAlerts` diff, so a request that survives a restart is not re-alerted twice
    // and one resolved elsewhere is withdrawn.

    private var requestAlertsStarted = false

    /**
     * The live feed's collector, so a rebuilt graph's feed replaces the stopped one's
     * (B.16 (228)): the old collector is cancelled when the shared store's host stops Control's
     * graph, and [rebuildRealRuntime] starts one over the new repository.
     */
    internal val requestAlerts = RequestAlerts { PendingRequestAlerts(IosPendingRequestNotifier()) }

    /**
     * Begin alerting on the live feed for the life of the process. Idempotent — SwiftUI may call
     * it from more than one scene, and a second `PendingRequestAlerts` would double-post.
     *
     * ⚠ Only meaningful once [installRealRuntime] has run: over the not-connected default the feed
     * is permanently empty (by construction — see [NotConnectedPendingRequestsRepository]), so this
     * is a no-op rather than a source of fabricated alerts.
     */
    fun startRequestAlerts() {
        if (requestAlertsStarted) return
        requestAlertsStarted = true
        // ⚠⚠ The authorization request deliberately does NOT live here — see
        // [requestNotificationPermission]. This runs from the root scene's `.task` and from every
        // `scenePhase == .active`, so asking here would fire the system prompt on the very first
        // launch, underneath "Welcome to Tenshu", before the user knew what Control was.
        requestAlerts.start(installedPendingRequests.requests)
    }

    /**
     * Ask for notification authorization, from a moment where the reason is on screen.
     *
     * ## ⚠⚠ Why this is separate from [startRequestAlerts]
     *
     * The system prompt is **one-shot**: a denial cannot be re-requested — the user has to find it
     * in Settings. [startRequestAlerts] runs on the root scene's `.task` and on every foreground,
     * so bundling the request into it would spend that single chance at app launch, before
     * onboarding, with no context at all. Apple's guidance is the opposite: ask in context, once
     * the notification's value is evident.
     *
     * ⚠ The cost of getting this wrong is functional, not cosmetic. These alerts are the whole point
     * of the background wake — the path that surfaces a pairing or share request arriving
     * while Control is closed. A denial at "Welcome to Tenshu" silently disables that forever, and
     * nothing later in the app can explain the connection.
     *
     * ⚠⚠ Call sites are load-bearing: this asks **nowhere** unless a UI explicitly calls it: the
     * pairing screen (which produces the requests) and the notification centre (opened
     * by a user who is already looking for them). Adding a third inbound-request surface owes a call
     * here, or its requests arrive silently.
     *
     * Idempotent and cheap — [IosPendingRequestNotifier.requestAuthorizationIfNeeded] no-ops unless
     * the status is still `NotDetermined`, so a repeat visit never re-prompts.
     */
    fun requestNotificationPermission() {
        IosPendingRequestNotifier.requestAuthorizationIfNeeded()
    }

    /**
     * The BGAppRefresh entry: install the runtime, re-poll the poll-only sources, and let the
     * diff post whatever is new. Returns a short status string for the Swift side to log.
     *
     * ⚠⚠ **A sealed realm is a legitimate empty answer, not a failure.** Control's *default* is
     * headless (`OnboardingView`'s setup step leads with device protection; the passphrase is an
     * opt-in), so on most devices the runtime autoloads here with no user present and the feed is
     * readable. A user who chose "Encrypt on unlock only" has no in-memory keys in a
     * background-launched process, so this reads nothing and posts nothing — which is precisely
     * what that setting promises. Do not "fix" it by trying to unlock.
     *
     * ⚠ The wake is best-effort and OS-scheduled. That is not a defect of this design; Android's
     * closed-process path is a ~hourly `DrainSync` hook and is best-effort in the same way.
     */
    suspend fun runPendingRequestRefresh(maxWaitSeconds: Int = 10): String {
        // The runtime's open is admitted by the implicit token (B.5 (80)).
        installRealRuntime()
        if (realRuntimeKoin == null) return "request-refresh: no runtime"
        // ⚠⚠ B.15: the wake's work under an entry token, so the gate admits it and the
        // token's expiry ends it.
        return SharedReplicaEntries.withProtection("control-refresh") {
            // B.14: a wake is the shared store's worker trigger too.
            sharedReplica.wake()
            startRequestAlerts()
            installedPendingRequests.refresh()
            // Give the poll-only sources (membership grants) a window to answer inside the OS budget.
            kotlinx.coroutines.delay(maxWaitSeconds * 1000L)
            "request-refresh: ${installedPendingRequests.requests.value.size} pending"
        } ?: "request-refresh: no protected time, or it expired (S0b B.15)"
    }

    /** Notification surface with an explicit scope (a smoke can inject `Dispatchers.Default`). */
    fun notificationsBound(scope: CoroutineScope): NotificationsBound {
        val viewModel = NotificationsViewModel(installedPendingRequests)
        val snapshots = combine(
            viewModel.requests,
            viewModel.seenIds,
            viewModel.unread,
            viewModel.actionState,
        ) { requests, seen, unread, actions ->
            ControlNotificationsSnapshot(
                rows = requests.map { request ->
                    val action = actions[request.id]
                    ControlPendingRequestRow.from(
                        request = request,
                        seen = request.id in seen,
                        inProgress = action?.inProgress == true,
                        // ⚠⚠ Rendered from the catalogue by key, never a proxy sentence (§5.0).
                        errorMessage = action?.error?.let { ErrorCopySurface.labelForKey(it) },
                    )
                },
                // From the shared rule, not `unread.size`: the same count the Compose badge
                // uses, pinned by `PendingRequestPresentationTest` where this file cannot be.
                unreadCount = unreadRequestCount(requests, seen),
            )
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            ControlNotificationsSnapshot(rows = emptyList(), unreadCount = 0),
        )
        return NotificationsBound(viewModel, snapshots)
    }

    /**
     * Accept the request with [id] — join a shared realm, or confirm a redeemed invite as a
     * contact named [petname] with [sasConfirmed] recording the §5.3 code comparison. Both
     * parameters are ignored for a grant, which has neither.
     *
     * Resolving the leaf in Kotlin is the point: Swift holds a flat row and an id, and the `when`
     * that maps id → action stays exhaustive (`NotificationsViewModel.acceptById`). An unknown id
     * is a no-op rather than a crash — the request may have resolved between render and tap.
     *
     * ⚠⚠ [shownFingerprint] is the row's [ControlPendingRequestRow.shownFingerprint] — the raw
     * claim of the card the user tapped. It, not the claim re-resolved by id, is what an invite's
     * confirmation submits (threat model T-CI-10): the synced claim can flip by merge before the
     * Swift collector re-renders, and a re-read would send the new key with the old code's tick.
     */
    fun acceptRequest(
        bound: NotificationsBound,
        id: String,
        shownFingerprint: String,
        petname: String,
        sasConfirmed: Boolean,
    ) {
        bound.viewModel.acceptById(id, shownFingerprint, petname, sasConfirmed)
    }

    /** Decline the request with [id] — discard a quarantined grant, or reject a redeemed invite. */
    fun declineRequest(bound: NotificationsBound, id: String) {
        when (val request = bound.viewModel.requests.value.firstOrNull { it.id == id }) {
            is PendingRequest.MembershipGrant -> bound.viewModel.declineGrant(request)
            is PendingRequest.ContactInviteRedeemed -> bound.viewModel.declineInvite(request)
            null -> Unit
        }
    }

    /** Mark the request with [id] seen — clears it from the badge on every device. */
    fun dismissRequest(bound: NotificationsBound, id: String) {
        bound.viewModel.requests.value.firstOrNull { it.id == id }
            ?.let { bound.viewModel.dismiss(it) }
    }

    /** The Apps section view model (observe `uiState: StateFlow<AppsUiState>`). */
    fun appsViewModel(): AppsViewModel = AppsViewModel(installedAppRegistry)

    /** The Settings view model (stateless; here for parity with the other platforms' settings wiring). */
    fun settingsViewModel(): SettingsViewModel = SettingsViewModel()

    // ---- Genesis onboarding ----------------------------------------------------------------
    //
    // The genesis + recovery VMs drive their own sealed `step` state machines. SKIE renders Kotlin
    // sealed classes as Swift enums, but their case ergonomics are fiddly across the bridge — so the
    // SwiftUI side observes plain `String` step keys + `String?` error copy mapped *here*, never
    // touching the sealed types directly. (UiState scalars — passphrase, busy flags — cross fine.)

    // NB: do NOT name these `newUser*` — Objective-C reserves the `new` method family, and
    // Kotlin/Native silently drops `new`-prefixed selectors from the generated header (the Swift
    // side then can't see them). `genesis*` keeps them exported.

    /** The new-user genesis view model (observe `uiState: StateFlow<NewUserUiState>`). */
    fun genesisViewModel(): NewUserViewModel =
        NewUserViewModel(installedSyncRepository, installedBiometricDelegate)

    /** The recovery-from-phrase view model (observe `uiState: StateFlow<RecoveryUiState>`). */
    fun recoveryViewModel(): RecoveryViewModel = RecoveryViewModel(installedSyncRepository)

    /** Stable key for the new-user step, so SwiftUI can `switch` without touching the sealed type. */
    fun genesisStepKey(state: NewUserUiState): String = when (state.step) {
        NewUserStep.SetupUnlock -> "setup"
        NewUserStep.Bootstrapping -> "bootstrapping"
        NewUserStep.YoureSetUp -> "set_up"
        NewUserStep.Done -> "done"
    }

    /** Render the typed new-user error to user copy (null when there's no error). */
    fun genesisErrorMessage(state: NewUserUiState): String? = when (val error = state.error) {
        null -> null
        is NewUserError.PassphraseTooShort -> "Passphrase must be at least ${error.minLength} characters."
        NewUserError.PassphrasesDoNotMatch -> "Passphrases don't match."
        NewUserError.LocalUnlockSetupFailed -> "Couldn't set up local unlock — try again."
        NewUserError.BiometricFinalizeFailed -> "Couldn't finalize biometric setup. Set a passphrase instead."
        is NewUserError.BiometricEnrollFailed -> error.detail ?: "Biometric setup failed. Set a passphrase instead."
        NewUserError.RealmBootstrapFailed -> "Couldn't create your realm. Please try again."
    }

    /** Stable key for the recovery step, so SwiftUI can `switch` without touching the sealed type. */
    fun recoveryStepKey(state: RecoveryUiState): String = when (state.step) {
        RecoveryStep.Entry -> "entry"
        RecoveryStep.Processing -> "processing"
        RecoveryStep.Success -> "success"
        RecoveryStep.Done -> "done"
    }

    /** Render the typed recovery error to user copy (null when there's no error). */
    fun recoveryErrorMessage(state: RecoveryUiState): String? = when (val error = state.error) {
        null -> null
        is RecoveryError.UnknownWord -> "'${error.word}' isn't a valid recovery word. Check the spelling."
        is RecoveryError.InvalidLength -> "Recovery phrases are 24 words. You entered ${error.wordCount}."
        RecoveryError.ChecksumMismatch ->
            "This phrase doesn't look right. Double-check each word — even one wrong word breaks recovery."
        RecoveryError.PhraseRejected -> "That phrase was not accepted. Check each word and try again."
        // ⚠⚠ From the shared catalogue by key, never from a message (§5.0). Kept in step with
        // control-shared's `recovery_error_*` strings by hand, as every entry in this file is.
        is RecoveryError.Unexpected -> ErrorCopySurface.labelForKey(error.copyKey)
    }

    // ---- Realm-device probe ----------------------------------------------------------------
    //
    // "Am I already a realm device?" — the **durable** launch-time gate `ControlRootView` uses to
    // choose onboarding vs the main tabs across process death (the Control twin of Drive's
    // `RootView` shared-Keychain probe). Sourced from `getUnlockStatus().isSetUp`, which is true
    // once genesis (or recovery) has written an unlock credential (`UNLOCK_WRAPPER` /
    // `BIOMETRIC_WRAPPER`) into secure storage — that survives a relaunch and is independent of the
    // realm's sealed/unlocked state (a freshly-launched, still-sealed realm is *still* a realm
    // device). Deliberately **not** `getSyncStatus().ownerId`: that's null whenever the realm is
    // sealed (keys not yet derived from the wrapper), so it would wrongly re-onboard a set-up
    // device on every cold start. Returns false on any failure (treat as first-run).

    /** True iff this device has already completed genesis/recovery (durable across process death). */
    suspend fun isRealmDevice(): Boolean =
        installedSyncRepository.getUnlockStatus().getOrNull()?.isSetUp?.get() == true

    /**
     * The canonical launch-time landing decision, mirroring `TenshuLandingStateRepository.detect()`
     * so `ControlRootView` routes the same three ways the Compose app does: **`"locked"`** (a realm
     * device whose keys aren't loaded — show the unlock screen), **`"active"`** (realm present +
     * unlocked — main tabs), or **`"first_time"`** (no realm — onboarding). `isLocked` is checked
     * first because a sealed realm has a wrapper but no `ownerId` yet (keys not derived). Returns a
     * plain `String` key (the `genesisStepKey` discipline) so SwiftUI never switches on a sealed type.
     */
    suspend fun landingStateKey(): String {
        val unlock = installedSyncRepository.getUnlockStatus().getOrNull()
        if (unlock?.isLocked?.get() == true) return "locked"
        val ownerId = installedSyncRepository.getSyncStatus().getOrNull()?.ownerId
        return if (ownerId != null) "active" else "first_time"
    }

    // ---- Returning-user unlock (the LandingState.Locked screen) -----------------------------
    //
    // A genesised/recovered device reopened in a fresh process is **sealed**: the unlock wrapper
    // is persisted but the master keys aren't derived (the SyncManager autoload stays sealed once a
    // wrapper exists). `LockedViewModel` drives the unlock — the passphrase floor (`unlockLocal`),
    // or, when biometric unlock has been enrolled ([enableBiometricUnlock]), the Face ID / Touch ID
    // path (`onUnlockBiometric` → the real `IosBiometricUnlockDelegate`). SwiftUI observes `uiState`
    // (including `biometricAvailable` / `biometricEnrolled`) and maps the typed `LockedError` to copy
    // here (the `genesisErrorMessage` discipline).

    /** The returning-user unlock view model (observe `uiState: StateFlow<LockedUiState>`). */
    fun unlockViewModel(): LockedViewModel =
        LockedViewModel(installedSyncRepository, installedBiometricDelegate)

    /**
     * An inert [BiometricPromptHost] for `LockedViewModel.onUnlockBiometric`. iOS has no
     * host-window requirement — `LocalAuthentication` presents its own sheet — so this is the no-arg
     * marker the shared VM contract asks for.
     */
    fun biometricPromptHost(): BiometricPromptHost = BiometricPromptHost()

    /** Copy for the Face ID / Touch ID sheet on the returning-user unlock screen. */
    fun unlockBiometricPromptCopy(): BiometricPromptCopy = BiometricPromptCopy(
        title = "Unlock Tenshu Control",
        subtitle = "Use Face ID to unlock your realm.",
        negativeButton = "Use passphrase",
    )

    // ---- Biometric-unlock enrollment (Settings "Unlock with Face ID") -----------------------

    // The wrap-key size the proxy's biometric wrapper expects (mirrors the VMs' WRAP_KEY_BYTES).
    private const val WRAP_KEY_BYTES = 32

    /** Whether the device can present a Face ID / Touch ID / passcode prompt (a secure lock exists). */
    suspend fun biometricUnlockAvailable(): Boolean = installedBiometricDelegate.isAvailable()

    /** Whether biometric unlock is currently enrolled on this device (the wrap-key blob is present). */
    suspend fun biometricUnlockEnrolled(): Boolean = installedBiometricDelegate.hasEnrolled()

    /**
     * Enroll biometric unlock (Settings toggle → on). **Enroll-first**: mint a random 32-byte
     * wrap-key, biometric-protect it in the Keychain via the delegate, and only on success wrap the
     * live realm secret under it (`setUpBiometricUnlock`) so a delegate failure never strands a
     * proxy-side wrapper. Requires the realm to be unlocked (Settings is reachable only when it is).
     *
     * Returns a `genesisStepKey`-style String the Swift layer switches on:
     *  - `"enrolled"`      — success; the next cold start offers Face ID.
     *  - `"cancelled"`     — the user backed out of the prompt.
     *  - `"unavailable"`   — no biometric / passcode configured on the device.
     *  - `"error:<detail>"`— enrollment or the proxy wrap failed; nothing was persisted.
     */
    suspend fun enableBiometricUnlock(): String {
        val wrapKey = nextSecureRandomBytes(WRAP_KEY_BYTES)
        return when (val enroll = installedBiometricDelegate.enrollWrapKey(
            wrapKey,
            biometricPromptHost(),
            unlockBiometricPromptCopy(),
        )) {
            is EnrollResult.Success -> {
                val ok = installedSyncRepository.setUpBiometricUnlock(wrapKey).getOrNull()?.get() == true
                if (ok) {
                    "enrolled"
                } else {
                    // The proxy refused (realm sealed / no plaintext): undo the on-device blob so we
                    // never leave a biometric wrapper the proxy can't honor.
                    installedBiometricDelegate.clear()
                    "error:Couldn't finish Face ID setup. Try again after unlocking."
                }
            }
            is EnrollResult.CancelledByUser -> "cancelled"
            is EnrollResult.NotAvailable -> "unavailable"
            is EnrollResult.Failure -> "error:${enroll.message ?: "Face ID setup failed."}"
        }
    }

    /** Disable biometric unlock (Settings toggle → off): drop both the on-device blob and the proxy wrapper. */
    suspend fun disableBiometricUnlock(): Boolean {
        installedBiometricDelegate.clear()
        return installedSyncRepository.clearBiometricSetup().getOrNull()?.get() == true
    }

    /** Render the typed unlock error to user copy (null when there's no error). */
    fun unlockErrorMessage(state: LockedUiState): String? = when (val error = state.error) {
        null -> null
        LockedError.PassphraseInvalid -> "That passphrase didn't work. Try again."
        LockedError.BiometricEnrollmentChanged ->
            "Your biometric enrollment changed. Unlock with your passphrase, then re-enable biometric in Settings."
        is LockedError.BiometricUnlockFailed ->
            error.detail ?: "Biometric unlock failed. Use your passphrase to continue."
        LockedError.RecoveryPhraseUnreadable ->
            "This device can't read its saved recovery phrase, so unlocking can't succeed. Use “Add This Device” from another device that is still signed in."
    }

    /**
     * Re-seal the realm ("Lock now" from Settings): purges this session's in-memory keys via the
     * `LockRealm` op so the next `landingStateKey()` returns `"locked"` and `ControlRootView` routes
     * back to the unlock screen. Returns true on success (locking can't meaningfully fail).
     */
    suspend fun lockRealm(): Boolean =
        installedSyncRepository.lock().getOrNull()?.get() == true

    /**
     * Publish this realm's key material — `derivedKeys` (sharedIdentity + sharedVolume) + the A4 device
     * signing key — into the **shared `app.tenshu.shared` Keychain group** so the co-resident Drive app
     * can read them and (route-1 in-process collapse) encrypt + upload sync blocks **itself**
     * while it is foreground, instead of waiting for this (suspendable) Control to wake. The durable
     * shared-Keychain hand-off the route-1 key-custody decision selected. Requires an **unlocked** realm
     * (the in-memory `currentRealmContext()`); returns a non-secret fingerprint summary so a harness can
     * confirm Drive reads the *same* bytes. ⚠ Durably shares realm key material with a second first-party
     * app — see the threat-model A4-custody + realm-content-key-sharing amendment.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun publishRealmKeysToSharedKeychain(): String {
        val koin = realRuntimeKoin ?: return "no real runtime"
        val syncManager = koin.get<SyncManager>()
        val context = syncManager.currentRealmContext() ?: return "no realm context (locked / not set up)"
        val a4 = syncManager.getDeviceSigningKeyPair()
        val deviceBlob = Base64.encode(a4.publicKey) + "|" + Base64.encode(a4.privateKey)
        // A13: the realm encryption keypair — what seals contact invites; derivable here
        // (unlocked host) or held (joiner). Without it Drive's sharing cluster is read-only.
        val a13 = syncManager.getRealmEncryptionKeyPair()
        val material = RealmKeyMaterial(
            sharedIdentityKey = context.derivedKeys.sharedIdentityKey,
            sharedVolumeKey = context.derivedKeys.sharedVolumeKey,
            deviceSigningBlob = deviceBlob.encodeToByteArray(),
            ownerId = context.ownerId,
            deviceId = context.deviceId,
            realmEncryptionPublicKey = a13?.publicKey,
            realmEncryptionPrivateKey = a13?.privateKey,
        )
        if (!RealmKeyMaterialBridge.publish(material)) return "publish failed"
        return "published  id=${RealmKeyMaterialBridge.fingerprint(material.sharedIdentityKey)}" +
            "  vol=${RealmKeyMaterialBridge.fingerprint(material.sharedVolumeKey)}" +
            "  a4=${RealmKeyMaterialBridge.fingerprint(material.deviceSigningBlob)}"
    }

    // ---- Device pairing --------------------------------------------------------------------
    //
    // The "Add a device" wizard owns both pairing roles (Scan = joiner, Show = host) behind one
    // `PairingWizardViewModel`, with the host session delegated to the process-lifetime
    // `HostPairingMonitor`. As with genesis, the SwiftUI side observes plain `String` step/mode keys +
    // `String?` error copy mapped here, never touching the sealed `PairingStep` / `AddDeviceMode` types
    // across the SKIE bridge. Drivable over fakes; [installRealRuntime] flips the installed
    // repo/monitor to the real proxy with no SwiftUI change.

    /** The unified "Add a device" pairing wizard view model (observe `uiState`). */
    fun pairingWizardViewModel(): PairingWizardViewModel =
        PairingWizardViewModel(installedSyncRepository, hostPairingMonitor())

    /** Stable key for the pairing step, so SwiftUI can `switch` without touching the sealed type. */
    fun pairingStepKey(state: PairingWizardUiState): String = when (state.step) {
        PairingStep.PickMethod -> "pick_method"
        PairingStep.ScanQr -> "scan_qr"
        PairingStep.ManualCode -> "manual_code"
        PairingStep.NameDevice -> "name_device"
        PairingStep.Approval -> "approval"
        PairingStep.Success -> "success"
        PairingStep.ResponderApproval -> "responder_approval"
        PairingStep.ResponderSuccess -> "responder_success"
        PairingStep.Done -> "done"
    }

    /** Stable key for the active Scan/Show tab. */
    fun deviceModeKey(state: PairingWizardUiState): String = when (state.mode) {
        AddDeviceMode.Scan -> "scan"
        AddDeviceMode.Show -> "show"
    }

    /**
     * The pairing error copy.
     *
     * ⚠⚠ Renders a typed failure, never a raw `String?` handed straight to SwiftUI — that is how a
     * proxy's own sentence reaches an iPhone verbatim (`core-error-copy-catalogue.md` §1, §5.5).
     * The copy is owned here and kept in step with `:control-shared`'s `pairing_error_*` strings.
     */
    fun pairingErrorMessage(state: PairingWizardUiState): String? = pairingErrorCopy(state.error)

    /**
     * The **host-approval** failure as copy. ⚠ Sibling of [pairingErrorMessage]:
     * `hostApprovalError` is a typed `PairingError` too, and `PairingView` needs a `String?`. Same
     * catalogue, same copy, no second mapping.
     */
    fun hostApprovalErrorMessage(state: PairingWizardUiState): String? =
        pairingErrorCopy(state.hostApprovalError)

    private fun pairingErrorCopy(error: PairingError?): String? = when (error) {
        null -> null
        PairingError.AdmitterUnrecognised ->
            "This device is no longer recognised by the relay, so it can't add another device. " +
                "Pair this device again, then retry."
        PairingError.NoAuthority -> "This device can't add other devices yet. Finish setting it up first."
        is PairingError.Unexpected -> ErrorCopySurface.labelForKey(error.copyKey)
    }

    // ---- Host-side pairing approval, app-global ---------------------------------------------
    //
    // The pairing wizard renders the approval in its Show tab (that is where the user is when they
    // hand someone a QR), but the host session is process-lifetime: `HostPairingMonitor` keeps
    // polling after the sheet is dismissed, and the request the joiner posts a minute later has to
    // land somewhere. On Compose that somewhere is the App-root `HostPairingApprovalDialog`; this
    // bound is its iOS twin, for the root scene to present over whatever is on screen.
    //
    // ⚠ Both surfaces share ONE monitor, so approving from either resolves the same request — but
    // they must not be on screen together (SwiftUI would stack an alert over the in-tab prompt).
    // `HostPairingApprovalHost` suppresses itself while the pairing surface is visible.

    /** The approval feed + its two resolutions. Approve/Deny delegate to the shared monitor, so a
     *  request answered here also clears the wizard's in-tab prompt. */
    class HostPairingApprovalBound internal constructor(
        private val monitor: HostPairingMonitor,
        val snapshots: StateFlow<ControlHostPairingApproval?>,
    ) {
        fun approve() = monitor.approveActiveRequest()

        /** ⚠ Ends the whole host session, exactly as the Compose modal's Deny does — the ephemeral
         *  key the SAS came from is burned with it. */
        fun deny() = monitor.denyActiveRequest()
    }

    fun hostPairingApprovalBound(scope: CoroutineScope): HostPairingApprovalBound {
        val monitor = hostPairingMonitor()
        val snapshots = combine(monitor.activeRequest, monitor.approvalError) { request, error ->
            request?.let {
                ControlHostPairingApproval(
                    deviceName = it.deviceName.get(),
                    sas = it.sas?.get()?.takeIf(String::isNotEmpty),
                    error = pairingErrorCopy(error),
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)
        return HostPairingApprovalBound(monitor, snapshots)
    }

    fun hostPairingApprovalBound(): HostPairingApprovalBound =
        hostPairingApprovalBound(CoroutineScope(Dispatchers.Main))

    // ---- EPC app pairing (the §11.1 third flow: an app — e.g. Drive — connects to Control) ----
    // The Control-side counterpart to drive-ios's EPC pairing client; it invokes the proxy's
    // EpcPairingHost.run() on iOS. Bridged through a small iOS controller (not a Compose
    // VM) because the canonical EpcPairingViewModel + the EpcPairingHost binding live in non-iOS /
    // above-layer modules — see [IosEpcPairingController].

    /**
     * The EPC app-pairing host controller (observe `uiState`). Over the real runtime it serves
     * a live relay rendezvous session; over fakes (DEBUG screenshot routes, no real host installed) a
     * [sampleEpcPairingHost] surfaces a static invite so the screen still renders its waiting state.
     */
    fun epcPairingController(): IosEpcPairingController =
        IosEpcPairingController(installedEpcPairingHost ?: sampleEpcPairingHost())

    /** A relay-free host for DEBUG screenshots: surfaces a sample invite, then idles (never SAS). */
    private fun sampleEpcPairingHost(): EpcPairingHost = EpcPairingHost { onInvite, _ ->
        onInvite("tenshu://epc/pair#sample")
        awaitCancellation()
    }

    /**
     * A valid, decodable sample `tenshu://join` code that `PairingWizardViewModel.onQrScanned` parses
     * (canonical role-host form, generated by the shared `PairingUri` helper) to advance the joiner
     * to NameDevice → Approval → Success with no real host.
     *
     * **DEBUG-screenshot-only.** The production wizard rides the real `TenshuSyncRepository` (a
     * real host code is scanned/typed), so the joiner's manual-code field is empty by default; this
     * sample is only used by the `-pairingStep` screenshot deep-links, which run over the fakes
     * (`ControlIosApp.init` skips `installRealRuntime()` for those routes).
     */
    fun fakeJoinerPairingCode(): String = PairingUri.joinUri(
        ownerId = "ios-control-owner",
        hostDeviceId = "ios-control-device",
        hostPublicKey = ByteArray(32),
        pairingSecret = "ios-fake-secret".encodeToByteArray(),
    )

    // ---- Sync dashboard / devices ----------------------------------------------------------
    //
    // A Devices list + sync-enabled status sourced from `SyncDashboardViewModel`, which on
    // `refresh()` issues real `refreshDevices()` / `getSyncStatus()` ops through the in-process
    // proxy. The device composites (`org.tenshu.identity.Device`) bridge poorly to Swift, so a flat
    // [SyncDeviceRow] is mapped here — the same String-bridge discipline as `genesisStepKey`.

    /** A flattened device row for the SwiftUI list (the `Device` composite, read Kotlin-side). */
    data class SyncDeviceRow(
        val id: String,
        val name: String,
        val isCurrent: Boolean,
        val isAuthorized: Boolean,
        /**
         * Ready-to-render "last seen" line, formatted **Kotlin-side** with the same
         * `formatLastSeen` the Compose roster uses. Deliberately not a raw timestamp for Swift to
         * format: the two rosters exist to be compared against each other, and the comparison is
         * only meaningful if both platforms bucket quietness identically.
         */
        val lastSeen: String,
    )

    /** The Sync dashboard view model — observe `uiState`, call `refresh()` / `revokeDevice(id)`. */
    fun syncDashboardViewModel(): SyncDashboardViewModel =
        SyncDashboardViewModel(installedSyncRepository, hostPairingMonitor())

    /** Flatten the dashboard's authorized `Device` composites into Swift-friendly rows. */
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun syncDevices(state: SyncDashboardUiState): List<SyncDeviceRow> {
        // Sampled once per flatten, not per row, so every row in one render is measured against
        // the same instant.
        val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
        return state.devices.map { device ->
            val id = device.deviceId.get()
            val isCurrent = id == state.currentDeviceId
            SyncDeviceRow(
                id = id,
                // The user's label joined to the self-published hardware model, by the same
                // `deviceDisplayLabel` the Compose roster uses — Kotlin-side, like `lastSeen`.
                name = deviceDisplayLabel(device.name.get(), state.deviceHardwareNames[id]),
                isCurrent = isCurrent,
                isAuthorized = device.isAuthorized.get(),
                // Empty for this device — "last seen" about the phone in your hand is noise, and
                // the row already carries a "This device" marker.
                lastSeen = if (isCurrent) "" else formatLastSeen(state.deviceLastSeen[id], nowMs),
            )
        }
    }

    // ---- Provisioning bridge ---------------------------------------------------------------
    //
    // After genesis (or recovery) completes, and whenever this device reaches an unlocked realm,
    // Control publishes the route-1 key material into the shared Keychain
    // ([publishRealmKeysToSharedKeychain], via [ensureDriveProvisioned]); the route-1 apps' gates
    // route on it alone.
    //
    // ⚠ No `epc-session` **presence token** is minted (`ios-first-party-route-1-migration.md` §8).
    // The `epc-session` item has one writer — [publishEpcInvite], Control's "Connect an app" host —
    // and is deleted by [clearDriveSession], which also sweeps a presence token an older build left
    // behind.

    private val driveSessionProvisioner = ControlSessionProvisioner()

    /**
     * Remove the handoff items — the route-1 apps fall back to their connect screen (e.g. on
     * sign-out / reset): the `epc-session` item (a published invite, or a leftover presence token
     * from an older build) and the route-1 key material.
     *
     * Also clears (ios-drive-parity.md) the route-1 key material ([RealmKeyMaterialBridge]) —
     * the threat-model A4 revocation duty. Drive's shipping gate detects the vanished material on
     * its next launch/foreground probe and purges its own at-rest custody copies
     * (`DriveLocalRuntime.deprovision`), completing the teardown symmetry. ⚠ Deliberately NOT
     * called on "Lock now": the durable shared-Keychain custody is the point of route 1 (Drive
     * keeps syncing while Control is locked); only identity-destroying events clear it.
     */
    fun clearDriveSession() {
        driveSessionProvisioner.clear()
        RealmKeyMaterialBridge.clear()
    }

    /**
     * Purge the outbound queues and describe what happened, distinguishing the three ways it can
     * fail to run.
     *
     * ⚠ A catch-all failure string on a destructive path is worse than no string: it reads as a
     * known benign state, and hides that the real cause may be ordering rather than installation.
     */
    private fun queuePurgeNote(): String {
        val koin = realRuntimeKoin ?: return "queues skipped (no runtime installed)"
        val ctx = executionContextOrNull(koin) ?: return "⚠ queues NOT purged (no execution context)"
        return runCatching { "${ctx.purgeOutboundQueues()} queued item(s)" }
            .getOrElse { "⚠ queues NOT purged (${it.message})" }
    }

    /**
     * The proxy's [ExecutionContext] for this app.
     *
     * ⚠⚠ **`koin.get<ExecutionContext>()` does not work** — `ExecutionContext` is not a Koin
     * singleton anywhere; it is minted per client by [ClientRegistry.getExecutionContext]. The
     * bare `get()` throws `NoDefinitionFoundException`, which on the wipe path is an *uncaught*
     * exception in a coroutine — i.e. SIGABRT, killing "Start fresh" mid-wipe. This mirrors how
     * `ProxyModule`'s own `AsyncTaskExecutor` resolves it.
     */
    private fun executionContextOrNull(koin: Koin): ExecutionContext? = runCatching {
        koin.get<ClientRegistry>().getExecutionContext(
            koin.get<String>(org.koin.core.qualifier.named("platformPackageName")),
        )
    }.getOrNull()

    /**
     * Does this device already hold a realm identity at rest? (The reinstall gate's detector,
     * `ios-identity-reinstall-gate.md` §9.)
     *
     * ⚠⚠ **Deliberately NOT [landingStateKey].** That helper answers `"first_time"` both for a
     * genuinely new device *and* for one whose runtime has not loaded yet, and the launch probe
     * calls it before the realm is up — so a reinstall reads as first-run on the first tick and
     * corrects to `"locked"`/`"active"` on the next. Phase routing tolerates that because a later
     * probe overwrites it; the gate's sentinel does **not**, because it is a one-way latch. Keyed
     * on the transient value, it would silently suppress the gate forever.
     *
     * This reads the custody bucket directly through the same standalone [IosSecureStorageDelegate]
     * [wipeDeviceState] builds, so it needs no runtime and cannot be transiently wrong.
     */
    fun hasResidentIdentity(): Boolean {
        val crypto = koinApplication {
            modules(org.tenshu.core.common.security.cryptoBackendsModule())
        }.koin
        val delegate = IosSecureStorageDelegate(
            aead = crypto.get(),
            serviceName = "app.tenshu.control.ios.secure-storage",
        )
        // OWNER_ID is the realm-membership marker: DEVICE_ID alone can exist on a device that never
        // reached a realm, and the gate is about resuming a REALM, not about having booted before.
        return runCatching {
            !delegate.getSecret(SyncManagerImpl.Keys.OWNER_ID).isNullOrBlank()
        }.getOrDefault(false)
    }

    /**
     * What can this device still tell the user about their recovery phrase, **before** the
     * runtime loads and before unlock? One of `"available"`, `"behind_lock"`, `"none"`.
     *
     * ## ⚠⚠ Why the phrase is usually NOT showable at the reinstall gate
     *
     * `getRecoveryPhrase()` reads a disk slot that `setUpLocalUnlock` / `setUpBiometricUnlock`
     * **destructively delete** once the phrase is wrapped under a credential, falling back to an
     * in-memory field that is empty on a fresh process — and a reinstall *is* a fresh process. The
     * gate also renders at `Phase.reinstallGate`, *ahead of* `.locked`.
     *
     * So the phrase is readable exactly when the device has **no** unlock wrapper — which is also
     * the case where the app would never have come up locked, i.e. where the user was not stuck. On
     * the device that is actually trapped it sits behind the lock. The reinstall gate's phrase
     * reveal therefore cannot simply mirror desktop's `ForgetDeviceDialog`; it has to offer the
     * unlock.
     *
     * ⚠ `"behind_lock"` means *may be*, never *is*: `setUpLocalUnlock` wraps **the recovery phrase
     * on a master device and the derived-keys tuple on a joiner**, and both leave the same
     * `UNLOCK_WRAPPER` key. Nothing distinguishes the two roles pre-unlock, so the copy must not
     * promise a phrase — only [revealPhraseAfterUnlock] can answer, and it returns null for the
     * joiner that never held one.
     */
    fun residentPhraseState(): String {
        val delegate = standaloneSecureStorage()
        return runCatching {
            when {
                !delegate.getSecret(SyncManagerImpl.Keys.RECOVERY_PHRASE).isNullOrBlank() -> "available"
                !delegate.getSecret(SyncManagerImpl.Keys.UNLOCK_WRAPPER).isNullOrBlank() ||
                    !delegate.getSecret(SyncManagerImpl.Keys.BIOMETRIC_WRAPPER).isNullOrBlank() -> "behind_lock"
                else -> "none"
            }
        }.getOrDefault("none")
    }

    /**
     * The phrase from the plaintext slot — non-null only while [residentPhraseState] is
     * `"available"`. Reads the same standalone bucket as [hasResidentIdentity], so it needs no
     * runtime.
     */
    fun residentRecoveryPhrase(): String? = runCatching {
        standaloneSecureStorage().getSecret(SyncManagerImpl.Keys.RECOVERY_PHRASE)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * The phrase once the realm is unlocked — the `"behind_lock"` answer. Goes through the installed
     * [SyncRepository] rather than the standalone bucket, because after unlock the phrase lives in
     * the session field rather than on disk.
     *
     * ⚠ `null` is a real answer, not only a failure: a **joiner** unlocks successfully and still has
     * no phrase, because it never held A1. The caller must say "this device never held your recovery
     * phrase" rather than "could not read it".
     */
    suspend fun revealPhraseAfterUnlock(): String? = recoveryPhrase()

    /**
     * The realm's recovery phrase (A9), or `null` when this device holds none — a **joiner** never
     * does, and that is an answer rather than a failure.
     *
     * ## ⚠⚠ The only route to the phrase on iOS
     *
     * Genesis mints the BIP-39 phrase silently, and the shared recovery *banner* is not bound on
     * iOS, so the Settings recovery row calling this is the one place an iOS-only user can see the
     * words their realm depends on. Without it the phrase is minted, sealed and unreachable — and a
     * device holding the only copy, never shown to its owner, loses A1 on a discard
     * (`admission-revocation-names-the-capability.md` §5b.12).
     */
    suspend fun recoveryPhrase(): String? = runCatching {
        installedSyncRepository.getRecoveryPhrase().getOrNull()?.get()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Whether this realm is **sealed** — i.e. the user opted into the advanced passphrase, so A1 sits
     * behind an `UNLOCK_WRAPPER` rather than being headless.
     *
     * ## ⚠⚠ Why Settings must ask this before offering "Unlock with Face ID"
     *
     * `control-keep-device-unlocked.md` decides that **iOS gets no distinct hard mode**: auth is
     * the transparent device-owner primitive (Face ID / Touch ID / passcode with automatic
     * fallback) that the soft "Require unlock" already provides, and a Tenshu passphrase on iOS is
     * "foreign + clunky". So the default iOS realm is **headless** — the OS protects the keys at
     * rest and nothing is sealed behind a Tenshu credential.
     *
     * Realm biometric unlock only means something on a *sealed* realm: it adds a biometric wrapper
     * beside the passphrase one. `SyncManagerImpl.setUpBiometricUnlock` enforces that directly —
     * `if (storageDelegate.getSecret(Keys.UNLOCK_WRAPPER) == null) return false`, the passphrase-floor
     * backstop that stops a lost biometric becoming a lockout.
     *
     * ⚠ So on a default iOS device the toggle can only ever fail; gating it solely on whether the
     * *hardware* can prompt leaks an Android/desktop control onto the platform that decided against
     * the model. Ask this instead.
     *
     * ⚠ Deliberately not derived from [residentPhraseState] even though `"behind_lock"` implies the
     * same thing: that helper answers "can I show the phrase", this one answers "is A1 sealed",
     * and collapsing them would make a future change to either silently move the other.
     */
    fun realmIsSealed(): Boolean {
        val delegate = standaloneSecureStorage()
        return runCatching {
            !delegate.getSecret(SyncManagerImpl.Keys.UNLOCK_WRAPPER).isNullOrBlank()
        }.getOrDefault(false)
    }

    /** The custody bucket [hasResidentIdentity], [wipeDeviceState] and [residentPhraseState] all read. */
    private fun standaloneSecureStorage(): IosSecureStorageDelegate {
        val crypto = koinApplication {
            modules(org.tenshu.core.common.security.cryptoBackendsModule())
        }.koin
        return IosSecureStorageDelegate(
            aead = crypto.get(),
            serviceName = "app.tenshu.control.ios.secure-storage",
        )
    }

    /**
     * Destroy this device's Tenshu identity at rest so the next launch is a genuinely fresh
     * device. Two callers: the DEBUG harness (`-wipeDeviceState`) AND the production "Forget this
     * device" affordance on the returning-user `LockedView` (the escape hatch for a sealed device
     * whose passphrase is lost). Needed on hardware because the Keychain survives app uninstall —
     * a stale `REALM_RELAY_REGISTERED` latch / `lastSyncTimestamp` makes
     * `RealmRegistrar.ensureRegistered` skip the deferred relay genesis forever, so a
     * reinstalled Control 401s against a wiped relay with no way back (the
     * ios-control-handover.md §C4 "stale device key blocks re-registration" landmine; the sim
     * answer is `simctl erase`, which a real phone doesn't have).
     *
     * Purges every `SyncManagerImpl.Keys` slot from Control's custody bucket (device id + A4
     * signing key, unlock/biometric wrappers, recovery phrase, owner id, relay latches) plus the
     * cross-app handoff items ([clearDriveSession]: `epc-session` item + route-1 key bridge)
     * **and the outbound queues** ([purgeOutboundQueues], `ios-identity-reinstall-gate.md` §4).
     *
     * ⚠⚠ The wipe never touches the *store*, but it does drop the set of payloads staged to
     * **send**: nothing authored by an identity you are discarding can be right when it finally
     * lands — a queued `Device` tombstone left behind lands later on a device that has since
     * re-paired.
     *
     * ⚠ The purge needs a runtime, because the queues live in the object store. When none is
     * installed — the DEBUG `-wipeDeviceState` route calls this *instead* of installing one — it is
     * skipped and the return string says so, rather than failing the wipe.
     * Uses a standalone delegate on the same Keychain service (the
     * `DriveLocalRuntime.deprovision` recipe), so it works before any runtime is installed —
     * call it INSTEAD of installing the real runtime, then relaunch. It is likewise safe to call
     * with the real runtime already installed (the "Forget this device" case): the standalone
     * delegate purges the same on-disk custody bucket the runtime holds, and `onForget` then
     * routes back to onboarding.
     */
    suspend fun wipeDeviceState(): String {
        // Revoke the route-1 apps' custody FIRST (threat model A4), before anything that can fail,
        // suspend or be killed. They read the identity only through [RealmKeyMaterialBridge];
        // Control itself never does, so nothing below needs it. Cleared last, a sibling that
        // rebuilt or foregrounded during the retirement wait — or after a wipe killed partway —
        // re-adopted the identity being retired. Cleared first, an interrupted wipe errs the safe
        // way: siblings deprovision, and a Control that still holds the identity republishes the
        // next time it reaches an unlocked realm ([ensureDriveProvisioned]).
        clearDriveSession()

        // Leave the realm before destroying the means to say so (see [retireThisDeviceRow]).
        //
        // ⚠⚠ `runCatching`, because a failure here must never abort the wipe: a retirement that
        // revoked the row and then threw would leave the device revoked by the relay but still
        // holding its identity, which is the precise half-state this whole path exists to prevent.
        // The user asked to discard; a broken prelude is not a reason to refuse.
        val retirement = runCatching { retireThisDeviceRow() }.getOrElse {
            "⚠ this device is still listed in your realm — remove it from another device"
        }
        val crypto = koinApplication {
            modules(org.tenshu.core.common.security.cryptoBackendsModule())
        }.koin
        val delegate = IosSecureStorageDelegate(
            aead = crypto.get(),
            serviceName = "app.tenshu.control.ios.secure-storage",
        )
        // `ios-identity-reinstall-gate.md` §4: drop what this device staged to send, BETWEEN the
        // retirement and the key purge.
        //
        // ⚠⚠ The ordering is pinned on both sides and neither is arbitrary. It must come **after**
        // `retireThisDeviceRow`, which waits on its own tombstone sitting in `PENDING_TOMBSTONES` —
        // purging first deletes the retirement the wipe just waited for. And it must come **before**
        // the custody keys go: minting an `ExecutionContext` is opening a session, and after the
        // identity is destroyed that silently yields nothing, so the purge would never run.
        val queueNote = queuePurgeNote()

        // ⚠⚠ The SAME list desktop purges, not a copy of it — see
        // [SyncManagerImpl.Keys.CUSTODY_SLOTS]: two copies drift, whatever their comments promise.
        val keys = SyncManagerImpl.Keys.CUSTODY_SLOTS
        // ⚠⚠ D3 (`ios-identity-reinstall-gate.md`), B.14 (191), §13's rule: this device's local
        // copy of realm data — the shared store, every app's — is wiped before custody goes, and an
        // unfinished wipe KEEPS custody (custody-gone-store-present is the half-state D3 closed: a
        // fresh device id with no realm,
        // `401` on every push, an app still looking signed in to the realm it just left).
        // The user is told "not finished" and repeats the forget, which completes over the finished
        // or still-marked store.
        val replicaWipe = sharedReplica.wipeThenPurgeCustody {
            keys.forEach { key -> runCatching { delegate.remove(key) } }
            // Also clear the dev-seed mock publisher account (same secure-storage service). Otherwise a
            // stale mock Free account survives the wipe, reloads via MockPublisherClient.loadAccount() on
            // the next launch, and the entitlement writer re-mints a `tenshu.dev` record that SHADOWS the
            // realm owner's real synced entitlement — so a re-joined device presents a dev-signed proof
            // the relay rejects `entitlement_invalid` (docs/proposals/ios-joiner-entitlement-proof-rejection.md).
            runCatching { delegate.remove(MockPublisherClient.STORAGE_KEY) }
        }
        if (!replicaWipe.custodyMayGo) {
            println("[wipe] ⚠⚠ NOT FINISHED, custody kept: ${replicaWipe.note} ($queueNote)")
            return "⚠ not finished — ${replicaWipe.note}; try again — $retirement — $RELAUNCH_CONTROL"
        }
        println(
            "[wipe] device state purged (${keys.size} custody slots + mock account + drive handoff" +
                " + $queueNote + ${replicaWipe.note})",
        )
        // B.16 (217): Control's graph is rebuilt after the forget; asked to
        // relaunch only when that rebuild left no serving graph.
        return if (replicaWipe.relaunchControl) "wiped — $retirement — $RELAUNCH_CONTROL" else "wiped — $retirement"
    }

    /** B.14 (193): a switched forget whose rebuild of Control's graph did not serve (B.16 (217)). */
    private const val RELAUNCH_CONTROL = "quit and reopen Tenshu Control before setting it up again"

    /**
     * Tombstone **this device's own** `Device` row before [wipeDeviceState] destroys the identity
     * that row describes, and wait for the tombstone to actually leave.
     *
     * **Why.** A wipe purges the Keychain custody bucket but deliberately never touches the object
     * store, so it produces the one state that mints a ghost: identity gone, roster row alive. The
     * row then outlives its identity in every replica that holds it, indistinguishable by name from
     * a live device, and nothing reaps it (`tech-debt.md`, *"a `Device` row that outlives the
     * identity it describes"*).
     *
     * **No new mechanism.** `RevokeDevice` removes the row from the `DEVICES` relation,
     * deletes the entity, and emits a `DeleteBlock` carrying `revokedDeviceId` — which is what makes
     * the relay revoke this device's `KnownDevicesStore` registration. It has no self-guard; the
     * "not this device" rule is a UI gate, not an op rule, precisely so a device can retire itself
     * while it still holds the keys to prove it.
     *
     * ⚠⚠ **The wait is the load-bearing part.** `TombstoneEmitter.emit` only appends to the durable
     * `PENDING_TOMBSTONES` array and wakes the scheduler; the push that drains it is signed with the
     * A4 key this wipe is about to delete. Emitting without waiting would leave the tombstone in a
     * queue that can never again be pushed — the ghost created by the very step meant to prevent it.
     *
     * ⚠ A timeout does **not** block the wipe. People wipe precisely when things are broken, and a
     * device that cannot reach the relay must still be able to forget itself. The orphan then
     * becomes an *announced* outcome with a named remedy rather than a silent one.
     */
    private suspend fun retireThisDeviceRow(): String {
        val koin = realRuntimeKoin ?: return "no realm to leave"
        val syncManager = koin.get<SyncManager>()
        val deviceId = syncManager.currentRealmContext()?.deviceId
            ?: return "no realm to leave"

        val client = koin.get<TenshuClient>()
        val revoked = runCatching { client.syncService.revokeDevice(deviceId) }.getOrNull()
        if (revoked == null || revoked is TResult.Failure) {
            return "⚠ this device is still listed in your realm — remove it from another device"
        }

        val executionContext = executionContextOrNull(koin)
            ?: return "⚠ this device is still listed in your realm — remove it from another device"
        val deadline = nowMs() + RETIREMENT_PUSH_TIMEOUT_MS
        while (nowMs() < deadline) {
            if (executionContext.pendingTombstoneCount() == 0) return "removed from your realm"
            kotlinx.coroutines.delay(RETIREMENT_POLL_MS)
        }
        return "⚠ this device is still listed in your realm — remove it from another device"
    }

    private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    /**
     * How long the wipe waits for its own tombstone to be pushed. Long enough for a wake + one
     * relay round trip on a working connection; short enough that a device with no connectivity
     * still forgets itself promptly, which is the case a user is most likely to be in.
     */
    private const val RETIREMENT_PUSH_TIMEOUT_MS = 8_000L
    private const val RETIREMENT_POLL_MS = 150L

    /**
     * Ensure the co-resident route-1 provisioning **exists**, publishing it if it does not — called
     * whenever this device reaches an unlocked realm, which is the only moment the key material is
     * derivable ([publishRealmKeysToSharedKeychain] needs `currentRealmContext()`).
     *
     * ⚠ Creates, not only refreshes: the bridge items are cleared by every identity-destroying
     * event ([clearDriveSession] / [wipeDeviceState]), and a refresh-only rule would re-create them
     * *only* at genesis/recovery — an already-onboarded Control that lost them would have no path
     * back, with Drive on its connect screen and Fit showing "Can't reach Tenshu Control"
     * permanently. (The "Connect an app" EPC host in Settings pairs an app over EPC; it never
     * republishes the route-1 material.)
     *
     * Creating here is not a custody widening: on iOS route-1 first-party apps are realm co-owners
     * by decision (`ios-first-party-route-1-migration.md`, Decision C), the material is the same
     * blob the onboarding handoff writes, and the trigger — *this* device's realm being unlocked —
     * is strictly narrower than "any app on the device". The provisioning is torn down by a
     * sign-out / wipe, and a Control with no realm never reaches this call.
     *
     * Also the onboarding handoff: genesis and recovery call it the moment they complete.
     *
     * Returns true when the material is published (i.e. Drive/Fit can go route-1 from here).
     */
    fun ensureDriveProvisioned(): Boolean =
        runCatching { publishRealmKeysToSharedKeychain() }
            .getOrElse { "publish threw: ${it.message}" }
            .startsWith("published")

    /**
     * Publish a **live EPC pairing invite** (`tenshu://epc/pair#…`, from the hosting
     * [epcPairingController]) into the shared `epc-session` Keychain item. SHIPPING caller:
     * Control's "Connect an app" host publishes on invite arrival (`EpcAppPairingView`).
     *
     * ⚠ No shipping app reads it: route 1 covers the co-resident case, so nothing consumes it
     * without a SAS compare. The only reader is drive-ios's DEV_HARNESS
     * `-epcAutoConnectFromControl`. Returns true on a write.
     */
    fun publishEpcInvite(uri: String): Boolean = driveSessionProvisioner.provision(uri)
}

/**
 * The OS alerts' collector over one graph's request feed (B.16 (228)): [start] replaces any
 * running one, [stop] cancels it. Each run has a scope of its own, so a stopped graph's collector
 * is gone and the next one follows the new repository's feed.
 */
internal class RequestAlerts(make: () -> PendingRequestAlerts) {
    /** ⚠ One diff for the process: a rebuilt graph's feed is not re-alerted for what was already shown. */
    private val alerts: PendingRequestAlerts by lazy(make)

    @kotlin.concurrent.Volatile
    private var scope: CoroutineScope? = null

    /** Test seam: the feed the running collector follows. */
    internal var following: kotlinx.coroutines.flow.StateFlow<List<app.tenshu.control.notifications.PendingRequest>>? = null
        private set

    fun start(requests: kotlinx.coroutines.flow.StateFlow<List<app.tenshu.control.notifications.PendingRequest>>) {
        stop()
        val made = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        scope = made
        following = requests
        alerts.launchIn(made, requests)
    }

    fun stop() {
        scope?.cancel()
        scope = null
        following = null
    }

    /** Test seam: whether a collector runs. */
    internal val isRunning: Boolean get() = scope?.isActive == true
}
