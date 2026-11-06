package app.tenshu.calendar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.model.DaySchedule
import app.tenshu.calendar.model.MonthGrid
import app.tenshu.calendar.model.WeekGrid
import app.tenshu.calendar.model.daySchedule
import app.tenshu.calendar.model.shiftMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlinx.datetime.plus
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/** Which view the screen is showing. CA-2 shipped the cheap two; CA-4 the grid. */
enum class CalendarView {
    MONTH,
    WEEK,
    DAY,
    AGENDA;

    /** How many days one page of this view covers; `0` where paging is not a thing. */
    val periodDays: Int
        get() = when (this) {
            WEEK -> WeekGrid.DAYS
            DAY -> 1
            MONTH, AGENDA -> 0
        }

    /**
     * Whether the header's back/forward arrows do anything here.
     *
     * ⚠ MONTH pages without having a day count — it moves by a *month*, which is
     * not a fixed number of days. AGENDA does not page at all: it is a forward
     * list from today, and its scroll position is the navigation.
     */
    val canPage: Boolean get() = this != AGENDA
}

data class CalendarUiState(
    val view: CalendarView = CalendarView.MONTH,
    /** The month the grid is showing. */
    val year: Int = 0,
    val month: Int = 0,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    /**
     * The day the screen is focused on.
     *
     * ⚠ Serves two roles on purpose: the detail list's day in MONTH view, and
     * the anchor the WEEK/DAY grid is built around. Keeping them one field is
     * what makes "tap the 20th, switch to Day" land on the 20th rather than on
     * today — two fields would have to be kept in step by hand, and the bug when
     * they drift is silent.
     */
    val selectedDate: LocalDate? = null,
    /** Today, as the host told us — see [CalendarViewModel] on why it is passed in. */
    val today: LocalDate? = null,
    val calendars: List<CalendarSummary> = emptyList(),
    /** Occurrences covering the loaded window, sorted by effective start. */
    val occurrences: List<ResolvedOccurrence> = emptyList(),
    /**
     * The laid-out days the WEEK/DAY grid draws; empty in the other views.
     *
     * ⚠ Computed once per load rather than derived on read: a `LazyRow` of day
     * columns re-reads this on every scroll frame, and re-running the overlap
     * layout each time would be a real cost for an answer that cannot have
     * changed.
     */
    val schedules: List<DaySchedule> = emptyList(),
    val isLoading: Boolean = true,
    /**
     * True once a load has finished.
     *
     * ⚠⚠ Separate from [isLoading] on purpose: conflating "no events" with
     * "not loaded yet" is the eternal-spinner bug `ios-drive-parity.md` P1a
     * found in Drive's `FilesView`, where an empty folder never stopped
     * loading. An empty calendar is a *result*, and must render as one.
     */
    val loaded: Boolean = false,
) {
    val grid: MonthGrid?
        get() = if (year == 0) null else MonthGrid.of(year, month, weekStart)

    /** Occurrences of one day, for the month view's detail list. */
    fun occurrencesOn(date: LocalDate): List<ResolvedOccurrence> =
        occurrences.filter { it.startLocal.date == date }

    /** Day → count, for the grid's per-day dots. */
    val countsByDate: Map<LocalDate, Int>
        get() = occurrences.groupingBy { it.startLocal.date }.eachCount()

    val isEmpty: Boolean get() = loaded && occurrences.isEmpty()

    /** True while the time grid is the visible surface. */
    val isTimeGrid: Boolean get() = view == CalendarView.WEEK || view == CalendarView.DAY
}

/**
 * The Calendar screen's view model (CA-2, extended by CA-4).
 *
 * ⚠⚠ **`today` is injected, not read from a clock.** Every date this view model
 * reasons about is civil, and "what is today" depends on a timezone the *host*
 * knows and this layer does not (`calendar-app.md` §3 D3). Passing it in also
 * makes every test deterministic without a fake clock — the same discipline
 * `FiringCalculator` and `RecurrenceExpander` already follow.
 *
 * ⚠ The loaded window is the **whole month grid**, not the calendar month: the
 * grid shows leading and trailing days from the adjacent months, and loading
 * only the month would render those cells permanently empty — a bug that looks
 * like missing data rather than a windowing mistake.
 */
class CalendarViewModel(
    private val repository: CalendarRepository,
    private val today: LocalDate,
    private val weekStart: DayOfWeek = DayOfWeek.MONDAY,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CalendarUiState(
            year = today.year,
            month = today.month.number,
            weekStart = weekStart,
            selectedDate = today,
            today = today,
        )
    )
    val uiState: StateFlow<CalendarUiState> = _uiState

    /**
     * The loaded window, **including calendars this device hides**, and the hidden set
     * itself — the two inputs `applyVisibility` derives the visible state from.
     *
     * ⚠ Plain fields rather than state: they are only ever touched from
     * `viewModelScope`, which is confined to the main dispatcher, and putting them in
     * `CalendarUiState` would publish "events you are not being shown" to every
     * consumer of the ui state, including the iOS shell.
     */
    private var loadedOccurrences: List<ResolvedOccurrence> = emptyList()
    private var loadedCalendars: List<CalendarSummary> = emptyList()
    private var hiddenCalendars: Set<String> = emptySet()

    /** The live read of the current window; swapped whenever the window moves. */
    private var windowSubscription: Job? = null

    init {
        // ⚠⚠ Collected, never polled. This is the authoritative source for visibility
        // — including a change made on the calendars screen, or on another device once
        // the preference syncs — and it is what corrects the optimistic update in
        // `setCalendarVisible`. See `CalendarRepository.observeHiddenCalendars`.
        viewModelScope.launch {
            repository.observeHiddenCalendars().collect { hidden ->
                if (hidden == hiddenCalendars) return@collect
                hiddenCalendars = hidden
                applyVisibility()
            }
        }
    }

    init {
        refresh()
    }

    fun showMonth() = show(CalendarView.MONTH)
    fun showWeek() = show(CalendarView.WEEK)
    fun showDay() = show(CalendarView.DAY)
    fun showAgenda() = show(CalendarView.AGENDA)

    /**
     * ⚠ Every switch reloads, because every view has a different window. MONTH
     * alone could sometimes skip it, and special-casing that is how a view ends
     * up rendering the previous view's data on the first frame.
     */
    private fun show(view: CalendarView) {
        _uiState.update { it.copy(view = view) }
        refresh()
    }

    fun selectDate(date: LocalDate) = _uiState.update { it.copy(selectedDate = date) }

    /**
     * Select a day from its parts (CA-8b) — the twin of [showMonth], for the same reason: a native
     * month grid reports a tapped cell as date *components*, not as a date object.
     *
     * ⚠ An unparseable day is ignored rather than throwing. It can only arrive from a platform
     * callback, and clearing or crashing on it would both be worse than leaving the selection
     * where the user last put it.
     */
    fun selectDay(year: Int, month: Int, day: Int) {
        val date = runCatching { LocalDate(year, month, day) }.getOrNull() ?: return
        selectDate(date)
    }

    /**
     * Pages the current view by one of *its own* periods — a month, a week, a
     * day.
     *
     * ⭐ One entry point rather than `stepMonth`/`stepWeek`/`stepDay`, so the
     * header's back/forward buttons stay view-agnostic: they mean "previous"
     * and the view decides what that is.
     *
     * ⚠ AGENDA does not page — it is a forward-looking list from today, and its
     * scroll position is the navigation.
     */
    fun step(delta: Int) {
        val state = _uiState.value
        when (state.view) {
            CalendarView.MONTH -> {
                val (year, month) = shiftMonth(state.year, state.month, delta)
                _uiState.update { it.copy(year = year, month = month) }
            }
            CalendarView.WEEK, CalendarView.DAY -> {
                val anchor = state.selectedDate ?: today
                val moved = anchor.plus(delta * state.view.periodDays, DateTimeUnit.DAY)
                // ⚠ The month follows the anchor, so switching back to MONTH
                // after paging weeks lands where the user actually is.
                _uiState.update {
                    it.copy(selectedDate = moved, year = moved.year, month = moved.month.number)
                }
            }
            CalendarView.AGENDA -> return
        }
        refresh()
    }

    /**
     * Show a specific month (CA-8b).
     *
     * ⭐ [step] cannot express this. It moves by one of the current view's periods, which is right
     * for a header with back/forward arrows — but a **native month control pages itself** and then
     * reports where it landed, and "the user flicked forward three months" is not a sequence of
     * steps we saw. `UICalendarView` is the first such control; a Compose pager would be the
     * second.
     *
     * ⚠⚠ **A no-op when the month has not changed, and that guard is load-bearing.** The control
     * reports its visible month on every settle — *including* settles caused by us setting it — so
     * an unconditional reload here is a feedback loop that reloads the window forever. It is also
     * why this returns rather than refreshing: "already showing it" is a real, common call.
     *
     * ⚠ It deliberately does **not** move [CalendarUiState.selectedDate]. Scrolling the grid is
     * not selecting a day; moving the selection with the scroll would silently change which day's
     * events the detail list is showing, from a gesture that never touched it.
     */
    fun showMonth(year: Int, month: Int) {
        if (month !in 1..12) return
        val state = _uiState.value
        if (state.year == year && state.month == month) return
        _uiState.update { it.copy(year = year, month = month) }
        refresh()
    }

    /** Jump back to today and select it. */
    fun goToToday() {
        _uiState.update {
            it.copy(year = today.year, month = today.month.number, selectedDate = today)
        }
        refresh()
    }

    /**
     * Shows or hides a calendar on this device.
     *
     * ⚠⚠ **Optimistic, and the write is fire-and-forget — it must not be followed by
     * a re-read.** This used to `setCalendarVisible(…)` then `refresh()`, and the
     * refresh observed the *pre-write* preference: the row landed in the store
     * correctly, the grid redrew identically, and the checkbox sprang back. Nothing
     * failed and nothing logged. [applyVisibility] updates the screen now;
     * `observeHiddenCalendars` is what corrects it if the write did not land.
     */
    fun setCalendarVisible(calendarId: String, visible: Boolean) {
        hiddenCalendars = if (visible) {
            hiddenCalendars - calendarId
        } else {
            hiddenCalendars + calendarId
        }
        applyVisibility()
        viewModelScope.launch { repository.setCalendarVisible(calendarId, visible) }
    }

    /**
     * Re-derives everything visibility touches from [loadedOccurrences] and
     * [hiddenCalendars].
     *
     * ⚠⚠ Visibility is applied **here**, not by re-querying: the window is loaded
     * with `includeHidden = true` precisely so that hiding and un-hiding are a filter
     * over data already in hand rather than a round trip. That is what makes a toggle
     * instant, and it is also why the toggle cannot race the store any more — there is
     * no second read to be early.
     *
     * ⚠ The `visible` flags on [CalendarUiState.calendars] are recomputed from the same
     * set rather than trusted from `repository.calendars()`, so the checkbox and the
     * grid cannot disagree — they are two renderings of one value.
     */
    private fun applyVisibility() {
        val shown = loadedOccurrences.filterNot { it.calendarId in hiddenCalendars }
        _uiState.update { state ->
            state.copy(
                calendars = loadedCalendars.map {
                    it.copy(visible = it.calendarId !in hiddenCalendars)
                },
                occurrences = shown,
                schedules = state.visibleDays().map { day -> daySchedule(day, shown) },
            )
        }
    }

    /**
     * (Re)subscribes to the current view's window.
     *
     * ⚠⚠ **A subscription, not a load** (CA-2h). The window is *observed*, so an event
     * created by Mia, an `.ics` import, a subscription refresh or a block synced from
     * another device lands on the grid on its own. Everything that changes the window
     * — paging, switching view, jumping to today — still calls this, and it swaps the
     * subscription rather than fetching once; the name stays because every caller,
     * including iOS's pull-to-refresh and its editor's `onDismiss`, means "show me the
     * current state of this window" either way.
     *
     * ⚠ The previous subscription is cancelled first. Leaving it running would keep a
     * stale window's reads alive for the life of the screen, and every page step would
     * add another — with the older ones still emitting into the same state.
     *
     * ⚠ The window is observed with hidden calendars **included**, so visibility stays
     * a filter over data already in hand ([applyVisibility]) rather than a re-query.
     * That is what keeps the CA-2g toggle instant.
     */
    fun refresh() {
        val state = _uiState.value
        val (from, to) = state.loadWindow()
        _uiState.update { it.copy(isLoading = true) }
        windowSubscription?.cancel()
        windowSubscription = viewModelScope.launch {
            combine(
                repository.observeOccurrences(from, to),
                repository.observeCalendars(),
            ) { occurrences, calendars -> occurrences to calendars }
                .collect { (occurrences, calendars) ->
                    loadedOccurrences = occurrences
                    loadedCalendars = calendars
                    applyVisibility()
                    _uiState.update { it.copy(isLoading = false, loaded = true) }
                }
        }
    }

    /** The days the time grid draws; empty for the views that are not one. */
    private fun CalendarUiState.visibleDays(): List<LocalDate> {
        val anchor = selectedDate ?: today ?: return emptyList()
        return when (view) {
            CalendarView.WEEK -> WeekGrid.containing(anchor, weekStart).days
            CalendarView.DAY -> listOf(anchor)
            CalendarView.MONTH, CalendarView.AGENDA -> emptyList()
        }
    }

    /**
     * The window to load for the current view.
     *
     * ⚠ MONTH loads the grid's full 42 days, not the calendar month — see the
     * class KDoc. AGENDA loads a forward-looking stretch from today, which is
     * what an agenda *is*: the past is not what you open it for.
     *
     * ⚠⚠ WEEK and DAY reach **one day further back** than they draw. The
     * repository answers by an occurrence's *start* date, so a shift running
     * 22:00 Sunday → 02:00 Monday is not in a Monday-first window at all — and
     * the Monday column would show a clear morning that is not clear. Nothing
     * needs widening forward: an event starting on the last visible day is
     * already returned, and `daySchedule` clips it.
     */
    private fun CalendarUiState.loadWindow(): Pair<LocalDate, LocalDate> = when (view) {
        CalendarView.MONTH -> {
            val cells = MonthGrid.of(year, month, weekStart).cells
            cells.first().date to cells.last().date
        }
        CalendarView.WEEK, CalendarView.DAY -> {
            val days = visibleDays()
            days.first().plus(-1, DateTimeUnit.DAY) to days.last()
        }
        CalendarView.AGENDA -> today?.let { it to it.plus(AGENDA_DAYS, DateTimeUnit.DAY) }
            ?: (LocalDate(year, month, 1) to LocalDate(year, month, 1).plus(AGENDA_DAYS, DateTimeUnit.DAY))
    }

    private companion object {
        /**
         * How far ahead the agenda looks.
         *
         * ⚠ A bounded horizon rather than "everything": occurrences are expanded
         * from rules, so an unbounded agenda is an unbounded expansion. 90 days
         * is far past what a list is read for, and paging it is a later problem
         * once there is a scroll position to page against.
         */
        const val AGENDA_DAYS = 90
    }
}
