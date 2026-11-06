package app.tenshu.calendar.ui

import app.tenshu.calendar.shared.resources.Res
import app.tenshu.calendar.shared.resources.calendar_control_fenced_update_app
import app.tenshu.calendar.shared.resources.calendar_control_fenced_update_both
import app.tenshu.calendar.shared.resources.calendar_control_fenced_update_control
import app.tenshu.calendar.shared.resources.calendar_control_fenced_update_unknown
import app.tenshu.calendar.shared.resources.calendar_control_get_action
import app.tenshu.calendar.shared.resources.calendar_control_not_installed
import app.tenshu.calendar.shared.resources.calendar_control_not_installed_other_channel
import app.tenshu.calendar.shared.resources.calendar_control_not_installed_unpublished
import app.tenshu.calendar.shared.resources.calendar_control_not_set_up
import app.tenshu.calendar.shared.resources.calendar_control_open_action
import app.tenshu.calendar.shared.resources.calendar_control_skew_update_app
import app.tenshu.calendar.shared.resources.calendar_control_skew_update_both
import app.tenshu.calendar.shared.resources.calendar_control_skew_update_control
import app.tenshu.calendar.shared.resources.calendar_control_unreachable
import app.tenshu.calendar.shared.resources.calendar_control_wall_title
import app.tenshu.calendar.shared.resources.calendar_update_action
import org.jetbrains.compose.resources.stringResource
import app.tenshu.ui.feedback.appControlCheckingLabel
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import app.tenshu.calendar.ui.components.RealmStorageBannerHost
import androidx.compose.runtime.collectAsState
import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.UpdateTarget
import app.tenshu.ui.feedback.ControlBannerAction
import app.tenshu.ui.feedback.AppControlUnavailableWall
import app.tenshu.ui.feedback.AppControlCheckingGate
import app.tenshu.ui.feedback.ErrorBanner
import app.tenshu.ui.feedback.controlBannerIcon
import app.tenshu.ui.feedback.rememberControlBannerAction
import app.tenshu.ui.storage.ControlInstallRoute
import app.tenshu.ui.storage.rememberControlInstallRoute
import app.tenshu.calendar.ui.components.rememberRealmStorageState
import androidx.compose.material3.VerticalDivider
import app.tenshu.ui.launch.AnnounceAtLaunch
import org.koin.core.context.GlobalContext
import org.tenshu.calendar.common.recurrence.OccurrenceId
import org.tenshu.core.common.util.bestEffort
import org.tenshu.core.entity.DataId
import org.tenshu.core.common.TenshuClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.ReminderDeepLink
import app.tenshu.calendar.data.resolveReminderLink
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
// ⚠ `InteropProblem` and `CalendarInteropViewModel` are used below without an import:
// `:calendar-shared-logic` declares them in `app.tenshu.calendar.ui`, this file's own package.
import app.tenshu.calendar.ui.interop.OpenedIcsFile
import app.tenshu.calendar.ui.navigation.Destination
import app.tenshu.calendar.ui.screens.CalendarCreateButton
import app.tenshu.calendar.ui.screens.CalendarSidebar
import app.tenshu.calendar.ui.screens.CalendarInteropSheet
import app.tenshu.calendar.ui.screens.CalendarSidebarWidth
import app.tenshu.calendar.ui.screens.CalendarScreen
import app.tenshu.calendar.ui.screens.CalendarsScreen
import app.tenshu.calendar.ui.screens.EventEditorScreen
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.LocalWindowWidthSizeClass
import app.tenshu.ui.layout.WindowWidthSizeClass
import app.tenshu.ui.layout.appWindowMargin
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence
import app.tenshu.ui.legal.OpenSourceLicencesListItem
import app.tenshu.ui.legal.OpenSourceLicencesScreen
import app.tenshu.ui.legal.AppPrivacyPolicyListItem

/**
 * Calendar's app root — the month/agenda surface plus the event editor.
 *
 * ⚠⚠ A real back stack (`:ui-core` `KmpNavHost`) rather than a `remember`ed destination switched on
 * with a `when`, even though the stack is never deeper than one. Depth is not what a nav host buys
 * (and reminder taps and TT-1c's cross-app opens are deep links anyway, collected a few lines
 * below). What it buys is a **saveable-state slot and a `ViewModelStore` per entry**, and the
 * absence of those is a bug with a name — a scrolled list comes back at row 0, because leaving the
 * `when` tears the screen out of composition and takes every `rememberSaveable` with it
 * (`docs/tech-debt.md`; pinned by `CalendarNavRoundTripTest`).
 *
 * ⚠ No `AdaptiveNavScaffold`, deliberately — see `navigation/Destination.kt`. One top-level
 * surface means there is nothing for a bar or a rail to switch between, and Maps is the sibling
 * that made the same call. The week grid is a *view* within the calendar destination, not a
 * destination of its own.
 */
@Composable
fun App(
    modifier: Modifier = Modifier,
    /**
     * The tap payload that launched the app, if a notification did.
     *
     * ⚠ A `String`, not a parsed type: it arrives as an `Intent` extra and this
     * signature is what an Android shell can hand over without learning
     * Calendar's vocabulary. Unrecognised or blank ⇒ an ordinary launch.
     */
    tapPayload: String? = null,
    /**
     * `.ics` files the host OS has handed over — a macOS document open, or the
     * command line on the platforms that pass one.
     *
     * ⚠⚠ A `Flow`, not a nullable value, and that is the whole design. The first
     * file arrives *before* this composition exists (a double-click is what launched
     * the app) and later ones arrive while it is running, possibly the same file
     * twice. A `String?` handles neither end: the early one is lost and the repeat
     * is swallowed as "no change".
     */
    openedIcsFiles: Flow<OpenedIcsFile> = emptyFlow(),
    /**
     * Rows another app asked Calendar to show — `control-cross-app-open.md` TT-1c's receiving half.
     *
     * ⚠⚠ A `Flow` for the same reason [openedIcsFiles] is, and the reason is sharper here: the
     * cold-start target arrives in the launch `Intent`, read before this composition exists. A
     * nullable parameter would drop precisely the case the whole mechanism was built for.
     *
     * ⚠ Empty by default, so a shell that does not wire it compiles and simply receives no
     * targets.
     */
    openTargets: Flow<DataId> = emptyFlow(),
) {
    // Registration must not wait for a screen to issue an operation (agent.md §7): resolving the
    // client opens the session, and the client module announces on the scope it was wired with.
    AnnounceAtLaunch { GlobalContext.get().get<TenshuClient>() }
    val repository = koinInject<CalendarRepository>()

    val backStack = remember { mutableStateListOf<Destination>(Destination.Calendar) }
    fun pop() { if (backStack.size > 1) backStack.removeLastOrNull() }
    fun push(destination: Destination) { backStack.add(destination) }

    /**
     * The occurrence the editor was opened on.
     *
     * ⚠⚠ Held by the SHELL, which outlives every nav entry, rather than carried inside the route
     * (`CalendarNavRoundTripTest` pins why). It cannot live in a view model the editor reaches for
     * instead: view models resolve against the *entry's* `ViewModelStore`, so an editor asking for
     * the calendar's would quietly construct a second one and re-read a window it does not need.
     * Nor can the editor look it up by id, because the two callers that matter most — a reminder
     * tap and a cross-app open — routinely name an occurrence **outside** the loaded window, and
     * hidden calendars at that (`resolveReminderLink` passes `includeHidden`).
     *
     * ⚠ One slot, no map: at most one editor is ever on the stack, because every route into it
     * truncates back to the calendar first (see [openOccurrence]).
     *
     * ⚠ Never cleared on close. The outgoing entry is still composed for the length of the host's
     * fade, and clearing here would make it render its own absence mid-animation.
     */
    var openedOccurrence: ResolvedOccurrence? by remember { mutableStateOf(null) }

    /**
     * Opens [occurrence] in the editor.
     *
     * ⚠⚠ **Truncates rather than stacks.** A second notification tapped while an editor is already
     * open *replaces* it; pushing instead would bury the first editor under the second and cost the
     * user two Back presses to reach the calendar, having asked for one event.
     */
    fun openOccurrence(occurrence: ResolvedOccurrence) {
        openedOccurrence = occurrence
        while (backStack.size > 1) backStack.removeLastOrNull()
        push(Destination.Editor.Existing(occurrence.eventId, occurrence.recurrenceId))
    }

    // ⚠ Hoisted above the routes so the sheet outlives whichever screen is showing —
    // and so a `.ics` handed over by the OS never has to move the user to find it.
    //
    // ⚠⚠ **Passed down to the calendars screen rather than resolved there a second time.** Each
    // nav entry has its own `ViewModelStore`, so resolving it inside the entry mints a SECOND
    // interop view model: a row's *Import* would set state on an instance the sheet drawn here is
    // not reading, and nothing would open.
    val interopViewModel: CalendarInteropViewModel = koinViewModel()

    // ⚠⚠ Seeds the first calendar + the default-calendar preference, which is
    // what makes `SaveEvent`'s headless path (and therefore Mia's `CreateEvent`)
    // work on a fresh realm — `SaveEvent` deliberately fails loudly rather than
    // guessing, and this is the "something must seed it" half.
    //
    // ⚠ `Unit` key: once per composition, not once per recomposition. The call
    // is idempotent anyway, but a `LaunchedEffect` that re-ran on every frame
    // would hammer the proxy for no reason.
    //
    // ⚠⚠ `runCatching` because this is the one repository call in the tree with
    // nothing catching it: an uncaught throw inside a `LaunchedEffect` takes the
    // app down, and the seeding write is precisely the call that fails when the
    // proxy is unreachable — i.e. it would crash on launch in the state the shell
    // exists to explain. Every other call site folds through a view model.
    LaunchedEffect(Unit) {
        bestEffort("Calendar -> seeding the default calendar at launch") { repository.ensureDefaultCalendar() }
    }

    // ⚠⚠ Keyed on the payload, so a *second* notification tapped while the app is
    // already open re-navigates instead of being ignored — the case a `Unit` key
    // would silently drop.
    //
    // ⚠ A payload that resolves to nothing leaves the route alone rather than
    // opening a blank editor: the occurrence may have been deleted between the
    // arm and the tap, and the honest result is the calendar as it now stands.
    // ⚠⚠ That is why the shell resolves BEFORE navigating rather than pushing an address and
    // letting the editor resolve it: only here is "there is nothing to open" still an answer the
    // user can be given by doing nothing.
    LaunchedEffect(tapPayload) {
        val link = tapPayload?.let(ReminderDeepLink::parse) ?: return@LaunchedEffect
        repository.resolveReminderLink(link)?.let(::openOccurrence)
    }

    // ⚠⚠ **No route change.** The interop sheet is hosted here, above the routes, so a
    // file arriving over the month grid opens it in place: being thrown onto a different
    // screen by a double-click in the Finder explains nothing, and the sheet already asks
    // which calendar to import into.
    LaunchedEffect(openedIcsFiles) {
        openedIcsFiles.collect { file ->
            when (file) {
                is OpenedIcsFile.Content -> interopViewModel.openForImport(file.text, file.name)
                is OpenedIcsFile.Unreadable ->
                    interopViewModel.openFailed(file.name, InteropProblem.Unreadable)
            }
        }
    }

    // Cross-app open (`control-cross-app-open.md` TT-1c): a citation elsewhere named one of our
    // rows, and this is where Calendar shows it.
    //
    // ⭐ **It reuses the reminder deep-link path whole** rather than growing a second resolver.
    // `OpenTarget` addresses an occurrence as `(eventId, recurrenceId)` — the same pair a reminder
    // tap carries — so the only new code is unwrapping the `DataId`. That is CO-D5 paying off: an
    // occurrence has no stored row, and both features had to solve that already.
    //
    // ⚠ A target that resolves to nothing leaves the route alone, exactly as a stale reminder does:
    // the row may have been deleted between the tap and the launch, and the honest answer is the
    // calendar as it now stands rather than a blank editor. ⚠⚠ This is §6b's open question showing
    // up for real, and it is answered here for Calendar only — the receiving half of CO-D7 belongs
    // to whichever app implements the route.
    LaunchedEffect(openTargets) {
        openTargets.collect { target ->
            val (eventId, recurrenceId) = OccurrenceId.parse(target) ?: return@collect
            repository.resolveReminderLink(ReminderDeepLink(eventId, recurrenceId))
                ?.let(::openOccurrence)
        }
    }

    // ⚠⚠ **The wall replaces the app, it does not sit on top of it** (ADR 0025 §Decision 1,
    // amended). Without Control there is no realm, so the grid renders an empty month that reads as
    // "you have no events" rather than "Control is missing".
    //
    // ⚠ `NotInstalled` and `NotSetUp` only: a transport outage is transient and self-recovering,
    // and a wall that slams down on a three-second blip is worse than any banner.
    val wallReason by repository.controlUnreachableReason().collectAsState()
    // ⚠⚠ **Installed, answering, and no realm** (ADR 0025 §Decision 8). Nothing is unreachable —
    // the proxy serves every read, empty — so without this the shell renders its empty states
    // over a library that does not exist. Permanent until the user acts, so it walls like
    // `NotInstalled`; the offer is to *open* Control, never to get it.
    // ⚠⚠ ADR 0025 §8.6: Control is answering, and whether this device has a
    // realm is not known yet. Non-interactive — no shell, nothing that writes — until the realm
    // read answers (normally ~1 s); blank for 300 ms, a spinner, a retry wall past 20 s.
    if (wallReason == UnreachableReason.Checking) {
        AppControlCheckingGate(onRetry = repository::recheckControl)
        return
    }
    if (wallReason == UnreachableReason.NotSetUp) {
        val wallAction = rememberControlBannerAction(wallReason)
        AppControlUnavailableWall(
            title = stringResource(Res.string.calendar_control_wall_title),
            body = stringResource(Res.string.calendar_control_not_set_up),
            icon = controlBannerIcon(wallReason),
            actionLabel = wallAction?.let { stringResource(Res.string.calendar_control_open_action) },
            onAction = wallAction?.onClick,
        )
        return
    }
    if (wallReason == UnreachableReason.NotInstalled) {
        val wallRoute = rememberControlInstallRoute()
        val wallAction = rememberControlBannerAction(wallReason, wallRoute)
        AppControlUnavailableWall(
            title = stringResource(Res.string.calendar_control_wall_title),
            body = when (wallRoute) {
                is ControlInstallRoute.Store ->
                    stringResource(Res.string.calendar_control_not_installed)
                ControlInstallRoute.NotPublishedYet ->
                    stringResource(Res.string.calendar_control_not_installed_unpublished)
                ControlInstallRoute.SameChannelAsThisApp ->
                    stringResource(Res.string.calendar_control_not_installed_other_channel)
            },
            actionLabel = wallAction?.let { stringResource(Res.string.calendar_control_get_action) },
            onAction = wallAction?.onClick,
        )
        return
    }

    KmpNavHost(
        backStack = backStack,
        modifier = modifier,
        onBack = { pop() },
        entryProvider = kmpEntryProvider {
            entry<Destination.Calendar> {
                CalendarPane(
                    onCreate = { push(Destination.Editor.New(date = it)) },
                    onCreateAt = { date, time -> push(Destination.Editor.New(date, time)) },
                    onOpen = { openOccurrence(it) },
                    onManageCalendars = { push(Destination.Calendars) },
                )
            }

            entry<Destination.Calendars> {
                CalendarsPane(
                    interopViewModel = interopViewModel,
                    onClose = { pop() },
                    onOpenLicences = { push(Destination.Licences) },
                )
            }

            // Calendar draws no map and shows no third-party data: libraries only.
            entry<Destination.Licences> {
                OpenSourceLicencesScreen(onBack = { pop() })
            }

            entry<Destination.Editor.New> { route ->
                EditorPane(
                    viewModelKey = route.key,
                    initialDate = route.date,
                    initialTime = route.time,
                    onClose = { pop() },
                )
            }

            entry<Destination.Editor.Existing> { route ->
                // ⚠⚠ Read from the shell's slot, not carried in the route — see [openedOccurrence].
                // ⚠ Matched against the address rather than taken on trust: the two cannot disagree
                // while there is one editor at a time and the slot is written immediately before
                // the push, and this check is what notices if either stops being true.
                val occurrence = openedOccurrence?.takeIf {
                    it.eventId == route.eventId && it.recurrenceId == route.recurrenceId
                }
                // ⚠ Pop rather than render an empty form. An editor with nothing loaded looks
                // exactly like a *create*, so the user would be one Save away from a duplicate
                // blank event — the same reasoning that makes an unresolvable reminder tap a no-op.
                LaunchedEffect(occurrence == null) { if (occurrence == null) pop() }
                if (occurrence != null) {
                    EditorPane(
                        viewModelKey = route.key,
                        occurrence = occurrence,
                        onClose = { pop() },
                    )
                }
            }
        },
    )

    // ⚠ Last, and outside the nav host: a modal sheet drawn over whatever route is up.
    CalendarInteropSheet(interopViewModel)
}

/**
 * The Control-unavailable strip, shared by Calendar's panes (`ADR 0025`).
 *
 * ⚠⚠ Without it Calendar renders an **empty month with no explanation** when Control is gone —
 * the silent-blank failure `:app-reachability` exists to prevent.
 *
 * ⚠ Gated on the REASON, not `controlReachable == false`: a fenced device answers every read, so it
 * stays "reachable" while receiving nothing, and that condition could never fire for it (SK-3).
 *
 * Copy from `composeResources` (ADR 0016): the Control banner and walls are in the catalogue; the
 * rest of the app's literals are not yet.
 *
 * ⚠ Placed inside each pane's `topBar` slot so `Scaffold` owns the status-bar inset and grows the
 * content padding; a hand-rolled inset puts the banner under the clock.
 */
@Composable
private fun ControlBanner(repository: CalendarRepository = koinInject()) {
    val reason by repository.controlUnreachableReason().collectAsState()
    if (reason == null) return
    val installRoute = rememberControlInstallRoute()
    val action = rememberControlBannerAction(reason, installRoute)
    ErrorBanner(
        text = when (val r = reason) {
            // ⚠⚠ The app to name is frequently NOT Calendar: on Android and desktop one
            // Control-owned proxy serves every app, so a version disagreement is usually Control's
            // to fix, and telling the user to update Calendar leaves them stuck after doing it.
            is UnreachableReason.Skew -> when (r.update) {
                UpdateTarget.CONTROL ->
                    stringResource(Res.string.calendar_control_skew_update_control)
                UpdateTarget.THIS_APP ->
                    stringResource(Res.string.calendar_control_skew_update_app)
                UpdateTarget.BOTH ->
                    stringResource(Res.string.calendar_control_skew_update_both)
            }
            is UnreachableReason.Fenced -> when (r.update) {
                UpdateTarget.CONTROL ->
                    stringResource(Res.string.calendar_control_fenced_update_control)
                UpdateTarget.THIS_APP ->
                    stringResource(Res.string.calendar_control_fenced_update_app)
                UpdateTarget.BOTH ->
                    stringResource(Res.string.calendar_control_fenced_update_both)
                // ⚠ No host declared itself. Name nothing rather than guess: "update Control" is
                // wrong under route 1 and "update Calendar" is wrong on Android and desktop.
                null ->
                    stringResource(Res.string.calendar_control_fenced_update_unknown)
            }
            // ⚠ Normally behind the wall (ADR 0025 §Decision 8); worded anyway, never as an outage.
            // Never reaches a banner — the root shows AppControlCheckingGate first (ADR 0025 §8.6).
            UnreachableReason.Checking -> appControlCheckingLabel()
            UnreachableReason.NotSetUp -> stringResource(Res.string.calendar_control_not_set_up)
            UnreachableReason.NotInstalled -> when (installRoute) {
                is ControlInstallRoute.Store ->
                    stringResource(Res.string.calendar_control_not_installed)
                ControlInstallRoute.NotPublishedYet ->
                    stringResource(Res.string.calendar_control_not_installed_unpublished)
                ControlInstallRoute.SameChannelAsThisApp ->
                    stringResource(Res.string.calendar_control_not_installed_other_channel)
            }
            UnreachableReason.Transport, null ->
                stringResource(Res.string.calendar_control_unreachable)
        },
        icon = controlBannerIcon(reason),
        actionLabel = action?.let {
            when (it.kind) {
                ControlBannerAction.Kind.OPEN_CONTROL -> stringResource(Res.string.calendar_control_open_action)
                ControlBannerAction.Kind.GET_CONTROL -> stringResource(Res.string.calendar_control_get_action)
                ControlBannerAction.Kind.UPDATE -> stringResource(Res.string.calendar_update_action)
            }
        },
        onAction = action?.onClick,
    )
}

/**
 * ⚠ Its `CalendarViewModel` resolves against the **entry's** `ViewModelStore`. This destination is
 * the root of the back stack and never leaves it, so the view model lives as long as the app —
 * which matters because it freezes `today` in its constructor (`CalendarModules`): an app left
 * open across midnight keeps yesterday's today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarPane(
    onCreate: (LocalDate?) -> Unit,
    onCreateAt: (LocalDate, LocalTime) -> Unit,
    onOpen: (ResolvedOccurrence) -> Unit,
    onManageCalendars: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CalendarViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nowMinute by rememberCurrentMinute()

    // ⚠⚠ A FAB is a phone affordance and a desktop window has no idiom for one —
    // a circle hovering over the bottom-right corner of a calendar is the tell
    // that this is an Android app in a window. From `Medium` up the same action
    // becomes a toolbar `+`, which is where macOS puts it; below it, the FAB
    // stays.
    val sizeClass = LocalWindowWidthSizeClass.current
    val wide = sizeClass >= WindowWidthSizeClass.Medium

    // ⚠⚠ `Large` (1200dp), not `Expanded`. From `Medium` up the month view already
    // spends 300dp on the selected-day pane; taking another 220 at 840dp would leave
    // the grid — the thing the window is for — with barely half the width. A sidebar
    // is worth having only once there is width nothing else is asking for.
    val showSidebar = sizeClass >= WindowWidthSizeClass.Large

    val storageState = rememberRealmStorageState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
          // ⚠ The banner rides the topBar slot so `Scaffold` owns the status-bar inset and grows
          // the content padding by its height; a hand-rolled inset puts it under the clock. Below
          // the bar: the bar is where you are, the banner is what is wrong.
          Column {
            AppTopAppBar(
                title = "Calendar",
                actions = {
                    if (wide) {
                        // ⚠ Same rule as the FAB (`CalendarCreateButton`): creates on
                        // the *selected* day, not today.
                        IconButton(onClick = { onCreate(state.selectedDate) }) {
                            Icon(Icons.Default.Add, contentDescription = "New event")
                        }
                    }
                    // ⚠ Dropped once the sidebar is up: it carries its own "Manage
                    // calendars" button, and the same destination behind two controls
                    // in one window is how a user learns to distrust both.
                    if (!showSidebar) {
                        IconButton(onClick = onManageCalendars) {
                            Icon(Icons.Default.DateRange, contentDescription = "Calendars")
                        }
                    }
                },
            )
            // ⚠ Above the storage banner, not instead of it: "Control is gone" and "the realm
            // cannot sync" are different facts and a user can be owed both.
            ControlBanner()
            RealmStorageBannerHost(storageState)
          }
        },
        floatingActionButton = { if (!wide) CalendarCreateButton(state, onCreate) },
    ) { padding ->
        Row(Modifier.padding(padding)) {
            if (showSidebar) {
                CalendarSidebar(
                    calendars = state.calendars,
                    // ⚠⚠ Through `CalendarViewModel`, *not* `CalendarManagementViewModel`
                    // — which also has a `setVisible`. That one refreshes its own list
                    // and nothing else, so the checkbox would move and the grid beside
                    // it would keep drawing the calendar that was just hidden. This one
                    // writes the same preference and reloads the window.
                    onToggleVisible = viewModel::setCalendarVisible,
                    onManage = onManageCalendars,
                    modifier = Modifier.width(CalendarSidebarWidth),
                )
                // ⚠ The window margin belongs to the content pane, not to the sidebar:
                // Material models leading chrome as the window perimeter, sitting
                // outside the panes rather than inside them.
                VerticalDivider()
            }

            Column(Modifier.weight(1f).appWindowMargin()) {
                CalendarScreen(
                    viewModel = viewModel,
                    onOpen = onOpen,
                    onCreateAt = onCreateAt,
                    nowMinute = nowMinute,
                )
            }
        }
    }
}

/**
 * ⚠⚠ [interopViewModel] is a **parameter, not a `koinViewModel()` call**, and that is the one thing
 * about this pane that cannot be relaxed: the sheet it feeds is drawn by [App], one nav entry away,
 * and each entry owns a separate `ViewModelStore`. Resolving it here would build a second instance
 * and a row's *Import* would set state nothing renders.
 *
 * ⚠ Its `CalendarManagementViewModel` is re-created per visit — the entry's store is cleared on
 * pop. Nothing depends on that: `refresh()` opens a live subscription rather than reading once,
 * so a calendar renamed on another device arrives while the screen is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarsPane(
    interopViewModel: CalendarInteropViewModel,
    onClose: () -> Unit,
    onOpenLicences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CalendarManagementViewModel = koinViewModel()
    // ⚠ Resolved here, unlike the interop one: its sheet is drawn by this pane's own screen, so
    // the entry's `ViewModelStore` is the right owner.
    val externalCopyViewModel: app.tenshu.calendar.ui.ExternalCopyViewModel = koinViewModel()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column {
                AppTopAppBar(title = "Calendars", onBack = onClose)
                ControlBanner()
            }
        },
    ) { padding ->
        // ⚠ Calendar has no settings screen; this management pane is the nearest thing, so the
        // legal entries sit under its list, below the FABs: the in-app privacy policy (App Store
        // 5.1.1(i), Play User Data policy), then the open-source notices (docs/oss-licences.md).
        Column(Modifier.padding(padding).fillMaxSize()) {
            CalendarsScreen(
                viewModel = viewModel,
                interopViewModel = interopViewModel,
                externalCopyViewModel = externalCopyViewModel,
                modifier = Modifier.weight(1f),
            )
            AppPrivacyPolicyListItem()
            OpenSourceLicencesListItem(onClick = onOpenLicences)
        }
    }
}

/**
 * The event editor, creating on [initialDate] / [initialTime] or editing [occurrence].
 *
 * ⚠ [occurrence] is passed in rather than read from a route or a repository: it is the shell's
 * resolved value, and re-reading it here would give the form a second source that can disagree
 * with the list — or the notification — that opened it.
 */
@Composable
private fun EditorPane(
    viewModelKey: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialDate: LocalDate? = null,
    initialTime: LocalTime? = null,
    occurrence: ResolvedOccurrence? = null,
) {
    // ⚠⚠ The `key` is load-bearing: without one, Compose hands back the view
    // model from the *previous* event and the form opens pre-filled with
    // somebody else's data. Drive's `FilesScreenIntegrationTest` documents this
    // exact trap — a `koinViewModel(parameters = …)` with no key.
    // ⚠ The nav host separates them too — a different route is a different `ViewModelStore` —
    // but the key stays: it is what makes this composable correct wherever it is called from,
    // rather than correct because of where it happens to be called.
    val viewModel: EventEditorViewModel = koinViewModel(
        key = viewModelKey,
        parameters = { parametersOf(initialDate, initialTime) },
    )

    // ⚠ Keyed on the occurrence, not `Unit`: re-running this on recomposition
    // would discard whatever the user has typed since.
    LaunchedEffect(viewModelKey) { occurrence?.let(viewModel::load) }

    EventEditorScreen(viewModel = viewModel, onClose = onClose, modifier = modifier)
}
