package app.tenshu.home.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import app.tenshu.ui.launch.AnnounceAtLaunch
import org.koin.core.context.GlobalContext
import org.tenshu.core.common.TenshuClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.vector.ImageVector
import app.tenshu.home.shared.resources.Res
import app.tenshu.home.shared.resources.home_control_get_action
import app.tenshu.home.shared.resources.home_control_not_installed
import app.tenshu.home.shared.resources.home_control_not_installed_other_channel
import app.tenshu.home.shared.resources.home_control_not_installed_unpublished
import app.tenshu.home.shared.resources.home_control_not_set_up
import app.tenshu.home.shared.resources.home_control_open_action
import app.tenshu.home.shared.resources.home_control_wall_title
import app.tenshu.home.shared.resources.home_nav_activity
import app.tenshu.home.shared.resources.home_nav_automations
import app.tenshu.home.shared.resources.home_nav_home
import app.tenshu.home.shared.resources.home_nav_settings
import app.tenshu.home.shared.resources.home_placeholder_settings_body
import app.tenshu.home.ui.navigation.Destination
import app.tenshu.home.ui.screens.activity.ActivityScreen
import app.tenshu.home.ui.screens.activity.ActivityViewModel
import app.tenshu.home.ui.screens.automations.AutomationEditorScreen
import app.tenshu.home.ui.screens.automations.AutomationEditorViewModel
import app.tenshu.home.ui.screens.automations.AutomationsScreen
import app.tenshu.home.ui.screens.automations.AutomationsViewModel
import app.tenshu.home.ui.screens.devices.DevicesScreen
import app.tenshu.home.ui.screens.devices.DevicesViewModel
import app.tenshu.home.ui.screens.mia.AskMiaScreen
import app.tenshu.home.ui.screens.placeholder.PlaceholderScreen
import app.tenshu.ui.layout.AdaptiveNavItem
import app.tenshu.home.ui.components.RealmStorageBannerHost
import app.tenshu.home.ui.components.rememberRealmStorageState
import app.tenshu.ui.layout.AdaptiveNavScaffold
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import app.tenshu.ui.legal.AppPrivacyPolicyListItem
import app.tenshu.ui.legal.OpenSourceLicencesListItem
import app.tenshu.ui.legal.OpenSourceLicencesScreen

/**
 * Home app root (docs/proposals/home-and-mia-apps.md §3). `AdaptiveNavScaffold` with
 * the four top-level tabs over a `KmpNavHost`; Settings is a placeholder. Both app shells
 * call this inside `TenshuTheme { App() }`.
 */
@Composable
fun App() {
    // Registration must not wait for a screen to issue an operation (agent.md §7): resolving the
    // client opens the session, and the client module announces on the scope it was wired with.
    AnnounceAtLaunch { GlobalContext.get().get<TenshuClient>() }

    // ⚠⚠ **The wall replaces the app, it does not sit on top of it** (ADR 0025 §Decision 1b).
    // An app that shows a lamp as on when it can reach nothing is worse than one that shows
    // nothing.
    //
    // ⚠ `NotInstalled` and `NotSetUp` only: a transport outage is transient and self-recovering,
    // and a wall that slams down on a three-second blip is worse than any banner.
    val reachability = koinInject<ControlReachability>()
    val reason: UnreachableReason? by reachability.reason.collectAsState()
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
            title = stringResource(Res.string.home_control_wall_title),
            body = stringResource(Res.string.home_control_not_set_up),
            icon = controlBannerIcon(reason),
            actionLabel = wallAction?.let { stringResource(Res.string.home_control_open_action) },
            onAction = wallAction?.onClick,
        )
        return
    }
    if (reason == UnreachableReason.NotInstalled) {
        val wallRoute = rememberControlInstallRoute()
        val wallAction = rememberControlBannerAction(reason, wallRoute)
        AppControlUnavailableWall(
            title = stringResource(Res.string.home_control_wall_title),
            body = when (wallRoute) {
                is ControlInstallRoute.Store ->
                    stringResource(Res.string.home_control_not_installed)
                ControlInstallRoute.NotPublishedYet ->
                    stringResource(Res.string.home_control_not_installed_unpublished)
                ControlInstallRoute.SameChannelAsThisApp ->
                    stringResource(Res.string.home_control_not_installed_other_channel)
            },
            actionLabel = wallAction?.let { stringResource(Res.string.home_control_get_action) },
            onAction = wallAction?.onClick,
        )
        return
    }

    val backStack = remember { mutableStateListOf<Destination>(Destination.Home) }
    fun pop() { if (backStack.size > 1) backStack.removeLastOrNull() }
    fun selectTop(destination: Destination.TopLevel) {
        if (backStack.lastOrNull() != destination) {
            backStack.clear()
            backStack.add(destination)
        }
    }

    val topLevel = remember {
        listOf(
            Destination.Home,
            Destination.Automations,
            Destination.Activity,
            Destination.Settings,
        )
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
        // Home's screens are headline Columns without their own TopAppBar, so nothing else
        // consumes the status-bar inset — let the shared scaffold apply it (Fit's posture).
        contentWindowInsets = WindowInsets.systemBars,
    ) {
        // ⚠ Inside the scaffold's content, which is where the status-bar inset has already been
        // applied (`contentWindowInsets = systemBars` above) — so the banner is inset for free.
        // Hand-rolled, the banner draws under the status bar.
        Column(Modifier.fillMaxSize()) {
            RealmStorageBannerHost(rememberRealmStorageState())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                NavContent(backStack, ::pop) { backStack.add(it) }
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
            entry<Destination.Home> {
                DevicesScreen(
                    viewModel = koinViewModel<DevicesViewModel>(),
                    onAskMia = { push(Destination.MiaChat) },
                )
            }
            entry<Destination.MiaChat> {
                AskMiaScreen(onBack = pop)
            }
            entry<Destination.Automations> {
                AutomationsScreen(
                    viewModel = koinViewModel<AutomationsViewModel>(),
                    onCreate = { push(Destination.AutomationEditor(automationId = null)) },
                    onEdit = { push(Destination.AutomationEditor(automationId = it)) },
                )
            }
            entry<Destination.Activity> {
                ActivityScreen(viewModel = koinViewModel<ActivityViewModel>())
            }
            entry<Destination.Settings> {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        PlaceholderScreen(
                            title = stringResource(Res.string.home_nav_settings),
                            body = stringResource(Res.string.home_placeholder_settings_body),
                            icon = Icons.Default.Settings,
                        )
                    }
                    // The legal entries: the Settings tab is a placeholder, but it is the app's
                    // settings screen. The in-app privacy policy
                    // (App Store 5.1.1(i), Play User Data policy), then the open-source notices
                    // (docs/oss-licences.md).
                    AppPrivacyPolicyListItem()
                    OpenSourceLicencesListItem(onClick = { push(Destination.Licences) })
                }
            }
            entry<Destination.Licences> {
                // ⚠ The shell has padded the system bars already (`contentWindowInsets` above).
                OpenSourceLicencesScreen(
                    onBack = pop,
                    modifier = Modifier.consumeWindowInsets(WindowInsets.systemBars),
                )
            }
            entry<Destination.AutomationEditor> { key ->
                AutomationEditorScreen(
                    // The id rides as a sentinel "" for create — see viewModelsModule.
                    viewModel = koinViewModel<AutomationEditorViewModel>(
                        key = key.automationId ?: "automation-new",
                        parameters = { parametersOf(key.automationId ?: "") },
                    ),
                    isNew = key.automationId == null,
                    onBack = pop,
                )
            }
        },
    )
}

private fun navIcon(destination: Destination.TopLevel): ImageVector = when (destination) {
    Destination.Home -> Icons.Default.Home
    Destination.Automations -> Icons.Default.AutoAwesome
    Destination.Activity -> Icons.Default.History
    Destination.Settings -> Icons.Default.Settings
}

@Composable
private fun navLabel(destination: Destination.TopLevel): String = when (destination) {
    Destination.Home -> stringResource(Res.string.home_nav_home)
    Destination.Automations -> stringResource(Res.string.home_nav_automations)
    Destination.Activity -> stringResource(Res.string.home_nav_activity)
    Destination.Settings -> stringResource(Res.string.home_nav_settings)
}
