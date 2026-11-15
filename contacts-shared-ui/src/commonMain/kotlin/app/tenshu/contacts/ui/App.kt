package app.tenshu.contacts.ui

import app.tenshu.ui.feedback.SyncActivityCapsule
import app.tenshu.ui.feedback.rememberSyncActivity
import org.tenshu.sync.client.anyCatchUpHeartbeat
import app.tenshu.reachability.SuiteSyncProblem
import app.tenshu.ui.feedback.SuiteSyncBanner
import app.tenshu.ui.feedback.rememberSuiteSyncProblem
import org.tenshu.sync.client.syncConnectivity
import org.tenshu.sync.client.syncService
import app.tenshu.contacts.shared.resources.contacts_update_action
import app.tenshu.ui.feedback.appControlCheckingLabel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Contacts
import app.tenshu.ui.launch.AnnounceAtLaunch
import org.koin.core.context.GlobalContext
import org.tenshu.core.common.TenshuClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.tenshu.contacts.data.ContactsRepository
import app.tenshu.contacts.shared.resources.Res
import app.tenshu.contacts.shared.resources.contacts_control_unreachable
import app.tenshu.contacts.shared.resources.contacts_control_not_installed
import app.tenshu.contacts.shared.resources.contacts_control_wall_title
import app.tenshu.contacts.shared.resources.contacts_control_not_installed_other_channel
import app.tenshu.contacts.shared.resources.contacts_control_not_installed_unpublished
import app.tenshu.contacts.shared.resources.contacts_control_get_action
import app.tenshu.contacts.shared.resources.contacts_control_not_set_up
import app.tenshu.contacts.shared.resources.contacts_control_open_action
import app.tenshu.contacts.shared.resources.contacts_skew_update_both
import app.tenshu.contacts.shared.resources.contacts_fenced_update_unknown
import app.tenshu.contacts.shared.resources.contacts_fenced_update_both
import app.tenshu.contacts.shared.resources.contacts_fenced_update_this_app
import app.tenshu.contacts.shared.resources.contacts_fenced_update_control
import app.tenshu.contacts.shared.resources.contacts_skew_update_control
import app.tenshu.contacts.shared.resources.contacts_skew_update_this_app
import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.UpdateTarget
import app.tenshu.contacts.shared.resources.contacts_nav_contacts
import app.tenshu.contacts.shared.resources.contacts_nav_you
import app.tenshu.contacts.ui.navigation.Destination
import app.tenshu.contacts.ui.screens.detail.ContactDetailScreen
import app.tenshu.contacts.ui.screens.detail.ContactDetailViewModel
import app.tenshu.contacts.ui.screens.importing.ContactImportViewModel
import app.tenshu.contacts.ui.screens.importing.ImportSheetContent
import app.tenshu.contacts.ui.screens.invite.ConfirmContactDialog
import app.tenshu.contacts.ui.screens.invite.ConfirmContactViewModel
import app.tenshu.contacts.ui.screens.invite.InviteSheetContent
import app.tenshu.contacts.ui.screens.invite.InviteViewModel
import app.tenshu.contacts.ui.screens.list.ContactListScreen
import app.tenshu.contacts.ui.screens.list.ContactListViewModel
import app.tenshu.contacts.ui.screens.merge.MergeScreen
import app.tenshu.contacts.ui.screens.merge.MergeViewModel
import app.tenshu.contacts.ui.screens.profile.ProfileScreen
import app.tenshu.contacts.ui.screens.profile.ProfileViewModel
import app.tenshu.ui.chrome.AppBottomSheet
import app.tenshu.contacts.ui.components.RealmStorageBannerHost
import app.tenshu.contacts.ui.components.rememberRealmStorageState
import org.tenshu.storage.common.RealmStorageState
import app.tenshu.ui.feedback.ErrorBanner
import app.tenshu.ui.feedback.AppControlUnavailableWall
import app.tenshu.ui.feedback.AppControlCheckingGate
import app.tenshu.ui.feedback.controlBannerIcon
import app.tenshu.ui.feedback.ControlBannerAction
import app.tenshu.ui.feedback.rememberControlBannerAction
import app.tenshu.ui.storage.ControlInstallRoute
import app.tenshu.ui.storage.rememberControlInstallRoute
import app.tenshu.ui.layout.AdaptiveNavItem
import app.tenshu.ui.layout.AdaptiveNavScaffold
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import app.tenshu.ui.legal.OpenSourceLicencesScreen

/**
 * Contacts app root (docs/proposals/contacts-app.md §6). `AdaptiveNavScaffold` over a
 * `KmpNavHost` with two top-level surfaces — the badged contact list (D3) and the own
 * profile card (D6) — and the contact detail pushed on top. Both shells call this inside
 * `TenshuTheme { App() }`.
 */
@Composable
fun App(repository: ContactsRepository = koinInject()) {
    // Registration must not wait for a screen to issue an operation (agent.md §7): resolving the
    // client opens the session, and the client module announces on the scope it was wired with.
    AnnounceAtLaunch { GlobalContext.get().get<TenshuClient>() }
    val backStack = remember { mutableStateListOf<Destination>(Destination.Contacts) }
    fun pop() { if (backStack.size > 1) backStack.removeLastOrNull() }
    fun selectTop(destination: Destination.TopLevel) {
        if (backStack.lastOrNull() != destination) {
            backStack.clear()
            backStack.add(destination)
        }
    }

    val topLevel = remember { listOf(Destination.Contacts, Destination.Profile) }
    val current = backStack.last()

    // Read at the shell, not in the list's view model: that VM only exists while the
    // Contacts tab is composed, and the badge's whole job is to be visible from the other
    // tab. Drive spends an `InviteBadgeViewModel` on this; here `App` already holds the
    // repository for the reachability banner, so a second VM would buy nothing.
    val pendingConfirmations by repository.pendingConfirmations()
        .collectAsState(initial = emptyList())

    val navItems = topLevel.map { destination ->
        AdaptiveNavItem(
            key = destination,
            icon = navIcon(destination),
            label = navLabel(destination),
            selected = current == destination,
            onClick = { selectTop(destination) },
            showBadge = destination == Destination.Contacts && pendingConfirmations.isNotEmpty(),
        )
    }

    // Sheet + dialog live at the shell so they survive a tab switch: confirming an invite
    // is the kind of thing a user starts, gets interrupted in, and comes back to.
    var inviteSheetVisible by remember { mutableStateOf(false) }
    var confirmingInviteId by remember { mutableStateOf<String?>(null) }

    // The import sheet's visibility lives on its view model rather than in a local, because
    // an import survives the picker taking over the screen — on Android the activity can be
    // recreated while the OS contact picker is up, and the sheet has to still be there when
    // it comes back with the cards.
    val importViewModel = koinViewModel<ContactImportViewModel>()
    val importState by importViewModel.uiState.collectAsState()

    // Control-reachability banner (fit-app.md §9.14 recipe): shell-level, and it clears
    // itself when the repository's recovery probe sees Control return. Without it, an
    // unreachable proxy renders as "No contacts yet" — telling someone with 200 contacts
    // that they have none.
    val controlReachable by repository.controlReachable().collectAsState(initial = null)
    // SK-1b: decides the banner's words, not whether it shows.
    val unreachableReason by repository.controlUnreachableReason().collectAsState()

    // ⚠ No `contentWindowInsets` override here, deliberately — see `ui-agent.md` §8. Every
    // Contacts screen opens with an `AppSearchBar` (the list) or an `AppTopAppBar` (profile,
    // detail, merge), and *both* consume the status-bar inset themselves. Passing
    // `WindowInsets.systemBars` here pads the content down by the status bar without marking it
    // consumed (`Modifier.padding`), and the screen's own bar pads by it a second time — ~100 dp
    // of dead space above the search bar. Fit/Home/Mia keep the override honestly: their screens
    // are bare `Column`s with no inset-consuming chrome at all.
    // ⚠⚠ **The wall replaces the app, it does not sit on top of it** (ADR 0025 §Decision 1,
    // amended). Without Control there is no realm, so every surface underneath a banner is empty —
    // and an empty contact list reads as "your contacts are gone", not as "Control is missing".
    //
    // ⚠ `NotInstalled` and `NotSetUp` only: a transport outage is transient and self-recovering,
    // and a wall that slams down on a three-second blip is worse than any banner.
    // ⚠⚠ **Installed, answering, and no realm** (ADR 0025 §Decision 8). Nothing is unreachable —
    // the proxy serves every read, empty — so without this the shell renders its empty states
    // over a library that does not exist. Permanent until the user acts, so it walls like
    // `NotInstalled`; the offer is to *open* Control, never to get it.
    // ⚠⚠ ADR 0025 §8.6: Control is answering, and whether this device has a
    // realm is not known yet. Non-interactive — no shell, nothing that writes — until the realm
    // read answers (normally ~1 s); blank for 300 ms, a spinner, a retry wall past 20 s.
    if (unreachableReason == UnreachableReason.Checking) {
        AppControlCheckingGate(onRetry = repository::recheckControl)
        return
    }
    if (unreachableReason == UnreachableReason.NotSetUp) {
        val wallAction = rememberControlBannerAction(unreachableReason)
        AppControlUnavailableWall(
            title = stringResource(Res.string.contacts_control_wall_title),
            body = stringResource(Res.string.contacts_control_not_set_up),
            icon = controlBannerIcon(unreachableReason),
            actionLabel = wallAction?.let { stringResource(Res.string.contacts_control_open_action) },
            onAction = wallAction?.onClick,
        )
        return
    }
    if (unreachableReason == UnreachableReason.NotInstalled) {
        val wallRoute = rememberControlInstallRoute()
        val wallAction = rememberControlBannerAction(unreachableReason, wallRoute)
        AppControlUnavailableWall(
            title = stringResource(Res.string.contacts_control_wall_title),
            body = when (wallRoute) {
                is ControlInstallRoute.Store -> stringResource(Res.string.contacts_control_not_installed)
                ControlInstallRoute.NotPublishedYet ->
                    stringResource(Res.string.contacts_control_not_installed_unpublished)
                ControlInstallRoute.SameChannelAsThisApp ->
                    stringResource(Res.string.contacts_control_not_installed_other_channel)
            },
            actionLabel = wallAction?.let { stringResource(Res.string.contacts_control_get_action) },
            onAction = wallAction?.onClick,
        )
        return
    }

    AdaptiveNavScaffold(
        navItems = navItems,
        showNav = current is Destination.TopLevel,
    ) {
        val storageState = rememberRealmStorageState()
        // ⚠⚠ One strip for the app (`suite-sync-status.md` SS-1): Control's state, this device's sync
        // health and storage, in one order. Control's banner below keeps this app's own words.
        val tenshuClient: TenshuClient = koinInject()
        val connectivity = remember(tenshuClient) { tenshuClient.syncService.syncConnectivity() }
        val syncProblem = rememberSuiteSyncProblem(unreachableReason, storageState, connectivity)
        // SS-2 (`suite-sync-status.md`): "Syncing…" while Control pulls, through its heartbeat so a
        // killed Control's stale flag never sticks.
        val catchingUp = remember(tenshuClient) { tenshuClient.syncService.anyCatchUpHeartbeat() }
        val syncActivity = rememberSyncActivity(catchingUp)
        val controlReason = (syncProblem as? SuiteSyncProblem.Control)?.reason
        Column(
            Modifier.fillMaxSize().then(
                // While the banner is up it *is* the shell's top element, so it takes the inset
                // for the whole column — and `windowInsetsPadding` consumes what it applies, so
                // the screen chrome below does not then pad again. Without this the banner would
                // render under the status bar the moment Control goes away.
                // ⚠⚠ Gated on the strip, not on `controlReachable == false`: every problem draws
                // one (a storage one too), and a fenced device answers every read, so it stays
                // "reachable" while showing one (SK-3).
                if (syncProblem != SuiteSyncProblem.None) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                } else {
                    Modifier
                }
            )
        ) {
            // ⚠⚠ Gated on the REASON, not `controlReachable == false`. A fenced device answers
            // every read - its state stays true - so that condition could never fire for the
            // one case where the app is silently receiving nothing (SK-3).
            if (controlReason != null) {
                // ⚠⚠ The app to name is frequently NOT Contacts: on Android and desktop one
                // Control-owned proxy serves every app, so a version disagreement is usually
                // Control's to fix and telling the user to update Contacts is a dead end.
                // ⚠⚠ The route decides the *words* as well as the button: a build whose Control is not
                // in the store yet must NOT be told "install it the way you installed this app", because
                // that way does not work yet (ADR 0025 §Decision 6).
                val installRoute = rememberControlInstallRoute()
                val action = rememberControlBannerAction(controlReason, installRoute)
                ErrorBanner(
                    text = when (val r = controlReason) {
                        is UnreachableReason.Skew -> when (r.update) {
                            UpdateTarget.CONTROL -> stringResource(Res.string.contacts_skew_update_control)
                            UpdateTarget.THIS_APP -> stringResource(Res.string.contacts_skew_update_this_app)
                            UpdateTarget.BOTH -> stringResource(Res.string.contacts_skew_update_both)
                        }
                        is UnreachableReason.Fenced -> when (r.update) {
                            UpdateTarget.CONTROL -> stringResource(Res.string.contacts_fenced_update_control)
                            UpdateTarget.THIS_APP -> stringResource(Res.string.contacts_fenced_update_this_app)
                            UpdateTarget.BOTH -> stringResource(Res.string.contacts_fenced_update_both)
                            // No host declared itself: name nothing rather than guess wrong on a platform.
                            null -> stringResource(Res.string.contacts_fenced_update_unknown)
                        }
                        // ⚠ Normally behind the wall (ADR 0025 §Decision 8); worded anyway, never as an outage.
                        // Never reaches a banner — the root shows AppControlCheckingGate first (ADR 0025 §8.6).
                        UnreachableReason.Checking -> appControlCheckingLabel()
                        UnreachableReason.NotSetUp -> stringResource(Res.string.contacts_control_not_set_up)
                        UnreachableReason.NotInstalled -> when (installRoute) {
                            is ControlInstallRoute.Store -> stringResource(Res.string.contacts_control_not_installed)
                            ControlInstallRoute.NotPublishedYet ->
                                stringResource(Res.string.contacts_control_not_installed_unpublished)
                            ControlInstallRoute.SameChannelAsThisApp ->
                                stringResource(Res.string.contacts_control_not_installed_other_channel)
                        }
                        UnreachableReason.Transport, null -> stringResource(Res.string.contacts_control_unreachable)
                    },
                    icon = controlBannerIcon(controlReason),
                    actionLabel = action?.let {
                        when (it.kind) {
                            ControlBannerAction.Kind.OPEN_CONTROL ->
                                stringResource(Res.string.contacts_control_open_action)
                            ControlBannerAction.Kind.GET_CONTROL ->
                                stringResource(Res.string.contacts_control_get_action)
                            ControlBannerAction.Kind.UPDATE ->
                                stringResource(Res.string.contacts_update_action)
                        }
                    },
                    onAction = action?.onClick,
                )
            }
            // One problem, one bar (two stacked pushed the header off screen): Control's banner is drawn
            // just above in this app's words when it is the problem; any other problem is drawn here.
            SuiteSyncBanner(
                problem = syncProblem,
                controlBanner = {},
                storageBanner = { RealmStorageBannerHost(it) },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                NavContent(
                    backStack = backStack,
                    pop = ::pop,
                    push = { backStack.add(it) },
                    onInviteSomeone = { inviteSheetVisible = true },
                    onConfirmInvite = { confirmingInviteId = it },
                    onImport = importViewModel::onImportClicked,
                )
                // Over the screens, never beside them; a problem strip, or a screen that is not a tab, hides it.
                SyncActivityCapsule(activity = syncActivity, hidden = syncProblem != SuiteSyncProblem.None || current !is Destination.TopLevel)
            }
        }
    }

    val inviteViewModel = koinViewModel<InviteViewModel>()
    AppBottomSheet(
        visible = inviteSheetVisible,
        onDismissRequest = {
            inviteSheetVisible = false
            // The link is one-session: dropping it with the sheet is the point, not a leak.
            inviteViewModel.onDismissed()
        },
    ) {
        InviteSheetContent(inviteViewModel)
    }

    AppBottomSheet(
        visible = importState.visible,
        onDismissRequest = importViewModel::onDismissed,
    ) {
        ImportSheetContent(importViewModel)
    }

    confirmingInviteId?.let { inviteId ->
        ConfirmContactDialog(
            viewModel = koinViewModel<ConfirmContactViewModel>(
                key = "confirm_$inviteId",
                parameters = { parametersOf(inviteId) },
            ),
            onDismiss = { confirmingInviteId = null },
        )
    }
}

@Composable
private fun NavContent(
    backStack: SnapshotStateList<Destination>,
    pop: () -> Unit,
    push: (Destination) -> Unit,
    onInviteSomeone: () -> Unit,
    onConfirmInvite: (String) -> Unit,
    onImport: () -> Unit,
) {
    KmpNavHost(
        backStack = backStack,
        onBack = pop,
        entryProvider = kmpEntryProvider {
            entry<Destination.Contacts> {
                ContactListScreen(
                    viewModel = koinViewModel<ContactListViewModel>(),
                    onOpenContact = { push(Destination.ContactDetail(it)) },
                    onConfirmInvite = onConfirmInvite,
                    onImport = onImport,
                    onReviewDuplicates = { push(Destination.Merge) },
                )
            }
            entry<Destination.Profile> {
                ProfileScreen(
                    viewModel = koinViewModel<ProfileViewModel>(),
                    onInviteSomeone = onInviteSomeone,
                    onOpenLicences = { push(Destination.Licences) },
                )
            }
            // Contacts draws no map and shows no third-party data: libraries only.
            entry<Destination.Licences> {
                OpenSourceLicencesScreen(onBack = pop)
            }
            entry<Destination.Merge> {
                MergeScreen(viewModel = koinViewModel<MergeViewModel>(), onBack = pop)
            }
            entry<Destination.ContactDetail> { key ->
                ContactDetailScreen(
                    viewModel = koinViewModel<ContactDetailViewModel>(
                        key = key.personId,
                        parameters = { parametersOf(key.personId) },
                    ),
                    onBack = pop,
                )
            }
        },
    )
}

private fun navIcon(destination: Destination.TopLevel): ImageVector = when (destination) {
    Destination.Contacts -> Icons.Default.Contacts
    Destination.Profile -> Icons.Default.AccountCircle
}

@Composable
private fun navLabel(destination: Destination.TopLevel): String = stringResource(
    when (destination) {
        Destination.Contacts -> Res.string.contacts_nav_contacts
        Destination.Profile -> Res.string.contacts_nav_you
    }
)
