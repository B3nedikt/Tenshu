package org.tenshu.sdk.ios

import app.tenshu.calendar.data.CalendarMirrorRepository
import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.external.MirrorRefresher
import app.tenshu.calendar.data.external.UserDefaultsMirrorDigestStore
import app.tenshu.calendar.data.NotConnectedCalendarRepository
import app.tenshu.calendar.model.CalendarHostInput
import app.tenshu.calendar.ui.CalendarInteropViewModel
import app.tenshu.calendar.ui.CalendarManagementViewModel
import app.tenshu.calendar.ui.EventEditorViewModel
import app.tenshu.calendar.ui.ExternalCopyViewModel
import app.tenshu.calendar.data.external.EventKitCalendarReader
import app.tenshu.calendar.ui.CalendarViewModel
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * Wires the **real** Calendar view-model layer (`:calendar-shared-logic`) to iOS so the SwiftUI
 * `calendar-ios` app can resolve each screen's view model and observe its `StateFlow` through
 * SKIE (`ios-proxy-architecture.md` §9.3 — "share view models + repos, native UI"). The Calendar
 * twin of [ContactsSurface] / [MapsSurface] / [TimelineSurface] / [FitSurface].
 *
 * Calendar's Koin DI lives in `:calendar-shared-ui` (Compose, not on iOS), so this object is the
 * minimal hand-wiring the SwiftUI client needs instead of `calendarModules()`.
 *
 * One seam, not Maps' two: every Calendar screen reads the same [CalendarRepository].
 *
 * The runtime default is the honest **not-connected** repository
 * ([NotConnectedCalendarRepository], which lives in `commonMain` so it is unit-tested rather than
 * iOS-only): an unprovisioned app is empty and says why. ⚠⚠ A mock behind the shipping gate would
 * be worse here than in any sibling app — a calendar that shows appointments you do not have does
 * not merely look wrong, it makes you be somewhere else.
 *
 * ⚠ Only the factories the Swift shell actually calls are exposed — a factory nothing calls is
 * built and unreachable, and nothing notices.
 */
object CalendarSurface {

    private var repository: CalendarRepository = NotConnectedCalendarRepository()

    /**
     * ⚠ Reachability lives on the **surface**, not on [CalendarRepository], because Calendar's
     * Compose shells do not read it — widening a 25-method interface for one platform's banner
     * would put a method on Android and desktop that nothing calls. A Compose banner would move
     * it down. (Contacts put its signal on the repository for the opposite reason:
     * its Compose shell reads it.)
     */
    private var reachable: Flow<Boolean?> = flowOf(false)

    /**
     * *Keep in sync* (CX-2b): present only while a real repository is installed, because only
     * that one stores mirrors. ⚠ Its own reader, separate from the copy sheet's: both build their
     * `EKEventStore` only once access is granted, so neither can be stuck with a pre-grant store.
     */
    private var refresher: MirrorRefresher? = null
    private var mirrors: CalendarMirrorRepository? = null

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshing: Job? = null

    /**
     * Install the repository subsequently-built view models bind, plus the runtime's one
     * reachability signal. Called by the tier that owns a live client — [CalendarLocalRuntime]
     * (route 1); an EPC tier would call the same seam.
     */
    fun installRepository(calendarRepository: CalendarRepository, reachability: Flow<Boolean?>) {
        repository = calendarRepository
        reachable = reachability
        mirrors = calendarRepository as? CalendarMirrorRepository
        refresher = mirrors?.let {
            // ⚠ The clock enters at the composition root, like every other "now" in Calendar.
            MirrorRefresher(it, EventKitCalendarReader(), UserDefaultsMirrorDigestStore()) {
                Clock.System.now().toEpochMilliseconds()
            }
        }
    }

    /**
     * Restore the honest **not-connected** surface — the teardown / fallback target: empty reads,
     * refused writes, and `controlReachable = false`, which is what drives the shell's banner.
     */
    fun installNotConnected() {
        repository = NotConnectedCalendarRepository()
        reachable = flowOf(false)
        mirrors = null
        refresher = null
    }

    /**
     * Refreshes the mirrors bound to this iPhone — the Swift shell calls it on every foreground.
     *
     * ⚠⚠ **Foreground only, and that is the whole schedule** (§4.6): iOS binds no background
     * scheduler for Calendar (`ios-background-scheduling.md`), and EventKit offers no content
     * trigger like Android's. A mirror is therefore as fresh as the last time the app was opened,
     * which the calendars screen shows on the row ("updated …").
     *
     * ⚠ Fire-and-forget and coalesced: a second foreground while one refresh runs is dropped, not
     * queued — the running one reads the store as it is anyway. Cheap when nothing changed (a
     * read and a digest, no writes), and silent when access was revoked: the refresher reports it
     * per mirror and leaves the stamp alone, so the row goes visibly stale instead of erroring.
     */
    fun refreshMirrors() {
        val refresher = refresher ?: return
        if (refreshing?.isActive == true) return
        refreshing = refreshScope.launch { runCatchingCancellable { refresher.refreshAll() } }
    }

    /**
     * Whether the installed repository can actually reach Control — `false` on the not-connected
     * surface, and whatever the live engine reports otherwise.
     *
     * ⚠ `null` means *not yet known*: the shell shows no banner while it waits, because "still
     * asking" and "not connected" are different states and only the second one explains an empty
     * screen.
     */
    fun controlReachable(): Flow<Boolean?> = reachable

    /** The repository itself, for the Swift shell's few direct reads. */
    fun repository(): CalendarRepository = repository

    /**
     * The calendar screen's view model. Built fresh per call rather than cached: SwiftUI owns its
     * lifetime through `@StateObject`, and a cached instance would outlive its screen and keep
     * collecting.
     *
     * ⚠⚠ Today is passed in, never read from a clock — the discipline `CalendarViewModel` is built
     * around (`calendar-app.md` §3 D3). "What is today" depends on a timezone the *host* knows, and
     * on iOS the host is Swift, which has `Calendar.current`.
     *
     * ⚠ It arrives as three plain integers rather than a bridged `LocalDate`, so the Swift shell
     * never names a kotlinx-datetime constructor. The JVM gate compiles no Swift, and a
     * constructor spelling is exactly the sort of thing that would be discovered on somebody's
     * Mac instead. Out-of-range parts fall back to today's epoch start rather than
     * throwing — a shell that crashes on a nonsense date component is worse than one that is a day
     * wrong for one frame.
     *
     * ⚠ [weekStartIsoDay] is ISO (1 = Monday … 7 = Sunday), converted here rather than in Swift,
     * whose `Calendar.firstWeekday` is 1 = *Sunday*. Doing the shift once, on this side, is what
     * stops the two numbering schemes meeting somewhere in a view. Out of range falls back to
     * Monday, for the same reason.
     */
    fun calendarViewModel(
        todayYear: Int,
        todayMonth: Int,
        todayDay: Int,
        weekStartIsoDay: Int,
    ): CalendarViewModel = CalendarViewModel(
        repository = repository,
        today = CalendarHostInput.today(todayYear, todayMonth, todayDay),
        weekStart = CalendarHostInput.weekStart(weekStartIsoDay),
    )

    /**
     * The calendars screen's view model — the list, its colours, this device's visibility set, the
     * default calendar, and the subscriptions.
     *
     * ⚠ No clock and no locale: unlike [calendarViewModel] this screen has no notion of "now" at
     * all, which is why it takes nothing. The Compose module records the same observation.
     */
    fun calendarManagementViewModel(): CalendarManagementViewModel =
        CalendarManagementViewModel(repository, mirrors = mirrors, refresher = refresher)

    /**
     * The `.ics` import/export flow's view model.
     *
     * ⚠⚠ **No `CalendarFileAccess` actual, deliberately.** That seam exists because
     * Android's `ActivityResult` launchers may only be registered during composition;
     * SwiftUI has `.fileImporter` / `.fileExporter` as ordinary view modifiers and
     * needs no seam at all. Adding an iOS actual would mean putting `UIDocumentPicker`
     * behind a Compose-shaped abstraction in a module (`:calendar-shared-ui`) that
     * does not build for iOS in the first place. The Swift shell drives the picker and
     * hands this view model *text* — which is exactly the contract the seam was drawn
     * to produce.
     *
     * ⚠ Built fresh per call, like every other factory here: SwiftUI owns its lifetime
     * through `@StateObject`.
     */
    fun calendarInteropViewModel(): CalendarInteropViewModel =
        // ⚠ With the mirrors, so a kept-in-sync calendar offers no *Import* — its next refresh
        // would delete what was imported.
        CalendarInteropViewModel(repository, mirrors = mirrors)

    /**
     * *Copy from this iPhone* (`calendar-external-sources.md` CX-2a) — EventKit's calendars,
     * copied once into Tenshu.
     *
     * ⚠ *Keep in sync* (CX-2b) is offered whenever a real repository is installed. No background
     * scheduler: [refreshMirrors] on foreground is the schedule. ⚠ Asking for calendar access is
     * the Swift shell's job, at the button; the reader only checks it was granted.
     */
    fun externalCopyViewModel(): ExternalCopyViewModel =
        ExternalCopyViewModel(
            repository,
            reader = EventKitCalendarReader(),
            mirrors = mirrors,
            refresher = refresher,
            background = null,
        )

    /**
     * The event editor's view model.
     *
     * ⚠ The start date arrives as three integers, like [calendarViewModel]'s today, and for the
     * same reason: the Swift shell must never name a kotlinx-datetime constructor.
     *
     * ⚠⚠ There is no `initialTime` parameter. Kotlin's default arguments do not survive to Swift,
     * so an unused one would have to be spelled at every call site — and the only thing that ever
     * supplies a time is the Compose time-grid tap, which iOS does not have. A create therefore
     * opens on the view model's own 09:00 default. A native time grid would add the parameter here
     * rather than the shell inventing one.
     *
     * ⚠ Editing an existing occurrence uses the same factory and then `load(occurrence)` — the
     * occurrence is a value the shell already holds from a list, so nothing is constructed.
     */
    fun eventEditorViewModel(
        startYear: Int,
        startMonth: Int,
        startDay: Int,
    ): EventEditorViewModel = EventEditorViewModel(
        repository = repository,
        initialDate = CalendarHostInput.today(startYear, startMonth, startDay),
    )
}
