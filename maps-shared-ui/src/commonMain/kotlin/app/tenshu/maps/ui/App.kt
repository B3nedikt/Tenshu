package app.tenshu.maps.ui

import app.tenshu.ui.feedback.SyncActivityCapsule
import app.tenshu.ui.feedback.rememberSyncActivity
import org.tenshu.sync.client.anyCatchUpHeartbeat
import app.tenshu.reachability.SuiteSyncProblem
import app.tenshu.ui.feedback.SuiteSyncBanner
import app.tenshu.ui.feedback.rememberSuiteSyncProblem
import org.tenshu.sync.client.syncConnectivity
import org.tenshu.sync.client.syncService
import app.tenshu.maps.shared.resources.maps_update_action
import app.tenshu.ui.feedback.appControlCheckingLabel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import app.tenshu.ui.launch.AnnounceAtLaunch
import org.koin.core.context.GlobalContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.common.TenshuClient
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.tenshu.maps.data.MapsMockData
import app.tenshu.maps.data.PlaceRepository
import app.tenshu.maps.shared.resources.Res
import app.tenshu.maps.shared.resources.maps_control_unreachable_text
import app.tenshu.maps.shared.resources.maps_control_not_installed
import app.tenshu.maps.shared.resources.maps_control_wall_title
import app.tenshu.maps.shared.resources.maps_control_not_installed_other_channel
import app.tenshu.maps.shared.resources.maps_control_not_installed_unpublished
import app.tenshu.maps.shared.resources.maps_control_get_action
import app.tenshu.maps.shared.resources.maps_control_not_set_up
import app.tenshu.maps.shared.resources.maps_control_open_action
import app.tenshu.maps.shared.resources.maps_skew_update_both
import app.tenshu.maps.shared.resources.maps_fenced_update_unknown
import app.tenshu.maps.shared.resources.maps_fenced_update_both
import app.tenshu.maps.shared.resources.maps_fenced_update_this_app
import app.tenshu.maps.shared.resources.maps_fenced_update_control
import app.tenshu.maps.shared.resources.maps_skew_update_control
import app.tenshu.maps.shared.resources.maps_skew_update_this_app
import app.tenshu.maps.ui.components.RealmStorageBannerHost
import app.tenshu.maps.ui.components.rememberRealmStorageState
import org.tenshu.storage.common.RealmStorageState
import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.UpdateTarget
import app.tenshu.ui.feedback.ErrorBanner
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import app.tenshu.ui.feedback.AppControlUnavailableWall
import app.tenshu.ui.feedback.AppControlCheckingGate
import app.tenshu.ui.feedback.controlBannerIcon
import app.tenshu.ui.feedback.ControlBannerAction
import app.tenshu.ui.feedback.rememberControlBannerAction
import app.tenshu.ui.storage.ControlInstallRoute
import app.tenshu.ui.storage.rememberControlInstallRoute
import app.tenshu.maps.ui.navigation.Destination
import app.tenshu.maps.ui.screens.detail.PlaceDetailScreen
import app.tenshu.maps.ui.screens.detail.PlaceDetailViewModel
import app.tenshu.maps.ui.screens.places.PlacesScreen
import app.tenshu.maps.ui.screens.places.PlacesViewModel
import app.tenshu.maps.ui.screens.regions.RegionPickerScreen
import app.tenshu.maps.ui.screens.regions.RegionPickerViewModel
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import app.tenshu.ui.legal.DataAttributions
import app.tenshu.ui.legal.OpenSourceLicencesScreen

/**
 * Maps app root (docs/proposals/maps-app.md §6). A `KmpNavHost` over a plain back
 * stack (`mutableStateListOf(Places)`); Places is the single top-level surface (the four tabs
 * live inside it, D1), and a tapped place pushes the detail. Both shells call this inside
 * `TenshuTheme { App() }`.
 *
 * `today` is sourced at this composition root so the screens stay store-agnostic. It is the
 * mock anchor (`MapsMockData.today`), not the device's date.
 */
@Composable
fun App(
    repository: PlaceRepository = koinInject(),
    /**
     * Rows another app asked this app to show — `control-cross-app-open.md` TT-2b.
     *
     * ⚠⚠ A `Flow`: a cold-start target arrives in the launch `Intent`, read before this composition
     * exists. ⚠ Empty by default, so desktop and tests are unchanged.
     */
    openTargets: Flow<DataId> = emptyFlow(),
) {
    // Registration must not wait for a screen to issue an operation (agent.md §7): resolving the
    // client opens the session, and the client module announces on the scope it was wired with.
    AnnounceAtLaunch { GlobalContext.get().get<TenshuClient>() }
    val today = MapsMockData.today
    val backStack = remember { mutableStateListOf<Destination>(Destination.Places) }
    fun push(destination: Destination) = backStack.add(destination)

    // Cross-app open, receiving half (`control-cross-app-open.md` TT-2b).
    // ⚠ A target of another type is ignored rather than guessed at: Control routes by type, so
    // receiving one is a table bug, and opening a screen anyway would hide it.
    LaunchedEffect(openTargets) {
        openTargets.collect { target -> destinationForTarget(target)?.let(::push) }
    }

    fun pop() { if (backStack.size > 1) backStack.removeLastOrNull() }

    // Control-reachability banner (fit-app.md §9.14 recipe): shell-level, clears itself when
    // the repository's recovery probe sees Control return.
    val controlReachable by repository.controlReachable().collectAsState()
    // Decides the banner's words, not whether it shows.
    val unreachableReason by repository.controlUnreachableReason().collectAsState()
    // ⚠ Hoisted out of the host so the shell can tell whether ANY banner is its top element — the
    // inset below depends on it; read inside the host, the storage banner draws under the status
    // bar.
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

    // ⚠⚠ **The wall replaces the app, it does not sit on top of it** (ADR 0025 §Decision 1,
    // amended). Without Control there is no realm to read, so every surface underneath a banner is
    // either empty or — worse — an onboarding invitation that cannot be accepted.
    //
    // ⚠ `NotInstalled` and `NotSetUp` only: a transport outage is transient and self-recovering,
    // and a wall that slams down on a three-second blip is worse than any banner. The rule is the
    // reason, never the platform.
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
            title = stringResource(Res.string.maps_control_wall_title),
            body = stringResource(Res.string.maps_control_not_set_up),
            icon = controlBannerIcon(unreachableReason),
            actionLabel = wallAction?.let { stringResource(Res.string.maps_control_open_action) },
            onAction = wallAction?.onClick,
        )
        return
    }
    if (unreachableReason == UnreachableReason.NotInstalled) {
        val wallRoute = rememberControlInstallRoute()
        val wallAction = rememberControlBannerAction(unreachableReason, wallRoute)
        AppControlUnavailableWall(
            title = stringResource(Res.string.maps_control_wall_title),
            body = when (wallRoute) {
                is ControlInstallRoute.Store -> stringResource(Res.string.maps_control_not_installed)
                ControlInstallRoute.NotPublishedYet ->
                    stringResource(Res.string.maps_control_not_installed_unpublished)
                ControlInstallRoute.SameChannelAsThisApp ->
                    stringResource(Res.string.maps_control_not_installed_other_channel)
            },
            actionLabel = wallAction?.let { stringResource(Res.string.maps_control_get_action) },
            onAction = wallAction?.onClick,
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            // ⚠⚠ `AdaptiveNavScaffold` passes `contentWindowInsets = 0`, so the shell's top element
            // owns the status-bar inset itself. Without this the banner draws UNDER the clock and
            // battery icons. ⚠ BOTH banners count: a storage banner is just as much the top element
            // as the reachability one.
            .then(
                if (syncProblem != SuiteSyncProblem.None) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                } else {
                    Modifier
                }
            )
    ) {
        // WARN: gated on the REASON, not `controlReachable == false`. A fenced device
        // answers every read - its state stays true - so that condition could never
        // fire for the one case where the app is silently receiving nothing (a fenced device).
        if (controlReason != null) {
            // ⚠⚠ The app to name is frequently NOT Maps: on Android and desktop one
            // Control-owned proxy serves every app, so a version disagreement is usually
            // Control's to fix and telling the user to update Maps is a dead end.
            // ⚠⚠ The route decides the *words* as well as the button: a build whose Control is not
            // in the store must NOT be told "install it the way you installed this app", because
            // that way does not work (ADR 0025 §Decision 6).
            val installRoute = rememberControlInstallRoute()
            val action = rememberControlBannerAction(controlReason, installRoute)
            ErrorBanner(
                text = when (val r = controlReason) {
                    is UnreachableReason.Skew -> when (r.update) {
                        UpdateTarget.CONTROL -> stringResource(Res.string.maps_skew_update_control)
                        UpdateTarget.THIS_APP -> stringResource(Res.string.maps_skew_update_this_app)
                        UpdateTarget.BOTH -> stringResource(Res.string.maps_skew_update_both)
                    }
                    is UnreachableReason.Fenced -> when (r.update) {
                        UpdateTarget.CONTROL -> stringResource(Res.string.maps_fenced_update_control)
                        UpdateTarget.THIS_APP -> stringResource(Res.string.maps_fenced_update_this_app)
                        UpdateTarget.BOTH -> stringResource(Res.string.maps_fenced_update_both)
                        // No host declared itself: name nothing rather than guess wrong on a platform.
                        null -> stringResource(Res.string.maps_fenced_update_unknown)
                    }
                    // ⚠ Normally behind the wall (ADR 0025 §Decision 8); worded anyway, never as an outage.
                    // Never reaches a banner — the root shows AppControlCheckingGate first (ADR 0025 §8.6).
                    UnreachableReason.Checking -> appControlCheckingLabel()
                    UnreachableReason.NotSetUp -> stringResource(Res.string.maps_control_not_set_up)
                    UnreachableReason.NotInstalled -> when (installRoute) {
                        is ControlInstallRoute.Store -> stringResource(Res.string.maps_control_not_installed)
                        ControlInstallRoute.NotPublishedYet ->
                            stringResource(Res.string.maps_control_not_installed_unpublished)
                        ControlInstallRoute.SameChannelAsThisApp ->
                            stringResource(Res.string.maps_control_not_installed_other_channel)
                    }
                    UnreachableReason.Transport, null -> stringResource(Res.string.maps_control_unreachable_text)
                },
                icon = controlBannerIcon(controlReason),
                actionLabel = action?.let {
                    when (it.kind) {
                        ControlBannerAction.Kind.OPEN_CONTROL ->
                            stringResource(Res.string.maps_control_open_action)
                        ControlBannerAction.Kind.GET_CONTROL ->
                            stringResource(Res.string.maps_control_get_action)
                        ControlBannerAction.Kind.UPDATE ->
                            stringResource(Res.string.maps_update_action)
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
            NavContent(backStack, today, ::pop, ::push)
            // Over the screens, never beside them; a problem strip, or a screen that is not a tab, hides it.
            SyncActivityCapsule(
                activity = syncActivity,
                // Maps has no tab marker: Places is its one root, and every other screen is a detail.
                hidden = syncProblem != SuiteSyncProblem.None || backStack.last() != Destination.Places,
            )
        }
    }
}

/** The nav host, extracted so [App] can stack the reachability banner above it. */
@Composable
private fun NavContent(
    backStack: androidx.compose.runtime.snapshots.SnapshotStateList<Destination>,
    today: kotlinx.datetime.LocalDate,
    pop: () -> Unit,
    push: (Destination) -> Unit,
) {
    KmpNavHost(
        backStack = backStack,
        onBack = pop,
        entryProvider = kmpEntryProvider {
            entry<Destination.Places> {
                PlacesScreen(
                    viewModel = koinViewModel<PlacesViewModel>(),
                    today = today,
                    onOpenPlace = { push(Destination.PlaceDetail(it)) },
                    onOpenRegions = { push(Destination.Regions) },
                )
            }
            entry<Destination.Regions> {
                RegionPickerScreen(
                    viewModel = koinViewModel<RegionPickerViewModel>(),
                    onBack = pop,
                    onOpenLicences = { push(Destination.Licences) },
                )
            }
            entry<Destination.Licences> {
                OpenSourceLicencesScreen(onBack = pop, dataAttributions = DataAttributions.Basemap)
            }
            entry<Destination.PlaceDetail> { key ->
                PlaceDetailScreen(
                    viewModel = koinViewModel<PlaceDetailViewModel>(
                        key = key.placeId,
                        parameters = { parametersOf(key.placeId) },
                    ),
                    today = today,
                    onBack = pop,
                )
            }
        },
    )
}
