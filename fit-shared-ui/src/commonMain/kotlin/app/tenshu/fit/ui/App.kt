package app.tenshu.fit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import app.tenshu.ui.launch.AnnounceAtLaunch
import org.koin.core.context.GlobalContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.common.TenshuClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import app.tenshu.fit.data.RecordingEngine
import app.tenshu.fit.data.RecordingState
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_browse_title
import app.tenshu.fit.shared.resources.fit_control_get_action
import app.tenshu.fit.shared.resources.fit_control_not_set_up
import app.tenshu.fit.shared.resources.fit_control_open_action
import app.tenshu.fit.shared.resources.fit_control_not_installed
import app.tenshu.fit.shared.resources.fit_control_not_installed_other_channel
import app.tenshu.fit.shared.resources.fit_control_not_installed_unpublished
import app.tenshu.fit.shared.resources.fit_control_wall_title
import app.tenshu.fit.shared.resources.fit_history_title
import app.tenshu.fit.shared.resources.fit_nav_home
import app.tenshu.fit.shared.resources.fit_profile_title
import app.tenshu.fit.shared.resources.fit_record_title
import app.tenshu.fit.ui.components.ControlUnreachableBanner
import app.tenshu.fit.ui.components.RealmStorageBannerHost
import app.tenshu.reachability.UnreachableReason
import app.tenshu.ui.feedback.AppControlUnavailableWall
import app.tenshu.ui.feedback.AppControlCheckingGate
import app.tenshu.ui.feedback.controlBannerIcon
import app.tenshu.ui.feedback.rememberControlBannerAction
import app.tenshu.ui.storage.ControlInstallRoute
import app.tenshu.ui.storage.rememberControlInstallRoute
import app.tenshu.fit.ui.components.rememberRealmStorageState
import org.tenshu.storage.common.RealmStorageState
import app.tenshu.fit.ui.components.FitIcons
import app.tenshu.fit.ui.navigation.Destination
import app.tenshu.fit.ui.screens.browse.BrowseScreen
import app.tenshu.fit.ui.screens.browse.BrowseViewModel
import app.tenshu.fit.ui.screens.category.CategoryDetailScreen
import app.tenshu.fit.ui.screens.category.CategoryDetailViewModel
import app.tenshu.fit.ui.screens.history.HistoryScreen
import app.tenshu.fit.ui.screens.history.HistoryViewModel
import app.tenshu.fit.ui.screens.home.HomeScreen
import app.tenshu.fit.ui.screens.home.HomeViewModel
import app.tenshu.fit.ui.screens.medical.MedicalScreen
import app.tenshu.fit.ui.screens.medical.MedicalViewModel
import app.tenshu.fit.ui.screens.onboarding.OnboardingScreen
import app.tenshu.fit.ui.screens.onboarding.OnboardingViewModel
import app.tenshu.fit.ui.screens.datasource.BatchImportScreen
import app.tenshu.fit.ui.screens.datasource.BatchImportViewModel
import app.tenshu.fit.ui.screens.datasource.DataSourceDetailScreen
import app.tenshu.fit.ui.screens.datasource.DataSourceDetailViewModel
import app.tenshu.fit.ui.screens.profile.ProfileScreen
import app.tenshu.fit.ui.screens.sharing.SharingWizardScreen
import app.tenshu.fit.ui.screens.sharing.SharingWizardViewModel
import app.tenshu.fit.ui.screens.profile.ProfileViewModel
import app.tenshu.fit.ui.screens.record.RecordLiveScreen
import app.tenshu.fit.ui.screens.record.RecordPreScreen
import app.tenshu.fit.ui.screens.record.RecordSummaryScreen
import app.tenshu.fit.ui.screens.record.RecordSurfaceRequests
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import app.tenshu.fit.ui.screens.record.RecordViewModel
import app.tenshu.fit.ui.screens.workout.WorkoutDetailScreen
import app.tenshu.fit.ui.screens.workout.WorkoutDetailViewModel
import app.tenshu.fit.ui.screens.workout.WorkoutRouteMapScreen
import app.tenshu.ui.layout.AdaptiveNavItem
import app.tenshu.fit.ui.components.FirstDetectionPrompt
import app.tenshu.ui.layout.AdaptiveNavScaffold
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import app.tenshu.ui.legal.DataAttributions
import app.tenshu.ui.legal.OpenSourceLicencesScreen
import androidx.compose.foundation.layout.consumeWindowInsets

/** How long a notification tap waits for a journal-restored session to appear before giving up. */
private const val RECORD_SURFACE_SETTLE_MS = 3_000L

/**
 * Fit app root (docs/proposals/fit-app.md). The onboarding gate (D5) sits above the
 * main shell: while `onboarded` is false the full-screen onboarding flow owns the surface;
 * once it flips true the `AdaptiveNavScaffold` + `KmpNavHost` shell mounts. Both app shells
 * call this inside `TenshuTheme { App() }`.
 */
@Composable
fun App(
    appViewModel: AppViewModel = koinViewModel(),
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
    val onboarded by appViewModel.onboarded.collectAsState()
    // ⚠⚠ ABOVE the onboarding gate, not inside the shell (ADR 0025 §8.6): a device with no realm is
    // precisely a device that has not onboarded, and onboarding writes the owner's profile — so a
    // wall only around the shell would leave onboarding writing into a store with no realm.
    val unreachableReason by appViewModel.unreachableReason.collectAsState()
    // ⚠⚠ ADR 0025 §8.6: Control is answering, and whether this device has a
    // realm is not known yet. Non-interactive — no shell, nothing that writes — until the realm
    // read answers (normally ~1 s); blank for 300 ms, a spinner, a retry wall past 20 s.
    if (unreachableReason == UnreachableReason.Checking) {
        AppControlCheckingGate(onRetry = appViewModel::recheckControl)
        return
    }
    if (unreachableReason == UnreachableReason.NotSetUp) {
        val wallAction = rememberControlBannerAction(unreachableReason)
        AppControlUnavailableWall(
            title = stringResource(Res.string.fit_control_wall_title),
            body = stringResource(Res.string.fit_control_not_set_up),
            icon = controlBannerIcon(unreachableReason),
            actionLabel = wallAction?.let { stringResource(Res.string.fit_control_open_action) },
            onAction = wallAction?.onClick,
        )
        return
    }
    when (onboarded) {
        null -> Unit
        false -> OnboardingScreen(koinViewModel<OnboardingViewModel>())
        true -> MainShell(appViewModel, openTargets)
    }
}

@Composable
private fun MainShell(appViewModel: AppViewModel, openTargets: Flow<DataId>) {
    val controlReachable by appViewModel.controlReachable.collectAsState()
    // Decides the banner's words, not whether it shows — see [ControlUnreachableBanner].
    val unreachableReason by appViewModel.unreachableReason.collectAsState()
    // D1's one-time introduction. ⚠ Inside the shell, not beside the onboarding gate: it fires at
    // the first would-be *detection*, which is necessarily later than setup — there is no trace to
    // point at during onboarding, and an abstract version of this question is one people click past.
    val firstDetection = koinViewModel<FirstDetectionViewModel>()
    val detectionCandidate by firstDetection.candidate.collectAsState()
    val backStack = remember { mutableStateListOf<Destination>(Destination.Home) }
    fun push(destination: Destination) { backStack.add(destination) }
    fun pop() { if (backStack.size > 1) backStack.removeLastOrNull() }

    // Cross-app open, receiving half (`control-cross-app-open.md` TT-2b).
    // ⚠ A target of another type is ignored rather than guessed at: Control routes by type, so
    // receiving one is a table bug, and opening a screen anyway would hide it.
    LaunchedEffect(openTargets) {
        openTargets.collect { target -> destinationForTarget(target)?.let(::push) }
    }

    fun selectTop(destination: Destination.TopLevel) {
        if (backStack.lastOrNull() != destination) {
            backStack.clear()
            backStack.add(destination)
        }
    }

    // fit-app.md §13.2: a Reviewing draft owns the surface. In-app Stop replaces the live
    // screen with the review screen; the notification's Stop action surfaces review wherever the
    // user is (the draft lives on the engine singleton, so navigation is all this takes).
    val recordingEngine = koinInject<RecordingEngine>()
    LaunchedEffect(recordingEngine) {
        recordingEngine.state().collect { s ->
            if (s is RecordingState.Reviewing && Destination.RecordSummary !in backStack) {
                if (backStack.lastOrNull() == Destination.RecordLive) backStack.removeLastOrNull()
                backStack.add(Destination.RecordSummary)
            }
        }
    }
    // §13.1: the recording notification's tap — surface the in-flight session.
    LaunchedEffect(recordingEngine) {
        RecordSurfaceRequests.pending.collect { pending ->
            if (!pending) return@collect
            RecordSurfaceRequests.consume()
            // ⚠ Wait for the state to settle rather than sampling it. On the cold start that
            // follows an OEM kill (§13.5e) the engine restores its session from the journal
            // off-thread, so the instant the tap arrives the state is still Idle — sampling it
            // once would route the user to Home while their recording quietly resumes behind it.
            // A stale notification with no session really is Idle: it waits out the timeout and
            // does nothing.
            val settled = withTimeoutOrNull(RECORD_SURFACE_SETTLE_MS) {
                recordingEngine.state().first { it !is RecordingState.Idle }
            }
            when (settled) {
                is RecordingState.Active -> {
                    backStack.remove(Destination.RecordLive)
                    backStack.add(Destination.RecordLive)
                }
                is RecordingState.Reviewing -> {
                    backStack.remove(Destination.RecordSummary)
                    backStack.add(Destination.RecordSummary)
                }
                else -> Unit
            }
        }
    }

    val topLevel = remember {
        listOf(
            Destination.Home,
            Destination.History,
            Destination.Record,
            Destination.Browse,
            Destination.Profile,
        )
    }
    val current = backStack.last()
    val navItems = topLevel.map { destination ->
        AdaptiveNavItem(
            key = destination,
            icon = navIcon(destination),
            label = navLabel(destination),
            selected = current == destination,
            emphasized = destination == Destination.Record,
            onClick = { selectTop(destination) },
        )
    }

    // ⚠⚠ **The wall replaces the app, it does not sit on top of it** (ADR 0025 §Decision 1,
    // amended). Fit has no store of its own, so without Control every tab is empty and the Record
    // tab's own gate (§11.7) is one of several surfaces each explaining the same absence
    // separately. One screen says it once.
    //
    // ⚠ `NotInstalled` and `NotSetUp` only: a transport outage is transient and self-recovering,
    // and a wall that slams down on a three-second blip is worse than any banner.
    // ⚠⚠ **Installed, answering, and no realm** (ADR 0025 §Decision 8). Nothing is unreachable —
    // the proxy serves every read, empty — so without this the shell renders its empty states
    // over a library that does not exist. Permanent until the user acts, so it walls like
    // `NotInstalled`; the offer is to *open* Control, never to get it.
    // `Checking` and `NotSetUp` are handled in [App], above the onboarding gate (ADR 0025 §8.6).
    if (unreachableReason == UnreachableReason.NotInstalled) {
        val wallRoute = rememberControlInstallRoute()
        val wallAction = rememberControlBannerAction(unreachableReason, wallRoute)
        AppControlUnavailableWall(
            title = stringResource(Res.string.fit_control_wall_title),
            body = when (wallRoute) {
                is ControlInstallRoute.Store -> stringResource(Res.string.fit_control_not_installed)
                ControlInstallRoute.NotPublishedYet ->
                    stringResource(Res.string.fit_control_not_installed_unpublished)
                ControlInstallRoute.SameChannelAsThisApp ->
                    stringResource(Res.string.fit_control_not_installed_other_channel)
            },
            actionLabel = wallAction?.let { stringResource(Res.string.fit_control_get_action) },
            onAction = wallAction?.onClick,
        )
        return
    }

    AdaptiveNavScaffold(
        navItems = navItems,
        showNav = current is Destination.TopLevel,
        // ⚠⚠ **Bottom only** (§18.15). Every screen carries a `FitTopBar`, and a `TopAppBar`
        // applies `TopAppBarDefaults.windowInsets` itself — so a shell that also supplied the top
        // would double-pad every screen in the app.
        //
        // ⚠ The **bottom** half stays, and is not symmetric with the top: on a tab the M3
        // `NavigationBar` already consumes it (the scaffold pads content by the bar's height), but
        // on a pushed screen there is no bottom chrome at all and nothing else would claim it.
        // Dropping it wholesale — Drive's posture — would need every Fit screen to host its own
        // `Scaffold` for the bottom inset, which buys nothing the shell is not already doing.
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom),
    ) {
        // ⚠⚠ The banner is the one thing above the screens, so it is the one thing with no bar to
        // own its inset. `windowInsetsPadding` both pads it below the status bar
        // AND consumes that inset for everything nested inside — which is exactly what keeps the
        // screen's own `FitTopBar` from adding a second status bar's worth beneath it. Applied
        // only when the banner is actually showing; otherwise the bar owns the inset as usual.
        // ⚠⚠ BOTH banners count: gated on one alone, the storage strip renders while
        // `controlReachable` is true and draws UNDERNEATH the status bar with the clock on top of
        // its text.
        val storageState = rememberRealmStorageState()
        val storageBannerVisible =
            storageState !is RealmStorageState.Ok && current != Destination.RecordLive
        // ⚠⚠ Gated on the REASON, not on `controlReachable == false`. A fenced device answers every
        // read, so `controlReachable` stays `true`; gated on it, the fence banner would render with
        // no inset — the same status-bar collision.
        val bannerVisible = unreachableReason != null && current != Destination.RecordLive
        Column(
            Modifier.fillMaxSize()
                .then(
                    if (bannerVisible || storageBannerVisible) {
                        Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    } else {
                        Modifier
                    },
                ),
        ) {
            // The Control-unreachable banner (§9.14): every screen reads over IPC, so this is
            // shell-level — one banner for the whole app. Suppressed on the always-dark
            // live-record surface; a failed save there has its own affordance (§9.13 dialogs).
            if (bannerVisible) {
                ControlUnreachableBanner(reason = unreachableReason)
            }
            // ⚠ Below the reachability banner, not instead of it: "Control is gone" and "the realm
            // cannot sync" are different facts and a user can be owed both. Suppressed on the
            // live-record surface for the same reason the banner above is — that screen is
            // always-dark and owns its own affordances.
            // ⚠⚠ And suppressed while Control is unreachable at all, reversing the rule above:
            // both facts are real, but the storage one is not actionable while the first is true —
            // its *Manage storage* button opens a Control that is not answering.
            if (storageBannerVisible && unreachableReason == null) {
                RealmStorageBannerHost(storageState)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                NavContent(backStack, ::pop, ::push, ::selectTop)
            }
        }
        // ⚠ Inside the scaffold, so it cannot appear over the live-record surface's own dialogs
        // or before the shell exists. `null` for every launch that has nothing to ask about —
        // which is most of them, and deliberately does not count as having asked.
        detectionCandidate?.let { candidate ->
            FirstDetectionPrompt(
                candidate = candidate,
                onAlways = firstDetection::logAutomatically,
                onOnce = firstDetection::justThisOne,
                onNever = firstDetection::notNow,
            )
        }
    }
}

/** The nav host, extracted so [MainShell] can stack the §9.14 banner above it. */
@Composable
private fun NavContent(
    backStack: SnapshotStateList<Destination>,
    pop: () -> Unit,
    push: (Destination) -> Unit,
    selectTop: (Destination.TopLevel) -> Unit,
) {
    KmpNavHost(
            backStack = backStack,
            onBack = pop,
            entryProvider = kmpEntryProvider {
                entry<Destination.Home> {
                    HomeScreen(
                        viewModel = koinViewModel<HomeViewModel>(),
                        onOpenWorkout = { push(Destination.WorkoutDetail(it)) },
                        onOpenHistory = { selectTop(Destination.History) },
                        onStartRecording = { selectTop(Destination.Record) },
                        onOpenBatchImport = { push(Destination.BatchImport) },
                        onOpenMetric = { metric -> push(Destination.CategoryDetail(CategoryId.Activity, metric)) },
                    )
                }
                entry<Destination.History> {
                    HistoryScreen(
                        viewModel = koinViewModel<HistoryViewModel>(),
                        onOpenWorkout = { push(Destination.WorkoutDetail(it)) },
                    )
                }
                entry<Destination.Record> {
                    RecordPreScreen(
                        viewModel = koinViewModel<RecordViewModel>(key = RECORD_VM_KEY),
                        onStart = { push(Destination.RecordLive) },
                        // A Reviewing draft parked behind a back-gesture: return to the review
                        // screen rather than offering Start (starting would clobber the draft).
                        onReview = { push(Destination.RecordSummary) },
                    )
                }
                entry<Destination.RecordLive> {
                    // Stop parks the draft; the MainShell effect replaces this screen with the
                    // review surface off the Reviewing state flip.
                    RecordLiveScreen(viewModel = koinViewModel<RecordViewModel>(key = RECORD_VM_KEY))
                }
                entry<Destination.RecordSummary> {
                    RecordSummaryScreen(
                        viewModel = koinViewModel<RecordViewModel>(key = RECORD_VM_KEY),
                        onFinished = { workoutId ->
                            pop()
                            push(Destination.WorkoutDetail(workoutId))
                        },
                        // Discard, or saved-locally (Control unreachable, journalled on-device —
                        // no readable detail yet): leave the review surface.
                        onClosed = pop,
                    )
                }
                entry<Destination.Browse> {
                    BrowseScreen(
                        viewModel = koinViewModel<BrowseViewModel>(),
                        onOpenCategory = { id ->
                            if (id == CategoryId.Medical) push(Destination.Medical)
                            else push(Destination.CategoryDetail(id))
                        },
                        onImport = { selectTop(Destination.Profile) },
                    )
                }
                entry<Destination.Profile> {
                    ProfileScreen(
                        viewModel = koinViewModel<ProfileViewModel>(),
                        // Height/weight are measurements, so their rows open the reading history
                        // rather than an editor (fit-app.md §18.2/§18.6).
                        onOpenBodyData = { push(Destination.CategoryDetail(CategoryId.Body)) },
                        onOpenSharingWizard = { push(Destination.SharingWizard) },
                        onOpenDataSource = { kind, deviceId ->
                            push(Destination.DataSourceDetail(kind, deviceId))
                        },
                        onOpenBatchImport = { push(Destination.BatchImport) },
                        onOpenLicences = { push(Destination.Licences) },
                    )
                }
                entry<Destination.Licences> {
                    // ⚠ The shell pads the bottom inset already (`contentWindowInsets` above); the
                    // screen's own bar owns the top one, as every Fit screen's does.
                    OpenSourceLicencesScreen(
                        onBack = { pop() },
                        dataAttributions = DataAttributions.Basemap,
                        modifier = Modifier.consumeWindowInsets(WindowInsets.systemBars.only(WindowInsetsSides.Bottom)),
                    )
                }
                entry<Destination.DataSourceDetail> { destination ->
                    DataSourceDetailScreen(
                        viewModel = koinViewModel<DataSourceDetailViewModel>(
                            // ⚠ The device is part of the key, not decoration: two phones in one
                            // realm mean two Health Connect rows, and a shared VM would show one
                            // device's figures on the other's screen.
                            key = "source_${destination.kind}_${destination.deviceId}",
                            parameters = { parametersOf(destination.kind, destination.deviceId) },
                        ),
                        onBack = pop,
                        onOpenSharingWizard = { push(Destination.SharingWizard) },
                    )
                }
                entry<Destination.BatchImport> {
                    BatchImportScreen(
                        viewModel = koinViewModel<BatchImportViewModel>(),
                        onBack = pop,
                    )
                }
                entry<Destination.SharingWizard> {
                    SharingWizardScreen(
                        viewModel = koinViewModel<SharingWizardViewModel>(),
                        onBack = pop,
                        // The export lives behind the Batch import screen, which owns the picker —
                        // so the terminal step goes there.
                        onOpenImport = {
                            pop()
                            push(Destination.BatchImport)
                        },
                    )
                }
                entry<Destination.WorkoutDetail> { destination ->
                    WorkoutDetailScreen(
                        viewModel = koinViewModel<WorkoutDetailViewModel>(
                            key = "workout_${destination.workoutId}",
                            parameters = { parametersOf(destination.workoutId) },
                        ),
                        onBack = pop,
                        onOpenRouteMap = { push(Destination.WorkoutRoute(destination.workoutId)) },
                    )
                }
                // The full-screen route map reuses the detail view model under the SAME Koin key,
                // so opening the map re-binds to the already-loaded instance rather than re-reading
                // the workout — and the track never has to ride the (serializable) destination.
                entry<Destination.WorkoutRoute> { destination ->
                    WorkoutRouteMapScreen(
                        viewModel = koinViewModel<WorkoutDetailViewModel>(
                            key = "workout_${destination.workoutId}",
                            parameters = { parametersOf(destination.workoutId) },
                        ),
                        onBack = pop,
                    )
                }
                entry<Destination.CategoryDetail> { destination ->
                    CategoryDetailScreen(
                        viewModel = koinViewModel<CategoryDetailViewModel>(
                            key = "category_${destination.categoryId}_${destination.metric}",
                            parameters = { parametersOf(destination.categoryId, destination.metric) },
                        ),
                        onBack = pop,
                    )
                }
                entry<Destination.Medical> {
                    MedicalScreen(viewModel = koinViewModel<MedicalViewModel>(), onBack = pop)
                }
            },
        )
}

private const val RECORD_VM_KEY = "fit_record"

private fun navIcon(destination: Destination.TopLevel): ImageVector = when (destination) {
    Destination.Home -> FitIcons.Home
    Destination.History -> FitIcons.History
    Destination.Record -> FitIcons.Plus
    Destination.Browse -> FitIcons.Browse
    Destination.Profile -> FitIcons.Profile
}

@Composable
private fun navLabel(destination: Destination.TopLevel): String = stringResource(
    when (destination) {
        Destination.Home -> Res.string.fit_nav_home
        Destination.History -> Res.string.fit_history_title
        Destination.Record -> Res.string.fit_record_title
        Destination.Browse -> Res.string.fit_browse_title
        Destination.Profile -> Res.string.fit_profile_title
    },
)
