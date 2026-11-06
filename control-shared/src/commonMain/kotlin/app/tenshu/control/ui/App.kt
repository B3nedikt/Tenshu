package app.tenshu.control.ui

import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.getOrNull
import kotlinx.coroutines.launch
import app.tenshu.control.ui.screens.settings.ForgetDeviceDialog
import app.tenshu.control.data.ReauthPromptCopy
import app.tenshu.control.data.Reauthenticator
import app.tenshu.control.data.SyncRepository
import app.tenshu.control.data.confirm
import app.tenshu.control.data.rememberBiometricPromptHost
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.reauth_forget_device_subtitle
import app.tenshu.control.shared.resources.reauth_forget_device_system_reason
import app.tenshu.control.shared.resources.reauth_title
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.tenshu.control.data.LandingState
import app.tenshu.control.data.LandingStateRepository
import app.tenshu.control.data.PublisherClient
import app.tenshu.control.epc.EpcPairingViewModel
import app.tenshu.control.ui.navigation.Destination
import app.tenshu.control.ui.screens.apps.AppDetailScreen
import app.tenshu.control.ui.screens.apps.AppDetailViewModel
import app.tenshu.control.ui.screens.apps.AppsScreen
import app.tenshu.control.ui.screens.apps.AppsViewModel
import app.tenshu.control.ui.screens.epc.EpcPairingScreen
import app.tenshu.control.ui.screens.data.DataScreen
import app.tenshu.control.ui.screens.data.DataViewModel
import app.tenshu.control.ui.screens.home.HomeScreen
import app.tenshu.control.ui.screens.home.HomeScreenViewModel
import app.tenshu.control.ui.screens.notifications.NotificationCenterScreen
import app.tenshu.control.ui.screens.notifications.NotificationsBell
import app.tenshu.control.ui.screens.notifications.NotificationsViewModel
import app.tenshu.control.ui.screens.notifications.PinnedRequests
import app.tenshu.control.ui.screens.notifications.asActions
import app.tenshu.control.ui.screens.home.QuotaSoftLimitBanner
import app.tenshu.control.ui.screens.home.OfflineMapsSuggestion
import app.tenshu.control.ui.screens.home.OfflineMapsSuggestionViewModel
import app.tenshu.control.ui.screens.home.QuotaSoftLimitViewModel
import app.tenshu.control.ui.screens.home.SchemaFenceBanner
import app.tenshu.control.ui.screens.home.SchemaFenceBannerViewModel
import app.tenshu.control.ui.screens.home.RecoveryAffordance
import app.tenshu.control.ui.screens.home.RecoveryAffordanceViewModel
import app.tenshu.control.ui.screens.locked.LockedScreen
import app.tenshu.control.ui.screens.services.AccountPickerScreen
import app.tenshu.control.ui.screens.services.CheckoutScreen
import app.tenshu.control.ui.screens.services.CheckoutViewModel
import app.tenshu.control.ui.screens.services.AccountDeletionScreen
import app.tenshu.control.ui.screens.services.AccountDeletionViewModel
import app.tenshu.control.ui.screens.services.DisconnectConfirmScreen
import app.tenshu.control.ui.screens.services.DisconnectConfirmViewModel
import app.tenshu.control.ui.screens.services.FreeAccountSignupScreen
import app.tenshu.control.ui.screens.services.FreeAccountSignupViewModel
import app.tenshu.control.ui.screens.services.ReiKeyScreen
import app.tenshu.control.ui.screens.services.ReiKeyViewModel
import app.tenshu.control.ui.screens.services.ServicesScreen
import app.tenshu.control.ui.screens.services.ServicesViewModel
import app.tenshu.control.ui.screens.settings.SettingsScreen
import app.tenshu.control.ui.screens.settings.advanced.AdvancedSettingsScreen
import app.tenshu.ui.legal.DataAttributions
import app.tenshu.ui.legal.OpenSourceLicencesScreen
import app.tenshu.control.ui.screens.settings.security.SecuritySettingsScreen
import app.tenshu.control.ui.screens.settings.security.SecuritySettingsViewModel
import app.tenshu.control.ui.screens.shares.SharesScreen
import app.tenshu.control.ui.screens.shares.SharesViewModel
import app.tenshu.control.ui.screens.settings.backgroundsync.BackgroundSyncSettingsScreen
import app.tenshu.control.ui.screens.settings.backgroundsync.BackgroundSyncSettingsViewModel
import app.tenshu.control.ui.screens.settings.ingest.BackgroundIngestScreen
import app.tenshu.control.ui.screens.settings.ingest.BackgroundIngestViewModel
import app.tenshu.control.ui.screens.settings.errors.ErrorsScreen
import app.tenshu.control.ui.screens.settings.errors.ErrorsViewModel
import app.tenshu.control.ui.screens.models.ModelFeatureScreen
import org.tenshu.inference.InferenceCapability
import app.tenshu.control.ui.screens.models.ModelsScreen
import app.tenshu.control.ui.screens.models.ModelsViewModel
import app.tenshu.control.ui.screens.offlinemaps.OfflineMapsScreen
import app.tenshu.control.ui.screens.offlinemaps.OfflineMapsViewModel
import app.tenshu.control.ui.screens.settings.unlock.UnlockSettingsScreen
import app.tenshu.control.ui.screens.settings.unlock.UnlockSettingsViewModel
import app.tenshu.control.ui.screens.newuser.NewUserScreen
import app.tenshu.control.sync.HostPairingMonitor
import app.tenshu.control.ui.screens.recovery.RecoveryScreen
import app.tenshu.control.ui.screens.sync.PairingWizardScreen
import app.tenshu.control.ui.sync.HostPairingApprovalDialog
import app.tenshu.control.ui.screens.sync.RecoveryPhraseScreen
import app.tenshu.control.ui.screens.sync.SyncDashboardScreen
import app.tenshu.control.ui.screens.unlock.SetupUnlockScreen
import app.tenshu.control.ui.screens.welcome.WelcomeScreen
import app.tenshu.ui.layout.AdaptiveNavItem
import app.tenshu.control.ui.lock.AppLockGate
import app.tenshu.ui.layout.AdaptiveNavScaffold
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * @param deepLink optional target to open directly — e.g. Drive's "set up
 *   storage" CTA deep-links to [Destination.Services]
 *   (`docs/proposals/storage-capacity-gating.md` §4). Applied only
 *   when the app resolves to its Active landing (Home); a Locked / FirstTime
 *   state takes precedence so a deep-link can't bypass unlock or onboarding.
 *   `null` on desktop and on a normal launch.
 */
@Composable
fun App(deepLink: Destination? = null) {
    val landingStateRepository: LandingStateRepository = koinInject()
    var initialDestination by remember { mutableStateOf<Destination?>(null) }

    LaunchedEffect(Unit) {
        val landing = when (landingStateRepository.detect()) {
            LandingState.Active -> Destination.Home
            LandingState.Locked -> Destination.Locked
            LandingState.FirstTime -> Destination.Welcome
        }
        initialDestination = resolveStartDestination(landing, deepLink)
    }

    val resolvedInitial = initialDestination
    if (resolvedInitial == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    AppContent(initialDestination = resolvedInitial)
}

/**
 * Start destination = the [deepLink] when one was supplied **and** the app is in
 * its Active state ([Destination.Home]); otherwise the state-resolved [landing].
 * A Locked / FirstTime landing always wins so a deep-link can't skip unlock or
 * onboarding. Pure so it's unit-testable without Compose.
 */
internal fun resolveStartDestination(landing: Destination, deepLink: Destination?): Destination =
    if (deepLink != null && landing == Destination.Home) deepLink else landing

/**
 * Whether the soft app-lock gate (control-keep-device-unlocked.md §4.2) may engage.
 * [showNav] excludes the hard LockedScreen and the top-level onboarding screens, but
 * onboarding extends past those: destinations pushed *from* Welcome (the first-run
 * PairingWizard) keep [Destination.Welcome] on the back stack until onboarding
 * completes (every exit path clears the stack), so its presence marks the whole
 * first-run flow — where there is no realm to shield yet and the gate must stay out
 * of the way. Pure so it's unit-testable without Compose (like
 * [resolveStartDestination]).
 */
internal fun appLockGateActive(showNav: Boolean, backStack: List<Destination>): Boolean =
    showNav && !backStack.contains(Destination.Welcome)

/** Shown when the op returned nothing at all — see [isForgetWarning] for why that is not success. */
internal const val UNKNOWN_FORGET_OUTCOME =
    "⚠ could not confirm what was removed — check your devices from another one"

/**
 * Does a `ForgetThisDevice` outcome need the user's attention on **another** device?
 *
 * ⚠⚠ **The `⚠` marker is the contract**, and setting it is deliberately the producer's job:
 * `ForgetThisDeviceImplementation` prefixes exactly its non-clean outcomes with it (a leftover row,
 * an unconfirmed retirement, a store that would not wipe) and never a clean one. That coupling is
 * pinned from the producing side by `ForgetThisDeviceOutcomeMarkerTest`, so it cannot silently start
 * matching everything or nothing.
 *
 * ⚠ Not a status-text match on a *remote* contract — the trap this project has already hit with
 * relay errors, where an H2 connection empties the reason phrase and the match goes inert in
 * production. Both ends of this one live in this repo and change together.
 */
internal fun isForgetWarning(outcome: String): Boolean = outcome.contains('⚠')

@Composable
private fun AppContent(initialDestination: Destination) {
    // ForgetThisDevice — a confirmation, not a destination, so it lives as screen-level state.
    val syncRepositoryForForget: SyncRepository = koinInject()
    val forgetScope = rememberCoroutineScope()
    // Re-auth before an irreversible action (control-keep-device-unlocked.md §6.1) — see the gate
    // at `onForgetDeviceClick` for why it sits before the dialog rather than on its confirm.
    val reauthenticator: Reauthenticator = koinInject()
    val reauthPromptHost = rememberBiometricPromptHost()
    val forgetReauthCopy = ReauthPromptCopy(
        title = stringResource(Res.string.reauth_title),
        subtitle = stringResource(Res.string.reauth_forget_device_subtitle),
        systemReason = stringResource(Res.string.reauth_forget_device_system_reason),
    )
    var showForgetDialog by remember { mutableStateOf(false) }
    var forgetPhrase by remember { mutableStateOf<String?>(null) }
    var forgetAcknowledged by remember { mutableStateOf(false) }
    // ⚠⚠ Confirm is a ~10s wait, not an instant action — the op's tombstone-push timeout elapses in
    // full whenever the relay cannot confirm the removal, which is the common case. A dialog that
    // closes over a Settings that just sits there reads as a dead button.
    var forgetWorking by remember { mutableStateOf(false) }
    // The op's outcome, carried to the Welcome screen this flow lands on. `ForgetThisDevice` does
    // not fail when the retirement half fails — by design — so this string is the only thing that
    // can say a row was left behind in the realm, and its remedy is always "from another device".
    var forgetNotice by remember { mutableStateOf<String?>(null) }

    val publisherClient: PublisherClient = koinInject()
    // App-global host-side pairing approval (control-pairing-ux.md). The monitor
    // is a process-lifetime single owning the poll; we render its pending request as a
    // modal that overlays any destination, so the user sees the approval wherever they are.
    val pairingMonitor: HostPairingMonitor = koinInject()
    // Satisfy the soft app-lock after an in-session unlock / onboarding so it doesn't
    // prompt a second time on Home (see the Home transitions below).
    val appLockController: app.tenshu.control.data.AppLockController = koinInject()
    val pendingPairingRequest by pairingMonitor.activeRequest.collectAsState()
    val pairingApprovalError by pairingMonitor.approvalError.collectAsState()
    val backStack = remember { mutableStateListOf<Destination>(initialDestination) }
    val currentDestination = backStack.lastOrNull()

    val showNav = currentDestination != null &&
            currentDestination != Destination.RecoveryPhrase &&
            currentDestination != Destination.Welcome &&
            currentDestination != Destination.NewUser &&
            currentDestination != Destination.Recovery &&
            currentDestination != Destination.Locked &&
            currentDestination != Destination.SetupUnlock

    val topLevelDestinations = remember {
        listOf(
            Destination.Home,
            Destination.Apps,
            Destination.Data,
            Destination.Services,
            Destination.Settings,
        )
    }

    val navItems = topLevelDestinations.map { dest ->
        AdaptiveNavItem(
            key = dest,
            icon = dest.icon,
            label = dest.label,
            selected = currentDestination == dest,
            onClick = {
                if (currentDestination != dest) {
                    backStack.clear()
                    backStack.add(dest)
                }
            },
        )
    }

    // Soft app-lock gate (control-keep-device-unlocked.md §4.2) — engaged only for the
    // active app UI, never over the LockedScreen / onboarding (see appLockGateActive).
    AppLockGate(active = appLockGateActive(showNav, backStack)) {
    AdaptiveNavScaffold(
        navItems = navItems,
        showNav = showNav,
    ) {
        KmpNavHost(
            backStack = backStack,
            modifier = Modifier,
            onBack = { backStack.removeLastOrNull() },
            entryProvider = kmpEntryProvider {
                entry<Destination.Home> { _ ->
                    val viewModel = koinViewModel<HomeScreenViewModel>()
                    val affordanceVm = koinViewModel<RecoveryAffordanceViewModel>()
                    val quotaVm = koinViewModel<QuotaSoftLimitViewModel>()
                    val schemaFenceVm = koinViewModel<SchemaFenceBannerViewModel>()
                    val offlineMapsVm = koinViewModel<OfflineMapsSuggestionViewModel>()
                    val notificationsVm = koinViewModel<NotificationsViewModel>()
                    // Unread (not-yet-dismissed) drives the badge + pinned slot;
                    // the full list — incl. dismissed — lives in the center.
                    val unreadRequests by notificationsVm.unread.collectAsState()
                    val pendingActionState by notificationsVm.actionState.collectAsState()
                    HomeScreen(
                        viewModel = viewModel,
                        topBarActions = {
                            NotificationsBell(
                                count = unreadRequests.size,
                                onClick = { backStack.add(Destination.Notifications) },
                            )
                        },
                        pinnedContent = {
                            PinnedRequests(
                                requests = unreadRequests,
                                actionState = pendingActionState,
                                actions = notificationsVm.asActions(),
                            )
                        },
                        affordanceContent = {
                            // First, because it is the one state that silently stops
                            // everything else on this page from changing — a fenced device
                            // pulls nothing until Control is updated.
                            SchemaFenceBanner(viewModel = schemaFenceVm)
                            RecoveryAffordance(
                                viewModel = affordanceVm,
                                onSavePhrase = {
                                    backStack.add(Destination.RecoveryPhrase)
                                },
                                // Only offered when the realm has no storage plan. Signed in
                                // here already → Services (its relay card handles "account, no
                                // storage"); the picker's Free card would offer a fresh sign-up.
                                onSetUpSync = {
                                    backStack.add(
                                        if (publisherClient.currentSessionToken() != null) Destination.Services
                                        else Destination.AccountPicker
                                    )
                                },
                            )
                            // control-subscription-ux.md §2.3: the free-tier 80% quota
                            // soft-limit banner; [See plans] deep-links to Services (the
                            // subscription surface), the storage-CTA target used elsewhere.
                            QuotaSoftLimitBanner(
                                viewModel = quotaVm,
                                onSeePlans = { backStack.add(Destination.Services) },
                            )
                            // maps-region-delivery.md §7: discovery, not a choice — the map
                            // already streams, so this offers offline use rather than asking the user
                            // to pick a mode before they have seen one.
                            OfflineMapsSuggestion(
                                viewModel = offlineMapsVm,
                                onChooseRegion = { backStack.add(Destination.OfflineMaps) },
                            )
                        },
                    )
                }
                entry<Destination.Notifications> { _ ->
                    NotificationCenterScreen(
                        viewModel = koinViewModel<NotificationsViewModel>(),
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<Destination.Apps> { _ ->
                    val viewModel = koinViewModel<AppsViewModel>()
                    AppsScreen(
                        viewModel = viewModel,
                        onAppClick = { appId -> backStack.add(Destination.AppDetail(appId)) },
                        onConnectApp = { backStack.add(Destination.EpcPairing) },
                    )
                }
                entry<Destination.EpcPairing> { _ ->
                    EpcPairingScreen(
                        viewModel = koinViewModel<EpcPairingViewModel>(),
                        onClose = { backStack.removeLastOrNull() },
                    )
                }
                entry<Destination.AppDetail> { dest ->
                    val viewModel = koinViewModel<AppDetailViewModel> { parametersOf(dest.appId) }
                    AppDetailScreen(
                        viewModel = viewModel,
                        onBack = { backStack.removeLastOrNull() },
                        onServiceClick = { backStack.add(Destination.Services) },
                    )
                }
                entry<Destination.Data> { _ ->
                    val viewModel = koinViewModel<DataViewModel>()
                    DataScreen(
                        viewModel = viewModel,
                        onOpenShares = { backStack.add(Destination.Shares) },
                    )
                }
                entry<Destination.Shares> { _ ->
                    val viewModel = koinViewModel<SharesViewModel>()
                    SharesScreen(
                        viewModel = viewModel,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<Destination.Services> { _ ->
                    val viewModel = koinViewModel<ServicesViewModel>()
                    ServicesScreen(
                        viewModel = viewModel,
                        onSignUp = { backStack.add(Destination.AccountPicker) },
                        // Synced-card "Sign in to manage" (D11): re-authenticate the
                        // existing account on this device via email-OTP, then the full
                        // account card (with billing controls) replaces the synced one.
                        onSignIn = { backStack.add(Destination.FreeAccountSignup(signIn = true)) },
                        onGetMoreStorage = { backStack.add(Destination.AccountPicker) },
                        onChangeIdentityMethod = { backStack.add(Destination.AccountPicker) },
                        onDisconnect = { backStack.add(Destination.DisconnectConfirm) },
                        onDeleteAccount = { backStack.add(Destination.AccountDeletion) },
                        // Same gate as the Subscribe CTA: no link to external payment in a store build.
                        showManageSubscription = inAppCheckoutEnabled,
                    )
                }
                entry<Destination.AccountPicker> { _ ->
                    AccountPickerScreen(
                        onPickFree = {
                            backStack.removeLastOrNull()
                            backStack.add(Destination.FreeAccountSignup())
                        },
                        onSubscribe = { productId ->
                            backStack.removeLastOrNull()
                            // ⚠⚠ A plan is bought signed in: Stripe is handed the account's email,
                            // so the purchase lands on the account this device can fetch it from.
                            backStack.add(
                                if (publisherClient.currentSessionToken() != null) {
                                    Destination.Checkout(productId)
                                } else {
                                    Destination.FreeAccountSignup(signIn = true, thenCheckout = productId)
                                },
                            )
                        },
                        onHaveCode = {
                            backStack.removeLastOrNull()
                            backStack.add(Destination.ReiKey)
                        },
                        onBack = { backStack.removeLastOrNull() },
                        // Every build sells (commerce-store-link-out.md D1–D3): a Play build
                        // through Play Billing, desktop and a non-Play APK through Stripe. The
                        // checkout screen picks the rail; nothing here steers to external payment.
                        showPaidTiers = true,
                    )
                }
                entry<Destination.Checkout> { dest ->
                    val viewModel = koinViewModel<CheckoutViewModel> { parametersOf(dest.productId) }
                    CheckoutScreen(
                        viewModel = viewModel,
                        // Pop back to Services; the synced entitlement now drives the storage
                        // figures (and the account card) there.
                        onDone = { backStack.removeLastOrNull() },
                        onCancel = { backStack.removeLastOrNull() },
                    )
                }
                entry<Destination.FreeAccountSignup> { dest ->
                    val viewModel = koinViewModel<FreeAccountSignupViewModel>()
                    FreeAccountSignupScreen(
                        viewModel = viewModel,
                        signIn = dest.signIn,
                        onCancel = { backStack.removeLastOrNull() },
                        onVerified = {
                            // Pop FreeAccountSignup so the user lands back on
                            // the Services tab with their freshly-bound
                            // account visible — or carries on to the purchase
                            // they signed in for.
                            backStack.removeLastOrNull()
                            dest.thenCheckout?.let { backStack.add(Destination.Checkout(it)) }
                        },
                    )
                }
                entry<Destination.ReiKey> { _ ->
                    val viewModel = koinViewModel<ReiKeyViewModel>()
                    ReiKeyScreen(
                        viewModel = viewModel,
                        onCancel = { backStack.removeLastOrNull() },
                        // Pop back to Services; the redeemed entitlement now drives the
                        // storage figures (and the "active storage" card) there.
                        onRedeemed = { backStack.removeLastOrNull() },
                    )
                }
                entry<Destination.AccountDeletion> { _ ->
                    val viewModel = koinViewModel<AccountDeletionViewModel>()
                    AccountDeletionScreen(
                        viewModel = viewModel,
                        onCancel = { backStack.removeLastOrNull() },
                        // Back to Services, which now shows the signed-out state.
                        onDone = { backStack.removeLastOrNull() },
                    )
                }
                entry<Destination.DisconnectConfirm> { _ ->
                    val viewModel = koinViewModel<DisconnectConfirmViewModel>()
                    DisconnectConfirmScreen(
                        viewModel = viewModel,
                        onCancel = { backStack.removeLastOrNull() },
                        onDisconnected = { backStack.removeLastOrNull() },
                    )
                }
                entry<Destination.Settings> { _ ->
                    SettingsScreen(
                        onSyncClick = {
                            backStack.add(Destination.Sync)
                        },
                        onBackgroundSyncClick = {
                            backStack.add(Destination.BackgroundSync)
                        },
                        onBackgroundIngestClick = {
                            backStack.add(Destination.BackgroundIngest)
                        },
                        onRecoverySetupClick = {
                            backStack.add(Destination.RecoveryPhrase)
                        },
                        onForgetDeviceClick = {
                            // ⚠ Load the phrase BEFORE showing the dialog: §5b.12.2 routes
                            // destruction through the reveal, and a dialog that pops first and
                            // fills in later would let a fast tap past the very thing it exists
                            // to show.
                            forgetScope.launch {
                                // Re-auth (§6.1). ⚠⚠ The gate is HERE, before the dialog — not on
                                // its confirm button. `ForgetDeviceDialog` *shows the recovery
                                // phrase in the clear* (deliberately: §5b.12.2 routes destruction
                                // through the reveal), so it is a second disclosure path to the
                                // very secret the phrase screen gates. Gating the confirm would
                                // leave "open the forget dialog, read the words, cancel" as a way
                                // straight around that gate. One prompt covers both the disclosure
                                // and the irreversible action behind it.
                                //
                                // ⚠ Asking is not refusing: `confirm` passes through on a device
                                // that cannot prompt, so a user with a broken or sold device can
                                // still forget it — which §5b.12.2 requires.
                                if (!reauthenticator.confirm(reauthPromptHost, forgetReauthCopy)) {
                                    return@launch
                                }
                                forgetPhrase = runCatchingCancellable {
                                    syncRepositoryForForget.getRecoveryPhrase().getOrNull()?.get()
                                }.getOrNull()
                                forgetAcknowledged = false
                                showForgetDialog = true
                            }
                        },
                        onUnlockClick = {
                            backStack.add(Destination.SettingsUnlock)
                        },
                        onErrorsClick = {
                            backStack.add(Destination.Errors)
                        },
                        onOfflineMapsClick = {
                            backStack.add(Destination.OfflineMaps)
                        },
                        onModelsClick = {
                            backStack.add(Destination.Models)
                        },
                        onAdvancedClick = {
                            backStack.add(Destination.SettingsAdvanced)
                        },
                        onLicencesClick = {
                            backStack.add(Destination.SettingsLicences)
                        },
                    )
                }
                entry<Destination.SettingsLicences> { _ ->
                    // Control serves offline map regions and Photon lookups to every app, and
                    // downloads the models, so it credits all three (docs/oss-licences.md §3).
                    OpenSourceLicencesScreen(
                        onBack = { backStack.removeLastOrNull() },
                        dataAttributions = listOf(
                            DataAttributions.OpenStreetMap,
                            DataAttributions.Protomaps,
                            DataAttributions.Photon,
                            DataAttributions.Models,
                        ),
                    )
                }
                entry<Destination.OfflineMaps> { _ ->
                    OfflineMapsScreen(
                        viewModel = koinViewModel<OfflineMapsViewModel>(),
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<Destination.Models> { _ ->
                    ModelsScreen(
                        viewModel = koinViewModel<ModelsViewModel>(),
                        onOpenFeature = { backStack.add(Destination.ModelFeature(it.id)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<Destination.ModelFeature> { destination ->
                    // ⚠ An id this build does not know has no screen to show; go back.
                    val capability = InferenceCapability.entries.firstOrNull { it.id == destination.capabilityId }
                    if (capability == null) {
                        LaunchedEffect(Unit) { backStack.removeLastOrNull() }
                    } else {
                        ModelFeatureScreen(
                            viewModel = koinViewModel<ModelsViewModel>(qualifier = app.tenshu.control.di.MODEL_FEATURE_VIEW_MODEL),
                            capability = capability,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                }
                entry<Destination.SettingsAdvanced> { _ ->
                    AdvancedSettingsScreen(
                        onSecurityClick = { backStack.add(Destination.SettingsSecurity) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<Destination.SettingsSecurity> { _ ->
                    SecuritySettingsScreen(
                        viewModel = koinViewModel<SecuritySettingsViewModel>(),
                        onEnableEncryptOnUnlock = {
                            backStack.add(Destination.SettingsEncryptSetup)
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<Destination.SettingsEncryptSetup> { _ ->
                    // Reuse the passphrase-floor SetupUnlock screen; pop back to
                    // Security on complete/skip (it re-reads hard-mode state on resume).
                    SetupUnlockScreen(
                        onComplete = { backStack.removeLastOrNull() },
                    )
                }
                entry<Destination.BackgroundSync> { _ ->
                    val viewModel = koinViewModel<BackgroundSyncSettingsViewModel>()
                    BackgroundSyncSettingsScreen(
                        viewModel = viewModel,
                        onBack = { backStack.removeLastOrNull() }
                    )
                }
                entry<Destination.BackgroundIngest> { _ ->
                    val viewModel = koinViewModel<BackgroundIngestViewModel>()
                    BackgroundIngestScreen(
                        viewModel = viewModel,
                        onBack = { backStack.removeLastOrNull() }
                    )
                }
                entry<Destination.Errors> { _ ->
                    val viewModel = koinViewModel<ErrorsViewModel>()
                    ErrorsScreen(
                        viewModel = viewModel,
                        onBack = { backStack.removeLastOrNull() }
                    )
                }
                entry<Destination.SettingsUnlock> { _ ->
                    val viewModel = koinViewModel<UnlockSettingsViewModel>()
                    UnlockSettingsScreen(
                        viewModel = viewModel,
                        onBack = { backStack.removeLastOrNull() }
                    )
                }
                entry<Destination.Sync> { _ ->
                    SyncDashboardScreen(
                        onAddDevice = {
                            backStack.add(Destination.PairingWizard)
                        },
                        onShowRecoveryPhrase = {
                            backStack.add(Destination.RecoveryPhrase)
                        }
                    )
                }
                entry<Destination.PairingWizard> { _ ->
                    PairingWizardScreen(
                        onBack = { backStack.removeLastOrNull() },
                        // A terminal step that found the realm cannot sync sends the user
                        // to the plan picker — the same route the "get storage" CTAs already take.
                        onSetUpStorage = { backStack.add(Destination.AccountPicker) },
                        onComplete = {
                            // Onboarding (Welcome → PairingWizard) routes
                            // through §4a.6 SetupUnlock → Home. From the
                            // Sync dashboard the wizard just pops back to
                            // the dashboard — local unlock isn't part of
                            // the dashboard's "add another device" flow.
                            if (backStack.contains(Destination.Welcome)) {
                                backStack.clear()
                                backStack.add(Destination.SetupUnlock)
                            } else {
                                backStack.removeLastOrNull()
                            }
                        }
                    )
                }
                entry<Destination.RecoveryPhrase> { _ ->
                    RecoveryPhraseScreen(
                        onBack = {
                            backStack.removeLastOrNull()
                        }
                    )
                }
                entry<Destination.Welcome> { _ ->
                    WelcomeScreen(
                        notice = forgetNotice,
                        onExistingDevice = {
                            backStack.add(Destination.PairingWizard)
                        },
                        onNewUser = {
                            backStack.add(Destination.NewUser)
                        },
                        onRecover = {
                            backStack.add(Destination.Recovery)
                        },
                    )
                }
                entry<Destination.Recovery> { _ ->
                    RecoveryScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onComplete = {
                            // Per spec §7.3 → §4a.6 (set up local unlock)
                            // → §4a.7 (welcome). The SetupUnlock screen
                            // routes to Home on success or skip.
                            backStack.clear()
                            backStack.add(Destination.SetupUnlock)
                        },
                    )
                }
                entry<Destination.SetupUnlock> { _ ->
                    SetupUnlockScreen(
                        onComplete = {
                            // Onboarding just finished in the foreground — whether the
                            // user took the headless default or set up the lock, don't
                            // immediately re-prompt the soft app-lock on Home. Clearing
                            // the stack drops Welcome, which is what otherwise exempted
                            // the flow (control-keep-device-unlocked.md §4.2).
                            appLockController.onAuthenticated()
                            backStack.clear()
                            backStack.add(Destination.Home)
                        },
                    )
                }
                entry<Destination.Locked> { _ ->
                    LockedScreen(
                        onUnlocked = {
                            // A hard unlock already proved presence this session — satisfy the
                            // soft "Require unlock" gate so Home doesn't prompt a *second* time.
                            // It re-arms on the next background.
                            appLockController.onAuthenticated()
                            backStack.clear()
                            backStack.add(Destination.Home)
                        },
                        onRecover = {
                            backStack.add(Destination.Recovery)
                        },
                    )
                }
                entry<Destination.NewUser> { _ ->
                    NewUserScreen(
                        onOpenDrive = {
                            // Fresh onboarding — don't jar the user with a soft-lock prompt the
                            // instant they finish; it engages on the next foreground instead.
                            appLockController.onAuthenticated()
                            backStack.clear()
                            backStack.add(Destination.Home)
                        },
                        // Pushed, not replaced: verifying pops back to "You're set up" so the user
                        // still leaves through Continue. ⚠ Straight to the OTP screen rather than
                        // via AccountPicker — a first-run user turning on sync is not shopping for
                        // a plan, and Free is what the picker would have sent them to anyway.
                        onSignIn = { backStack.add(Destination.FreeAccountSignup()) },
                    )
                }
            }
        )

        // App-global pairing-approval modal — overlays whatever destination is
        // showing. Disappears when the monitor clears activeRequest (approve/deny).
        pendingPairingRequest?.let { request ->
            HostPairingApprovalDialog(
                request = request,
                onApprove = pairingMonitor::approveActiveRequest,
                onDeny = pairingMonitor::denyActiveRequest,
                error = pairingApprovalError,
            )
        }
    }
    }
    if (showForgetDialog) {
        ForgetDeviceDialog(
            phrase = forgetPhrase,
            acknowledged = forgetAcknowledged,
            onAcknowledgedChange = { forgetAcknowledged = it },
            working = forgetWorking,
            onConfirm = {
                // ⚠ The dialog STAYS UP, showing progress, until the work is done — it is the only
                // surface the user is looking at, so it is the only place feedback can land.
                forgetWorking = true
                forgetScope.launch {
                    val outcome = runCatchingCancellable { syncRepositoryForForget.forgetThisDevice() }
                        .getOrNull()?.getOrNull()?.get()
                    // ⚠⚠ Logged, not shown — a deliberate exception to the op's "announced, never
                    // a silent orphan" contract, recorded in `control-keep-device-unlocked.md` §6.1.
                    // A modal here would block the teardown to deliver a remedy that can only be
                    // carried out on ANOTHER device, later, and would have to be raised
                    // asynchronously, where it goes unseen.
                    println("ForgetThisDevice: ${outcome ?: "no outcome returned"}")
                    forgetNotice = when {
                        // No description means we do not know what happened — announced, never
                        // quietly treated as success.
                        outcome == null -> UNKNOWN_FORGET_OUTCOME
                        isForgetWarning(outcome) -> outcome
                        // A clean removal needs no notice: a fresh-start screen is the wrong place
                        // for bookkeeping the user cannot act on.
                        else -> null
                    }
                    // ⚠ Clear rather than pop: this device is in no realm now, so every destination
                    // behind it is meaningless and Back would walk into a realm that is gone.
                    forgetWorking = false
                    showForgetDialog = false
                    backStack.clear()
                    backStack.add(Destination.Welcome)
                }
            },
            onDismiss = { showForgetDialog = false },
        )
    }

}
