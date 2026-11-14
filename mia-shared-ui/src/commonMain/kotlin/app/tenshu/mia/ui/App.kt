package app.tenshu.mia.ui

import app.tenshu.ui.feedback.SyncActivityCapsule
import app.tenshu.ui.feedback.rememberSyncActivity
import org.tenshu.sync.client.anyCatchUpHeartbeat
import app.tenshu.reachability.SuiteSyncProblem
import app.tenshu.ui.feedback.SuiteSyncBanner
import app.tenshu.ui.feedback.rememberSuiteSyncProblem
import org.tenshu.sync.client.syncConnectivity
import org.tenshu.sync.client.syncService
import org.tenshu.core.common.TenshuClient
import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.UnreachableReason
import app.tenshu.ui.feedback.AppControlUnavailableWall
import app.tenshu.ui.feedback.AppControlCheckingGate
import app.tenshu.ui.feedback.controlBannerIcon
import app.tenshu.ui.feedback.rememberControlBannerAction
import app.tenshu.ui.storage.ControlInstallRoute
import app.tenshu.ui.storage.rememberControlInstallRoute
import org.koin.compose.koinInject
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.vector.ImageVector
import app.tenshu.mia.shared.resources.Res
import app.tenshu.mia.shared.resources.mia_control_get_action
import app.tenshu.mia.shared.resources.mia_control_not_installed
import app.tenshu.mia.shared.resources.mia_control_not_installed_other_channel
import app.tenshu.mia.shared.resources.mia_control_not_installed_unpublished
import app.tenshu.mia.shared.resources.mia_control_not_set_up
import app.tenshu.mia.shared.resources.mia_control_open_action
import app.tenshu.mia.shared.resources.mia_control_wall_title
import app.tenshu.mia.shared.resources.mia_nav_chat
import app.tenshu.mia.shared.resources.mia_nav_history
import app.tenshu.mia.shared.resources.mia_nav_settings
import app.tenshu.mia.ui.navigation.Destination
import app.tenshu.mia.ui.screens.chat.ChatScreen
import app.tenshu.mia.ui.screens.chat.ChatViewModel
import app.tenshu.mia.ui.screens.history.ConversationDetailScreen
import app.tenshu.mia.ui.screens.history.HistoryScreen
import app.tenshu.mia.ui.screens.history.MiaHistoryViewModel
import app.tenshu.mia.ui.screens.onboarding.OnboardingScreen
import app.tenshu.mia.ui.screens.onboarding.OnboardingViewModel
import app.tenshu.mia.ui.screens.settings.SettingsScreen
import app.tenshu.ui.legal.OpenSourceLicencesScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.tenshu.ui.layout.AdaptiveNavItem
import app.tenshu.mia.ui.components.RealmStorageBannerHost
import app.tenshu.mia.ui.components.rememberRealmStorageState
import app.tenshu.ui.layout.AdaptiveNavScaffold
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Mia app root (docs/proposals/home-and-mia-apps.md §4): the one-time onboarding gate
 * (Fit's D5 shape) above the main shell — null renders nothing (no flash), false the
 * intro flow, true the tabs. Both app shells call this inside `TenshuTheme { App() }`.
 */
@Composable
fun App(
    appViewModel: MiaAppViewModel = koinViewModel(),
    reachability: ControlReachability = koinInject(),
) {
    // ⚠⚠ **The wall replaces the app, it does not sit on top of it** (ADR 0025 §Decision 1b).
    // Mia answers every question through Control — `TenshuConversationRepository` already says "I
    // can't reach Control" per message, and `MiaClientModule` states outright that the mock is not
    // a fallback. This is the shell's half: without it the app renders "Hi, I'm Mia — ask me to
    // find photos from your Drive" over a live input box, inviting a question it already knows it
    // cannot answer.
    //
    // ⚠ `NotInstalled` and `NotSetUp` only: a transport outage is transient and self-recovering,
    // and a wall that slams down on a three-second blip is worse than any banner.
    val reason by reachability.reason.collectAsState()
    // ⚠⚠ **Installed, answering, and no realm** (ADR 0025 §Decision 8). Nothing is unreachable —
    // the proxy serves every read, empty — so without this the shell renders its empty states
    // over a library that does not exist. Permanent until the user acts, so it walls like
    // `NotInstalled`; the offer is to *open* Control, never to get it.
    // ⚠⚠ ADR 0025 §8.6: Control is answering, and whether this device has a
    // realm is not known yet. Non-interactive — no shell, nothing that writes — until the realm
    // read answers (normally ~1 s); blank for 300 ms, a spinner, a retry wall past 20 s.
    if (reason == UnreachableReason.Checking) {
        AppControlCheckingGate(onRetry = reachability::recheck)
        return
    }
    if (reason == UnreachableReason.NotSetUp) {
        val wallAction = rememberControlBannerAction(reason)
        AppControlUnavailableWall(
            title = stringResource(Res.string.mia_control_wall_title),
            body = stringResource(Res.string.mia_control_not_set_up),
            icon = controlBannerIcon(reason),
            actionLabel = wallAction?.let { stringResource(Res.string.mia_control_open_action) },
            onAction = wallAction?.onClick,
        )
        return
    }
    if (reason == UnreachableReason.NotInstalled) {
        val wallRoute = rememberControlInstallRoute()
        val wallAction = rememberControlBannerAction(reason, wallRoute)
        AppControlUnavailableWall(
            title = stringResource(Res.string.mia_control_wall_title),
            body = when (wallRoute) {
                is ControlInstallRoute.Store ->
                    stringResource(Res.string.mia_control_not_installed)
                ControlInstallRoute.NotPublishedYet ->
                    stringResource(Res.string.mia_control_not_installed_unpublished)
                ControlInstallRoute.SameChannelAsThisApp ->
                    stringResource(Res.string.mia_control_not_installed_other_channel)
            },
            actionLabel = wallAction?.let { stringResource(Res.string.mia_control_get_action) },
            onAction = wallAction?.onClick,
        )
        return
    }

    val onboarded by appViewModel.onboarded.collectAsState()
    when (onboarded) {
        null -> Unit
        false -> OnboardingScreen(koinViewModel<OnboardingViewModel>())
        true -> MainShell()
    }
}

@Composable
private fun MainShell() {
    val backStack = remember { mutableStateListOf<Destination>(Destination.Chat) }
    fun pop() { if (backStack.size > 1) backStack.removeLastOrNull() }
    fun selectTop(destination: Destination.TopLevel) {
        if (backStack.lastOrNull() != destination) {
            backStack.clear()
            backStack.add(destination)
        }
    }

    val topLevel = remember {
        listOf(Destination.Chat, Destination.History, Destination.Settings)
    }
    val current = backStack.last()
    val navItems = topLevel.map { destination ->
        AdaptiveNavItem(
            key = destination,
            icon = navIcon(destination),
            label = navLabel(destination),
            selected = current == destination,
            onClick = { selectTop(destination) },
        )
    }

    AdaptiveNavScaffold(
        navItems = navItems,
        showNav = current is Destination.TopLevel,
        contentWindowInsets = WindowInsets.systemBars,
    ) {
        // ⚠ Inside the scaffold's content, which is where the status-bar inset has already been
        // applied (`contentWindowInsets = systemBars` above) — so the banner is inset for free.
        // A hand-rolled inset puts the banner under the clock.
        // ⚠⚠ One strip (`suite-sync-status.md` SS-1): this device's sync health and storage. No
        // Control verdict: this app shows Control trouble as its walls, never as a strip.
        val tenshuClient: TenshuClient = koinInject()
        val connectivity = remember(tenshuClient) { tenshuClient.syncService.syncConnectivity() }
        val syncProblem = rememberSuiteSyncProblem(
            reason = null,
            storage = rememberRealmStorageState(),
            connectivity = connectivity,
        )
        // SS-2 (`suite-sync-status.md`): "Syncing…" while Control pulls, through its heartbeat so a
        // killed Control's stale flag never sticks.
        val catchingUp = remember(tenshuClient) { tenshuClient.syncService.anyCatchUpHeartbeat() }
        val syncActivity = rememberSyncActivity(catchingUp)
        Column(Modifier.fillMaxSize()) {
            SuiteSyncBanner(
                problem = syncProblem,
                controlBanner = {},
                storageBanner = { RealmStorageBannerHost(it) },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                NavContent(backStack, ::pop) { backStack.add(it) }
                // Over the screens, never beside them; a problem strip, or a screen that is not a tab, hides it.
                SyncActivityCapsule(activity = syncActivity, hidden = syncProblem != SuiteSyncProblem.None || current !is Destination.TopLevel || current == Destination.Chat)
            }
        }
    }
}

@Composable
private fun NavContent(
    backStack: SnapshotStateList<Destination>,
    pop: () -> Unit,
    push: (Destination) -> Unit,
) {
    KmpNavHost(
        backStack = backStack,
        onBack = pop,
        entryProvider = kmpEntryProvider {
            entry<Destination.Chat> {
                ChatScreen(viewModel = koinViewModel<ChatViewModel>())
            }
            entry<Destination.History> {
                HistoryScreen(
                    viewModel = koinViewModel<MiaHistoryViewModel>(),
                    onOpen = { push(Destination.ConversationDetail(it)) },
                )
            }
            entry<Destination.Settings> {
                SettingsScreen(onLicences = { push(Destination.Licences) })
            }
            entry<Destination.Licences> {
                // ⚠ The shell has padded the system bars already (`contentWindowInsets` above).
                OpenSourceLicencesScreen(
                    onBack = pop,
                    modifier = Modifier.consumeWindowInsets(WindowInsets.systemBars),
                )
            }
            entry<Destination.ConversationDetail> { key ->
                val historyViewModel = koinViewModel<MiaHistoryViewModel>()
                val conversation = historyViewModel.conversation(key.conversationId)
                if (conversation == null) {
                    // The session vanished (cannot happen over the mock); fall back to History.
                    pop()
                } else {
                    ConversationDetailScreen(conversation = conversation, onBack = pop)
                }
            }
        },
    )
}

private fun navIcon(destination: Destination.TopLevel): ImageVector = when (destination) {
    Destination.Chat -> Icons.AutoMirrored.Filled.Chat
    Destination.History -> Icons.Default.History
    Destination.Settings -> Icons.Default.Settings
}

@Composable
private fun navLabel(destination: Destination.TopLevel): String = when (destination) {
    Destination.Chat -> stringResource(Res.string.mia_nav_chat)
    Destination.History -> stringResource(Res.string.mia_nav_history)
    Destination.Settings -> stringResource(Res.string.mia_nav_settings)
}
