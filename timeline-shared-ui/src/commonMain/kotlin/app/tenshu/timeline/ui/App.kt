package app.tenshu.timeline.ui

import app.tenshu.ui.feedback.SyncActivityCapsule
import app.tenshu.ui.feedback.rememberSyncActivity
import org.tenshu.sync.client.anyCatchUpHeartbeat
import app.tenshu.reachability.SuiteSyncProblem
import app.tenshu.ui.feedback.SuiteSyncBanner
import app.tenshu.ui.feedback.rememberSuiteSyncProblem
import org.tenshu.sync.client.syncConnectivity
import org.tenshu.sync.client.syncService
import app.tenshu.timeline.shared.resources.timeline_update_action
import app.tenshu.ui.legal.DataAttributions
import app.tenshu.ui.legal.OpenSourceLicencesScreen
import app.tenshu.ui.feedback.appControlCheckingLabel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import app.tenshu.ui.launch.AnnounceAtLaunch
import org.koin.core.context.GlobalContext
import org.tenshu.core.file.File
import org.tenshu.core.entity.DataId
import org.tenshu.core.common.app.TargetOpener
import org.tenshu.core.common.app.OpenTargetResult
import org.tenshu.core.app.OpenTargetUnavailable
import app.tenshu.timeline.ui.components.PhotoOpener
import app.tenshu.timeline.ui.components.LocalPhotoOpener
import org.tenshu.core.common.TenshuClient
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import app.tenshu.ui.core.ProvideMeasurementSystem
import org.tenshu.preference.MeasurementSystem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.Modifier
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.ui.components.LocalPhotoBytes
import app.tenshu.timeline.ui.components.PhotoByteLoaders
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.UpdateTarget
import app.tenshu.timeline.shared.resources.timeline_control_unreachable_text
import app.tenshu.timeline.shared.resources.timeline_control_not_installed
import app.tenshu.timeline.shared.resources.timeline_control_wall_title
import app.tenshu.timeline.shared.resources.timeline_control_not_installed_other_channel
import app.tenshu.timeline.shared.resources.timeline_control_not_installed_unpublished
import app.tenshu.timeline.shared.resources.timeline_control_get_action
import app.tenshu.timeline.shared.resources.timeline_control_not_set_up
import app.tenshu.timeline.shared.resources.timeline_control_open_action
import app.tenshu.timeline.shared.resources.timeline_skew_update_both
import app.tenshu.timeline.shared.resources.timeline_fenced_update_unknown
import app.tenshu.timeline.shared.resources.timeline_fenced_update_both
import app.tenshu.timeline.shared.resources.timeline_fenced_update_this_app
import app.tenshu.timeline.shared.resources.timeline_fenced_update_control
import app.tenshu.timeline.shared.resources.timeline_skew_update_control
import app.tenshu.timeline.shared.resources.timeline_skew_update_this_app
import app.tenshu.timeline.shared.resources.timeline_nav_activities
import app.tenshu.timeline.shared.resources.timeline_nav_home
import app.tenshu.timeline.shared.resources.timeline_nav_places
import app.tenshu.timeline.shared.resources.timeline_settings
import app.tenshu.timeline.ui.screens.home.HomeTab
import app.tenshu.ui.layout.AdaptiveNavItem
import app.tenshu.ui.layout.AdaptiveNavScaffold
import app.tenshu.timeline.ui.components.RealmStorageBannerHost
import app.tenshu.timeline.ui.components.rememberRealmStorageState
import org.tenshu.storage.common.RealmStorageState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import app.tenshu.ui.feedback.AppControlUnavailableWall
import app.tenshu.ui.feedback.AppControlCheckingGate
import app.tenshu.ui.feedback.ErrorBanner
import app.tenshu.ui.feedback.controlBannerIcon
import app.tenshu.ui.feedback.ControlBannerAction
import app.tenshu.ui.feedback.rememberControlBannerAction
import app.tenshu.ui.storage.ControlInstallRoute
import app.tenshu.ui.storage.rememberControlInstallRoute
import app.tenshu.timeline.ui.navigation.Destination
import app.tenshu.timeline.ui.screens.home.TimelineHomeScreen
import app.tenshu.timeline.ui.screens.home.TimelineHomeViewModel
import app.tenshu.timeline.ui.screens.moment.MomentDetailScreen
import app.tenshu.timeline.ui.screens.moment.MomentDetailViewModel
import app.tenshu.timeline.ui.screens.place.PlaceDetailScreen
import app.tenshu.timeline.ui.screens.place.PlaceDetailViewModel
import app.tenshu.timeline.ui.screens.route.MomentRouteMapScreen
import app.tenshu.timeline.ui.screens.route.TripRouteMapScreen
import app.tenshu.timeline.ui.screens.settings.SettingsScreen
import app.tenshu.timeline.ui.screens.trip.TripDetailScreen
import app.tenshu.timeline.ui.screens.trip.TripDetailViewModel
import app.tenshu.ui.navigation.enterFromAnotherApp
import app.tenshu.ui.navigation.popOrLeaveApp
import app.tenshu.ui.navigation.rememberAppExit
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Timeline app root (Ph5+Ph6, docs/proposals/timeline-app.md). The shared `AdaptiveNavScaffold`
 * chrome over a `KmpNavHost` on a plain back stack (`mutableStateListOf(Home)`); Home is the
 * single top-level content destination and trip/moment/place push detail destinations. Both
 * shells call this inside `TenshuTheme { App() }`.
 *
 * The chrome landed 2026-08-10: Timeline was the last first-party Compose app still rendering a
 * phone layout in a desktop window — one in-screen tab row stretched across the whole width, no
 * size-class response, no reading-width cap. `AdaptiveNavScaffold` is what Drive, Control, Fit,
 * Home, Contacts and Mia already use, so this is Timeline joining the shared shell rather than a
 * layout invented for it: bottom bar on a phone, rail at tablet width, permanent drawer on a
 * desktop. `TimelineShellRenderTest` renders all three so they can be looked at.
 */
@Composable
fun App(
    repository: TimelineRepository = koinInject(),
    /**
     * Rows another app asked Timeline to show — `control-cross-app-open.md` TT-2b.
     *
     * ⚠⚠ A `Flow`: a cold-start target arrives in the launch `Intent`, read before this composition
     * exists. ⚠ Empty by default, so desktop and tests are unchanged.
     */
    openTargets: Flow<DataId> = emptyFlow(),
) {
    // Registration must not wait for a screen to issue an operation (agent.md §7): resolving the
    // client opens the session, and the client module announces on the scope it was wired with.
    AnnounceAtLaunch { GlobalContext.get().get<TenshuClient>() }
    val backStack = remember { mutableStateListOf<Destination>(Destination.Home) }
    fun push(destination: Destination) = backStack.add(destination)
    // ⚠⚠ The guard is not defensive tidiness — `NavDisplay` requires a non-empty back stack and
    // throws on one — and since CO-D11 a cross-app open leaves exactly one entry that is NOT Home,
    // so a detail screen's back arrow can now reach the bottom. ⚠ Leaving the app is the right
    // answer there: the user arrived from another app, and back means take them back to it.
    // [rememberAppExit] is `null` where the platform has no such move, and this stays a no-op.
    val exitApp = rememberAppExit()
    fun pop() = backStack.popOrLeaveApp(exitApp)
    fun selectTop(destination: Destination.TopLevel) {
        if (backStack.lastOrNull() != destination) {
            backStack.clear()
            backStack.add(destination)
        }
    }

    // Control-reachability banner (fit-app.md §9.14 recipe): every screen reads over IPC, so
    // this is shell-level — one banner for the whole app; it clears itself when the
    // repository's recovery probe sees Control return.
    val controlReachable by repository.controlReachable().collectAsState()
    // SK-1b: decides the banner's words, not whether it shows.
    val unreachableReason by repository.controlUnreachableReason().collectAsState()

    // The realm-wide metric/imperial answer, provided once for the whole tree (fit-app.md §18.13).
    // Shell-level for the same reason the banner is: every screen that renders a distance needs it
    // and none of them owns it.
    val units by repository.units().collectAsState(MeasurementSystem.METRIC)

    // T-P3: how any composable gets pixels for a photo. Shell-level for the same reason the two
    // above are — every surface that draws a photo needs it and none of them owns it. The frames
    // themselves decide *when* to call it, which is what keeps a scroll from fetching a blob per
    // row it flies past.
    //
    // Two fidelities, because they cost different amounts: `cached` answers from the two cheap
    // stored rungs (at most 256 px on a device that did not upload the photo), while `hero` also
    // pulls the ~139 KB XLARGE rung so one deliberately-opened photo is not an upscale. Which
    // surfaces may spend which is on `PhotoByteLoaders`.
    val photoBytes: PhotoByteLoaders = remember(repository) {
        PhotoByteLoaders(
            cached = { uri, width, height -> repository.photoBytes(uri, width, height) },
            hero = { uri, width, height ->
                repository.photoBytes(uri, width, height, heroFidelity = true)
            },
        )
    }

    // Which of Home's three surfaces is showing. Shell state rather than a nav destination or
    // the view model's own `tab`, and both halves of that are deliberate:
    //  - not a destination, because `KmpNavHost` scopes a `ViewModelStore` per back-stack key, so
    //    three destinations would build three `TimelineHomeViewModel`s (× five repository flows);
    //  - not `TimelineHomeViewModel.uiState.tab`, because the chrome lives *outside* the nav host
    //    and would therefore resolve a different instance than the screen does — the VM's tab
    //    stays for `timeline-ios`, whose SwiftUI shell drives its own segmented picker from it.
    //
    // ⚠⚠ **The `MutableState` itself is what travels into the nav host, never `tab.value`.** An
    // entry's content lambda is built once per back-stack key and *cached* on Android
    // (`NavDisplay`), so a captured value freezes at the moment the entry was created: the nav
    // chrome moved and the content pane did not, on every tap, forever. Passing the state object
    // keeps the read inside the entry's own composition scope, where the snapshot system can
    // invalidate it. See `KmpNavHost` — desktop re-invokes the provider each recomposition, so
    // this class of bug is invisible on desktop and only ever shows up on a device.
    val tab = remember { mutableStateOf(HomeTab.Home) }

    val current = backStack.last()
    val navItems = HomeTab.entries.map { entry ->
        AdaptiveNavItem(
            key = entry,
            icon = entry.navIcon(),
            label = stringResource(entry.labelResource()),
            selected = current == Destination.Home && tab.value == entry,
            onClick = { selectTop(Destination.Home); tab.value = entry },
        )
    } + AdaptiveNavItem(
        key = Destination.Settings,
        icon = Icons.Outlined.Settings,
        label = stringResource(Res.string.timeline_settings),
        selected = current == Destination.Settings,
        onClick = { selectTop(Destination.Settings) },
    )

    ProvideMeasurementSystem(units) {
    // Cross-app open (`control-cross-app-open.md` TT-2a): tapping a photo opens it in the app that
    // owns the file. ⚠ `getOrNull` — a shell with no client stack (previews, the mock feed) provides
    // nothing, and frames stay inert rather than offering a tap that cannot be honoured.
    val targetOpener: TargetOpener? = remember { GlobalContext.get().getOrNull() }
    val openerScope = rememberCoroutineScope()
    // ⚠⚠ A tap that is refused must SAY so (CO-D7). Timeline has no chat to answer in, so it
    // borrows the banner it already shows for an unreachable Control.
    var openFailure by remember { mutableStateOf<String?>(null) }
    val photoOpener: PhotoOpener? = remember(targetOpener) {
        targetOpener?.let { opener ->
            PhotoOpener { photo ->
                val path = photo.uri ?: return@PhotoOpener
                openerScope.launch {
                    // ⚠⚠ Timeline names no app and declares no `<queries>` entry: it hands Control
                    // the FILE's id and Control decides which app shows it. That is CO-D1 holding
                    // at a **second** caller, which is the thing TT-2 exists to prove.
                    val target = DataId.StringDataId.create(
                        context = null,
                        type = File.ID,
                        keyValue = path,
                    )
                    openFailure = when (val result = opener.open(target)) {
                        // ⚠ Silent on success: the app coming to the front IS the acknowledgement.
                        OpenTargetResult.Delivered, OpenTargetResult.LaunchRequested -> null
                        is OpenTargetResult.Refused -> sentenceForOpenRefusal(result.reason)
                        // ⚠ Distinct from a refusal: the proxy never answered at all, and the
                        // shell's own reachability banner may not have noticed yet.
                        OpenTargetResult.Unreachable -> CANNOT_REACH_CONTROL
                    }
                }
            }
        }
    }

    // Cross-app open, receiving half (`control-cross-app-open.md` TT-2b). ⭐ Timeline is the first
    // app on BOTH sides of this contract — it opens Drive for a photo and is opened for a place or
    // a moment — which is the clearest evidence the design is not caller-shaped.
    //
    // ⚠ A target that is neither is ignored rather than guessed at: Control routes by type, so
    // receiving something else is a table bug, and opening a screen anyway would hide it.
    //
    // ⚠⚠ **`enterFromAnotherApp` REPLACES the stack, it does not push onto it** (CO-D11) — the same
    // rule Drive's receiving half obeys, for the same reason: this is an entry point from outside,
    // not a step in Timeline's own history, so back must leave rather than descend into Home.
    LaunchedEffect(openTargets) {
        openTargets.collect { target ->
            destinationForTarget(target)?.let(backStack::enterFromAnotherApp)
        }
    }

    CompositionLocalProvider(
        LocalPhotoBytes provides photoBytes,
        LocalPhotoOpener provides photoOpener,
    ) {
    // ⚠⚠ **The wall replaces the app, it does not sit on top of it** (ADR 0025 §Decision 1,
    // amended). With Control uninstalled this shell rendered its banner above "Your timeline starts
    // here — Import from Google Takeout": an empty-library claim that is false, over a button that
    // cannot work, on a device with no realm at all. The rest of the UI was not a distraction from
    // the message, it was contradicting it.
    //
    // ⚠ `NotInstalled` and `NotSetUp` only. A transport outage is transient and self-recovering,
    // and a full-screen wall that slams down on a three-second blip is worse than any banner — that
    // case keeps the strip below. The rule is the reason, never the platform.
    // ⚠ Hoisted out of the host so the shell can tell whether ANY banner is its top element — the
    // inset below depends on it, and while this lived inside the host the storage banner drew under
    // the status bar (the defect this pass started from).
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

    // ⚠⚠ **Installed, answering, and no realm** (ADR 0025 §Decision 8). Nothing is unreachable —
    // the proxy serves every read, empty — so without this the shell rendered its empty states
    // over a library that does not exist. Permanent until the user acts, so it walls like
    // `NotInstalled`; the offer is to *open* Control, never to get it.
    // ⚠⚠ ADR 0025 §8.6 (Codex P1 on 5f82133bc): Control is answering, and whether this device has a
    // realm is not known yet. Non-interactive — no shell, nothing that writes — until the realm
    // read answers (normally ~1 s); blank for 300 ms, a spinner, a retry wall past 20 s.
    if (unreachableReason == UnreachableReason.Checking) {
        AppControlCheckingGate(onRetry = repository::recheckControl)
        return@CompositionLocalProvider
    }
    if (unreachableReason == UnreachableReason.NotSetUp) {
        val wallAction = rememberControlBannerAction(unreachableReason)
        AppControlUnavailableWall(
            title = stringResource(Res.string.timeline_control_wall_title),
            body = stringResource(Res.string.timeline_control_not_set_up),
            icon = controlBannerIcon(unreachableReason),
            actionLabel = wallAction?.let { stringResource(Res.string.timeline_control_open_action) },
            onAction = wallAction?.onClick,
        )
        return@CompositionLocalProvider
    }
    if (unreachableReason == UnreachableReason.NotInstalled) {
        val installRoute = rememberControlInstallRoute()
        val action = rememberControlBannerAction(unreachableReason, installRoute)
        AppControlUnavailableWall(
            title = stringResource(Res.string.timeline_control_wall_title),
            body = when (installRoute) {
                is ControlInstallRoute.Store -> stringResource(Res.string.timeline_control_not_installed)
                ControlInstallRoute.NotPublishedYet ->
                    stringResource(Res.string.timeline_control_not_installed_unpublished)
                ControlInstallRoute.SameChannelAsThisApp ->
                    stringResource(Res.string.timeline_control_not_installed_other_channel)
            },
            actionLabel = action?.let { stringResource(Res.string.timeline_control_get_action) },
            onAction = action?.onClick,
        )
        return@CompositionLocalProvider
    }

    AdaptiveNavScaffold(
        navItems = navItems,
        showNav = current is Destination.TopLevel,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // ⚠⚠ `AdaptiveNavScaffold` passes `contentWindowInsets = 0`, so anything drawn at
                // the top of its content owns the status-bar inset itself. Without this the banner
                // drew UNDER the clock and battery icons — the defect that prompted the redesign,
                // and the reason a banner belongs in a `Scaffold` topBar slot wherever there is one.
                .then(
                    if (syncProblem != SuiteSyncProblem.None ||
                        openFailure != null
                    ) {
                        Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    } else {
                        Modifier
                    }
                )
        ) {
            // WARN: gated on the REASON, not `controlReachable == false`. A fenced device
            // answers every read - its state stays true - so the old condition could never
            // fire for the one case where the app is silently receiving nothing (SK-3).
            if (controlReason != null) {
                // ⚠⚠ The app to name is frequently NOT Timeline: on Android and desktop one
                // Control-owned proxy serves every app, so a version disagreement is usually
                // Control's to fix and telling the user to update Timeline is a dead end.
                // ⚠⚠ The route decides the *words* as well as the button: a build whose Control is not
                // in the store yet must NOT be told "install it the way you installed this app", because
                // that way does not work yet (ADR 0025 §Decision 6).
                val installRoute = rememberControlInstallRoute()
                val action = rememberControlBannerAction(controlReason, installRoute)
                ErrorBanner(
                    text = when (val r = controlReason) {
                        is UnreachableReason.Skew -> when (r.update) {
                            UpdateTarget.CONTROL -> stringResource(Res.string.timeline_skew_update_control)
                            UpdateTarget.THIS_APP -> stringResource(Res.string.timeline_skew_update_this_app)
                            UpdateTarget.BOTH -> stringResource(Res.string.timeline_skew_update_both)
                        }
                        is UnreachableReason.Fenced -> when (r.update) {
                            UpdateTarget.CONTROL -> stringResource(Res.string.timeline_fenced_update_control)
                            UpdateTarget.THIS_APP -> stringResource(Res.string.timeline_fenced_update_this_app)
                            UpdateTarget.BOTH -> stringResource(Res.string.timeline_fenced_update_both)
                            // No host declared itself: name nothing rather than guess wrong on a platform.
                            null -> stringResource(Res.string.timeline_fenced_update_unknown)
                        }
                        // ⚠ Normally behind the wall (ADR 0025 §Decision 8); worded anyway, never as an outage.
                        // Never reaches a banner — the root shows AppControlCheckingGate first (ADR 0025 §8.6).
                        UnreachableReason.Checking -> appControlCheckingLabel()
                        UnreachableReason.NotSetUp -> stringResource(Res.string.timeline_control_not_set_up)
                        UnreachableReason.NotInstalled -> when (installRoute) {
                            is ControlInstallRoute.Store -> stringResource(Res.string.timeline_control_not_installed)
                            ControlInstallRoute.NotPublishedYet ->
                                stringResource(Res.string.timeline_control_not_installed_unpublished)
                            ControlInstallRoute.SameChannelAsThisApp ->
                                stringResource(Res.string.timeline_control_not_installed_other_channel)
                        }
                        UnreachableReason.Transport, null -> stringResource(Res.string.timeline_control_unreachable_text)
                    },
                    icon = controlBannerIcon(controlReason),
                    actionLabel = action?.let {
                        when (it.kind) {
                            ControlBannerAction.Kind.OPEN_CONTROL ->
                                stringResource(Res.string.timeline_control_open_action)
                            ControlBannerAction.Kind.GET_CONTROL ->
                                stringResource(Res.string.timeline_control_get_action)
                            ControlBannerAction.Kind.UPDATE ->
                                stringResource(Res.string.timeline_update_action)
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
            // ⚠ Below the reachability banner, not instead of it: "Control is gone" and "that
            // particular row has no app" are different facts and a user can be told both.
            openFailure?.let { ErrorBanner(it) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                NavContent(backStack, tab, ::pop, ::push)
                // Over the screens, never beside them; a problem strip, or a screen that is not a tab, hides it.
                SyncActivityCapsule(activity = syncActivity, hidden = syncProblem != SuiteSyncProblem.None || current !is Destination.TopLevel)
            }
        }
    }
    }
    }
}

/** The nav-chrome glyph for one of Home's surfaces. */
private fun HomeTab.navIcon() = when (this) {
    HomeTab.Home -> Icons.AutoMirrored.Outlined.ViewList
    HomeTab.Places -> Icons.Outlined.Place
    HomeTab.Activities -> Icons.AutoMirrored.Outlined.DirectionsRun
}

private fun HomeTab.labelResource() = when (this) {
    HomeTab.Home -> Res.string.timeline_nav_home
    HomeTab.Places -> Res.string.timeline_nav_places
    HomeTab.Activities -> Res.string.timeline_nav_activities
}

/** The nav host, extracted so [App] can stack the reachability banner above it. */
@Composable
private fun NavContent(
    backStack: androidx.compose.runtime.snapshots.SnapshotStateList<Destination>,
    // ⚠⚠ The state object, not its value — see `App`. Read `.value` INSIDE an entry lambda, never
    // at this call site, or Android's cached entry freezes on the value it was built with.
    tab: androidx.compose.runtime.State<HomeTab>,
    pop: () -> Unit,
    push: (Destination) -> Unit,
) {
    KmpNavHost(
        backStack = backStack,
        onBack = pop,
        entryProvider = kmpEntryProvider {
            entry<Destination.Home> {
                TimelineHomeScreen(
                    viewModel = koinViewModel<TimelineHomeViewModel>(),
                    // Read here, inside the entry, so the snapshot system subscribes this scope.
                    tab = tab.value,
                    onOpenMoment = { push(Destination.MomentDetail(it)) },
                    onOpenTrip = { push(Destination.TripDetail(it)) },
                    onOpenPlace = { push(Destination.PlaceDetail(it)) },
                )
            }
            entry<Destination.Settings> {
                // No back affordance: Settings is a nav destination now, so `selectTop` left it
                // alone at the bottom of the stack and a chevron would have nothing to pop to.
                SettingsScreen(onBack = null, onLicences = { push(Destination.Licences) })
            }
            entry<Destination.Licences> {
                // The basemap's credits, plus Photon for the place names (docs/oss-licences.md §3).
                OpenSourceLicencesScreen(
                    onBack = { pop() },
                    dataAttributions = DataAttributions.Basemap + DataAttributions.Photon,
                )
            }
            entry<Destination.TripDetail> { key ->
                TripDetailScreen(
                    viewModel = koinViewModel<TripDetailViewModel>(
                        key = key.tripId,
                        parameters = { parametersOf(key.tripId) },
                    ),
                    onBack = pop,
                    onOpenMoment = { push(Destination.MomentDetail(it)) },
                    onOpenTrip = { push(Destination.TripDetail(it)) },
                    onOpenRouteMap = { push(Destination.TripRoute(key.tripId)) },
                )
            }
            entry<Destination.MomentDetail> { key ->
                MomentDetailScreen(
                    viewModel = koinViewModel<MomentDetailViewModel>(
                        key = key.momentId,
                        parameters = { parametersOf(key.momentId) },
                    ),
                    onBack = pop,
                    onOpenPlace = { push(Destination.PlaceDetail(it)) },
                    onOpenRouteMap = { push(Destination.MomentRoute(key.momentId)) },
                )
            }
            // The full-screen route maps reuse the detail view models under the SAME Koin key, so
            // opening the map re-binds to the already-loaded instance rather than re-reading the
            // route — and the geometry never has to ride the (serializable) destination.
            entry<Destination.MomentRoute> { key ->
                MomentRouteMapScreen(
                    viewModel = koinViewModel<MomentDetailViewModel>(
                        key = key.momentId,
                        parameters = { parametersOf(key.momentId) },
                    ),
                    onBack = pop,
                )
            }
            entry<Destination.TripRoute> { key ->
                TripRouteMapScreen(
                    viewModel = koinViewModel<TripDetailViewModel>(
                        key = key.tripId,
                        parameters = { parametersOf(key.tripId) },
                    ),
                    onBack = pop,
                )
            }
            entry<Destination.PlaceDetail> { key ->
                PlaceDetailScreen(
                    viewModel = koinViewModel<PlaceDetailViewModel>(
                        key = key.placeId,
                        parameters = { parametersOf(key.placeId) },
                    ),
                    onBack = pop,
                )
            }
        },
    )
}

/**
 * What to say when Control refuses to open a row (CO-D9).
 *
 * ⚠⚠ **The reason token, never `OpenTargetUnavailable.message`** — that is a developer string
 * naming an `appId` the user has never seen and cannot act on. ⚠ Names no app for the same reason
 * Mia's copy does not: which app opens a file is Control's resolution, and leaking it here would
 * put a routing decision in the one place that must not hold one.
 */
private fun sentenceForOpenRefusal(reason: String): String = when (reason) {
    OpenTargetUnavailable.Reasons.NO_HANDLER -> "Nothing on this device opens that yet."
    OpenTargetUnavailable.Reasons.NOT_INSTALLED_HERE ->
        "The app that opens photos isn't installed on this device."
    OpenTargetUnavailable.Reasons.LAUNCH_FAILED -> "That app didn't open. Try again in a moment."
    OpenTargetUnavailable.Reasons.NOT_PERMITTED -> "Timeline isn't allowed to open that."
    OpenTargetUnavailable.Reasons.NO_DELIVERY -> "Opening apps isn't supported here yet."
    // ⚠ A newer proxy naming a reason this build has never heard of. Vague beats silent.
    else -> "That couldn't be opened."
}

private const val CANNOT_REACH_CONTROL = "Can't reach Control right now."
