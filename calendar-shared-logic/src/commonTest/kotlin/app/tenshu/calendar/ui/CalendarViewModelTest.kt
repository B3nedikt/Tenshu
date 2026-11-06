package app.tenshu.calendar.ui

import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.CalendarSubscriptionSummary
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.data.IcsImportResult
import app.tenshu.calendar.data.EventDraft
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence
import org.tenshu.viewmodel.test.vmTest
import org.tenshu.viewmodel.test.OwnedViewModels

/**
 * The Calendar screen's state machine.
 *
 * ⭐ Every test here is deterministic without a fake clock, because `today` is a
 * constructor parameter rather than something the view model reads — the same
 * discipline the recurrence engine follows one layer down.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    /**
     * ⚠ `viewModelScope` dispatches on `Dispatchers.Main`, which does not exist
     * off Android — without this every `refresh()` would simply never run and
     * the assertions would read the pre-load state. The house pattern
     * (`MergeViewModelTest` and friends); `Unconfined` so the launched work
     * completes eagerly and the tests stay assertion-shaped rather than
     * advance-the-scheduler-shaped.
     */
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val today = LocalDate(2026, 8, 12)   // a Wednesday

    /**
     * Records the windows it was asked for, so the tests can assert on them.
     *
     * ⚠ Backed by `MutableStateFlow`s since CA-2h: the reads are **live**, so a test
     * simulates a sync, an import or another app's write by pushing new rows, and the
     * screen is expected to follow without anyone calling `refresh()`.
     */
    private class FakeRepository(
        occurrences: List<ResolvedOccurrence> = emptyList(),
        calendars: List<CalendarSummary> = emptyList(),
    ) : CalendarRepository {
        val rows = MutableStateFlow(occurrences)
        val calendarRows = MutableStateFlow(calendars)
        val windows = mutableListOf<Pair<LocalDate, LocalDate>>()
        val visibilityWrites = mutableListOf<Pair<String, Boolean>>()

        /**
         * What the *store* currently reports as hidden.
         *
         * ⚠⚠ **[setCalendarVisible] deliberately does not update this**, and that is
         * the whole point of the fake: in the real store a preference write lands
         * through the write mailbox, so a read taken straight after it still answers
         * with the pre-write value. A fake that applied the write synchronously would
         * be green on precisely the code that shipped broken. Tests push the emission
         * themselves, which is also how another device's change arrives.
         */
        val hidden = MutableStateFlow(emptySet<String>())

        override suspend fun calendars(): List<CalendarSummary> =
            calendarRows.value.map { it.copy(visible = it.calendarId !in hidden.value) }

        override suspend fun occurrences(
            from: LocalDate,
            to: LocalDate,
            includeHidden: Boolean,
        ): List<ResolvedOccurrence> {
            windows += from to to
            return rows.value
                .filter { it.startLocal.date in from..to }
                .filter { includeHidden || it.calendarId !in hidden.value }
        }

        // ⚠ The window is recorded at *subscribe*, which is what the paging tests read:
        // one entry per resubscription, exactly as the one-shot read gave them.
        override fun observeOccurrences(
            from: LocalDate,
            to: LocalDate,
        ): Flow<List<ResolvedOccurrence>> {
            windows += from to to
            return rows.map { all -> all.filter { it.startLocal.date in from..to } }
        }

        override fun observeCalendars(): Flow<List<CalendarSummary>> =
            combine(calendarRows, hidden) { all, hiddenIds ->
                all.map { it.copy(visible = it.calendarId !in hiddenIds) }
            }

        override fun observeSubscriptions(): Flow<List<CalendarSubscriptionSummary>> =
            flowOf(emptyList())

        override fun observeDefaultCalendar(): Flow<String> = flowOf("")

        override suspend fun setCalendarVisible(calendarId: String, visible: Boolean) {
            visibilityWrites += calendarId to visible
        }

        override fun observeHiddenCalendars(): Flow<Set<String>> = hidden

        var seedCalls = 0
        override suspend fun ensureDefaultCalendar() { seedCalls++ }

        // ⚠ The month/agenda screen never writes; the editor owns that surface
        // (`EventEditorViewModelTest`). These exist only to satisfy the seam.
        override suspend fun saveEvent(draft: EventDraft): String = draft.eventId
        override suspend fun saveOccurrence(draft: EventDraft, recurrenceId: String) = Unit
        override suspend fun deleteEvent(eventId: String) = Unit
        override suspend fun deleteOccurrence(eventId: String, recurrenceId: String) = Unit
        override suspend fun remindersFor(eventId: String): List<Int> = emptyList()
        override suspend fun setReminders(eventId: String, minutesBefore: List<Int>) = Unit
        override suspend fun defaultReminderMinutes(allDay: Boolean): Int? = null
        override suspend fun saveCalendar(
            calendarId: String,
            name: String,
            colorArgb: Int,
            sensitive: Boolean,
        ): String = calendarId.ifBlank { "minted" }
        override suspend fun deleteCalendar(calendarId: String) = Unit
        override suspend fun defaultCalendar(): String = ""
        override suspend fun setDefaultCalendar(calendarId: String) = Unit
        override suspend fun importIcs(text: String, calendarId: String, origin: String) =
            IcsImportResult(0, 0, 0)
        override suspend fun exportIcs(calendarId: String): String = ""
        override suspend fun copyExternalCalendar(
            source: app.tenshu.calendar.data.external.ExternalCalendar,
            snapshot: app.tenshu.calendar.data.external.ExternalSnapshot,
        ) = error("unused")
        override suspend fun subscriptions() = emptyList<CalendarSubscriptionSummary>()
        override suspend fun addSubscription(url: String, name: String): String? = null
        override suspend fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean) = Unit
        override suspend fun removeSubscription(subscriptionId: String) = Unit
        override suspend fun refreshSubscriptions() = Unit
        override suspend fun dismissReminder(reminderId: String, recurrenceId: String) = Unit
        override suspend fun snoozeReminder(
            reminderId: String,
            recurrenceId: String,
            untilEpochMs: Long,
        ) = Unit
    }

    private fun occurrence(
        date: LocalDate,
        title: String = "Event",
        hour: Int = 9,
        endsOnNextDay: Boolean = false,
    ) =
        ResolvedOccurrence(
            eventId = "$title-$date",
            calendarId = "work",
            recurrenceId = "${date}T09:00",
            title = title,
            location = "",
            notes = "",
            startLocal = LocalDateTime(date, kotlinx.datetime.LocalTime(hour, 0)),
            endLocal = if (endsOnNextDay) {
                LocalDateTime(
                    LocalDate.fromEpochDays(date.toEpochDays() + 1),
                    kotlinx.datetime.LocalTime(2, 0),
                )
            } else {
                LocalDateTime(date, kotlinx.datetime.LocalTime(hour + 1, 0))
            },
            zoneId = "Europe/Berlin",
            allDay = false,
            isOverridden = false,
            recurrenceRule = "",
        )

    private fun vm(vms: OwnedViewModels, repo: CalendarRepository) =
        vms.own(CalendarViewModel(repo, today))

    @Test
    fun it_opens_on_todays_month_with_today_selected() = vmTest { vms ->
        val state = vm(vms, FakeRepository()).uiState.value
        assertEquals(2026, state.year)
        assertEquals(8, state.month)
        assertEquals(today, state.selectedDate)
        assertEquals(CalendarView.MONTH, state.view)
    }

    /**
     * ⚠⚠ The window is the **grid's** 42 days, not the calendar month. Loading
     * only August would leave the grid's July and September cells permanently
     * empty — which reads as missing data, not as a windowing mistake.
     */
    @Test
    fun the_month_view_loads_the_whole_grid_including_adjacent_month_cells() = vmTest { vms ->
        val repo = FakeRepository()
        vm(vms, repo)

        val (from, to) = repo.windows.last()
        assertEquals(LocalDate(2026, 7, 27), from, "the grid starts in July")
        assertEquals(LocalDate(2026, 9, 6), to, "and ends in September")
    }

    /**
     * ⚠⚠ Empty is a *result*, not a loading state. Conflating the two is the
     * eternal-spinner bug `ios-drive-parity.md` P1a found in Drive, where an
     * empty folder never stopped loading.
     */
    @Test
    fun an_empty_calendar_finishes_loading_and_reports_empty() = vmTest { vms ->
        val state = vm(vms, FakeRepository()).uiState.value
        assertFalse(state.isLoading, "loading must finish even with nothing to show")
        assertTrue(state.loaded)
        assertTrue(state.isEmpty)
    }

    @Test
    fun occurrences_are_grouped_per_day_for_the_grid_dots() = vmTest { vms ->
        val repo = FakeRepository(
            listOf(
                occurrence(LocalDate(2026, 8, 12), "Standup"),
                occurrence(LocalDate(2026, 8, 12), "Review", hour = 14),
                occurrence(LocalDate(2026, 8, 13), "Lunch"),
            )
        )
        val state = vm(vms, repo).uiState.value

        assertEquals(2, state.countsByDate[LocalDate(2026, 8, 12)])
        assertEquals(1, state.countsByDate[LocalDate(2026, 8, 13)])
        assertEquals(listOf("Standup", "Review"), state.occurrencesOn(LocalDate(2026, 8, 12)).map { it.title })
        assertTrue(state.occurrencesOn(LocalDate(2026, 8, 14)).isEmpty())
    }

    // --- navigation ---------------------------------------------------------

    @Test
    fun stepping_a_month_moves_the_window() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        model.step(1)

        assertEquals(9, model.uiState.value.month)
        val (from, _) = repo.windows.last()
        assertTrue(from > LocalDate(2026, 8, 1), "the window followed the month")
    }

    /** ⚠ Paging past December must roll the year, not produce month 13. */
    @Test
    fun stepping_past_december_rolls_the_year() = vmTest { vms ->
        val model = vm(vms, FakeRepository())
        repeat(5) { model.step(1) }   // August → January
        assertEquals(2027, model.uiState.value.year)
        assertEquals(1, model.uiState.value.month)
    }

    @Test
    fun going_to_today_returns_to_this_month_and_selects_it() = vmTest { vms ->
        val model = vm(vms, FakeRepository())
        model.step(4)

        model.goToToday()

        assertEquals(8, model.uiState.value.month)
        assertEquals(today, model.uiState.value.selectedDate)
    }

    @Test
    fun selecting_a_date_does_not_reload() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        val loadsBefore = repo.windows.size

        model.selectDate(LocalDate(2026, 8, 20))

        assertEquals(LocalDate(2026, 8, 20), model.uiState.value.selectedDate)
        assertEquals(loadsBefore, repo.windows.size, "a selection is not a new window")
    }

    // --- agenda -------------------------------------------------------------

    /**
     * ⚠ The agenda looks **forward from today**, and is bounded. Occurrences are
     * expanded from rules, so an unbounded agenda is an unbounded expansion.
     */
    @Test
    fun the_agenda_loads_a_bounded_forward_window_from_today() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        model.showAgenda()

        val (from, to) = repo.windows.last()
        assertEquals(today, from)
        assertEquals(LocalDate(2026, 11, 10), to, "90 days ahead")
    }

    @Test
    fun switching_back_to_month_restores_the_grid_window() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.showAgenda()

        model.showMonth()
        model.step(0)   // force a reload without moving

        assertEquals(LocalDate(2026, 7, 27), repo.windows.last().first)
    }

    // --- the time grid (CA-4) ------------------------------------------------

    @Test
    fun the_week_view_loads_and_lays_out_the_selected_dates_whole_week() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        model.showWeek()

        val state = model.uiState.value
        assertTrue(state.isTimeGrid)
        assertEquals(7, state.schedules.size)
        assertEquals(LocalDate(2026, 8, 10), state.schedules.first().date, "Monday of today's week")
        assertEquals(LocalDate(2026, 8, 16), state.schedules.last().date)
    }

    /**
     * ⚠⚠ **The window reaches one day further back than it draws.** The
     * repository answers by an occurrence's *start* date, so a shift running
     * 22:00 Sunday to 02:00 Monday is not in a Monday-first window at all — and
     * Monday's column would show a clear morning that is not clear.
     */
    @Test
    fun the_week_window_reaches_back_a_day_so_an_overnight_event_is_not_missed() = vmTest { vms ->
        val sunday = LocalDate(2026, 8, 9)
        val repo = FakeRepository(
            listOf(occurrence(sunday, "Night shift", hour = 22, endsOnNextDay = true))
        )
        val model = vm(vms, repo)

        model.showWeek()

        val (from, to) = repo.windows.last()
        assertEquals(sunday, from, "one day before Monday")
        assertEquals(LocalDate(2026, 8, 16), to)

        val monday = model.uiState.value.schedules.first()
        assertEquals(LocalDate(2026, 8, 10), monday.date)
        assertEquals(
            listOf("Night shift"),
            monday.timed.map { it.value.title },
            "the spill-over must reach Monday's column",
        )
        assertEquals(0, monday.timed.single().topMinute, "clipped to the top of the day")
    }

    @Test
    fun the_day_view_shows_exactly_one_day() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.selectDate(LocalDate(2026, 8, 20))

        model.showDay()

        assertEquals(listOf(LocalDate(2026, 8, 20)), model.uiState.value.schedules.map { it.date })
    }

    /** ⭐ One anchor, so "tap the 20th, switch to Day" lands on the 20th. */
    @Test
    fun switching_from_month_to_day_keeps_the_day_the_user_picked() = vmTest { vms ->
        val model = vm(vms, FakeRepository())
        model.selectDate(LocalDate(2026, 8, 20))

        model.showDay()

        assertEquals(LocalDate(2026, 8, 20), model.uiState.value.schedules.single().date)
    }

    @Test
    fun stepping_the_week_view_moves_seven_days() = vmTest { vms ->
        val model = vm(vms, FakeRepository())
        model.showWeek()

        model.step(1)

        assertEquals(LocalDate(2026, 8, 17), model.uiState.value.schedules.first().date)
        assertEquals(LocalDate(2026, 8, 19), model.uiState.value.selectedDate)
    }

    @Test
    fun stepping_the_day_view_moves_one_day() = vmTest { vms ->
        val model = vm(vms, FakeRepository())
        model.showDay()

        model.step(-1)

        assertEquals(LocalDate(2026, 8, 11), model.uiState.value.schedules.single().date)
    }

    /**
     * ⚠ The month follows the anchor, so paging weeks across a boundary and
     * switching back to MONTH lands where the user actually is rather than
     * snapping back to where they left.
     */
    @Test
    fun paging_weeks_across_a_month_boundary_carries_the_month_along() = vmTest { vms ->
        val model = vm(vms, FakeRepository())
        model.showWeek()

        repeat(3) { model.step(1) }   // 12 Aug → 2 Sep

        assertEquals(9, model.uiState.value.month)
        assertEquals(2026, model.uiState.value.year)
    }

    /** ⚠ The agenda is a forward list from today; there is no page to step. */
    @Test
    fun stepping_does_nothing_in_the_agenda() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.showAgenda()
        val loadsBefore = repo.windows.size

        model.step(1)

        assertEquals(loadsBefore, repo.windows.size)
    }

    @Test
    fun the_month_and_agenda_views_carry_no_schedules() = vmTest { vms ->
        val model = vm(vms, FakeRepository())
        assertTrue(model.uiState.value.schedules.isEmpty())

        model.showAgenda()
        assertTrue(model.uiState.value.schedules.isEmpty())
        assertFalse(model.uiState.value.isTimeGrid)
    }

    @Test
    fun overlapping_events_in_the_day_view_are_laid_out_side_by_side() = vmTest { vms ->
        val repo = FakeRepository(
            listOf(
                occurrence(today, "Standup", hour = 9),
                occurrence(today, "Review", hour = 9),
            )
        )
        val model = vm(vms, repo)

        model.showDay()

        val timed = model.uiState.value.schedules.single().timed
        assertEquals(listOf(0, 1), timed.map { it.column })
        assertTrue(timed.all { it.columnCount == 2 })
    }

    // --- showMonth (CA-8b) --------------------------------------------------

    /**
     * ⭐ `step` cannot express this. A native month control pages itself and then reports where it
     * landed — "the user flicked forward three months" is not a sequence of steps we saw.
     */
    @Test
    fun showing_a_month_moves_the_grid_and_loads_it() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        val loadsBefore = repo.windows.size

        model.showMonth(2027, 3)

        assertEquals(2027, model.uiState.value.year)
        assertEquals(3, model.uiState.value.month)
        assertTrue(repo.windows.size > loadsBefore, "the new month has to be loaded, not just shown")
        // ⚠ The window is the grid's full 42 days, so it reaches into February and April — what
        // matters is that March is inside it.
        val (from, to) = repo.windows.last()
        assertTrue(from <= LocalDate(2027, 3, 1) && to >= LocalDate(2027, 3, 31), "was $from..$to")
    }

    /**
     * ⚠⚠ The guard that stops a feedback loop. `UICalendarView` reports its visible month on every
     * settle — *including* settles this call caused — so an unconditional reload here reloads the
     * window forever. "Already showing it" is a common call, not an edge case.
     */
    @Test
    fun showing_the_month_already_shown_does_not_reload() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        val loadsBefore = repo.windows.size

        model.showMonth(2026, 8)

        assertEquals(loadsBefore, repo.windows.size)
    }

    /**
     * ⚠ Scrolling the grid is not selecting a day. Moving the selection with the scroll would
     * silently change which day's events the detail list shows, from a gesture that never touched
     * it.
     */
    @Test
    fun showing_a_month_leaves_the_selected_day_alone() = vmTest { vms ->
        val model = vm(vms, FakeRepository())
        val selected = model.uiState.value.selectedDate

        model.showMonth(2027, 3)

        assertEquals(selected, model.uiState.value.selectedDate)
    }

    /** ⚠ A nonsense month is ignored rather than throwing — it can only arrive from a platform. */
    @Test
    fun a_month_out_of_range_is_ignored() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        val loadsBefore = repo.windows.size

        model.showMonth(2027, 0)
        model.showMonth(2027, 13)

        assertEquals(8, model.uiState.value.month)
        assertEquals(2026, model.uiState.value.year)
        assertEquals(loadsBefore, repo.windows.size)
    }

    /**
     * ⚠ A day can only reach [CalendarViewModel.selectDay] from a platform callback, so a
     * nonsense one is ignored rather than thrown on — clearing the selection or crashing would
     * both be worse than leaving it where the user last put it.
     */
    @Test
    fun selecting_a_nonsense_day_leaves_the_selection_alone() = vmTest { vms ->
        val model = vm(vms, FakeRepository())
        val before = model.uiState.value.selectedDate

        model.selectDay(2026, 2, 30)
        model.selectDay(2026, 13, 1)

        assertEquals(before, model.uiState.value.selectedDate)
    }

    @Test
    fun selecting_a_day_from_its_parts_selects_it() = vmTest { vms ->
        val model = vm(vms, FakeRepository())

        model.selectDay(2026, 8, 20)

        assertEquals(LocalDate(2026, 8, 20), model.uiState.value.selectedDate)
    }

    // --- visibility ---------------------------------------------------------

    /**
     * ⚠⚠ **This replaces a test that asserted the bug.** The old one required a toggle
     * to *reload the window* (`repo.windows.size > loadsBefore`) — and that reload is
     * exactly what broke the CA-2f sidebar: it re-read the preference the write had
     * only just been handed, got the pre-write value back, and redrew the grid
     * unchanged. A green test pinned the mechanism the whole time, because the fake
     * applied writes synchronously and the store does not.
     */
    @Test
    fun hiding_a_calendar_removes_its_events_at_once_and_without_a_reload() = vmTest { vms ->
        val repo = FakeRepository(
            occurrences = listOf(occurrence(LocalDate(2026, 8, 12))),
            calendars = listOf(CalendarSummary("work", "Work", 0, false, true)),
        )
        val model = vm(vms, repo)
        val loadsBefore = repo.windows.size

        model.setCalendarVisible("work", visible = false)

        assertEquals(listOf("work" to false), repo.visibilityWrites)
        assertTrue(model.uiState.value.occurrences.isEmpty(), "the events are still on the grid")
        assertFalse(model.uiState.value.calendars.single().visible, "the checkbox did not move")
        assertEquals(
            loadsBefore,
            repo.windows.size,
            "the window was reloaded — that read is the one that observes the pre-write value",
        )
    }

    @Test
    fun unhiding_brings_the_events_back_from_memory() = vmTest { vms ->
        val repo = FakeRepository(
            occurrences = listOf(occurrence(LocalDate(2026, 8, 12))),
            calendars = listOf(CalendarSummary("work", "Work", 0, false, true)),
        )
        val model = vm(vms, repo)
        model.setCalendarVisible("work", visible = false)
        val loadsBefore = repo.windows.size

        model.setCalendarVisible("work", visible = true)

        assertEquals(1, model.uiState.value.occurrences.size, "un-hiding lost the events")
        assertEquals(
            loadsBefore,
            repo.windows.size,
            "the window is loaded with includeHidden = true so this needs no round trip",
        )
    }

    /**
     * The other half of "collect, never poll": the store is what *corrects* the
     * optimistic update — whether the change came from this screen, the calendars
     * screen, or another device once the preference syncs.
     */
    @Test
    fun a_hidden_set_arriving_from_the_store_updates_the_grid() = vmTest { vms ->
        val repo = FakeRepository(
            occurrences = listOf(occurrence(LocalDate(2026, 8, 12))),
            calendars = listOf(CalendarSummary("work", "Work", 0, false, true)),
        )
        val model = vm(vms, repo)

        repo.hidden.value = setOf("work")

        assertTrue(model.uiState.value.occurrences.isEmpty())
        assertFalse(model.uiState.value.calendars.single().visible)
    }

    // --- live reads (CA-2h) --------------------------------------------------

    /**
     * ⭐ The gap CA-2g recorded, closed: an event that appears while the screen is
     * open — created by Mia, imported from an `.ics`, pulled by a subscription, or
     * synced from another device — lands on the grid **with nobody calling
     * `refresh()`**. Before this, it waited for a page step or a relaunch.
     */
    @Test
    fun an_event_arriving_while_the_screen_is_open_lands_on_the_grid() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        assertTrue(model.uiState.value.occurrences.isEmpty())

        repo.rows.value = listOf(occurrence(LocalDate(2026, 8, 12), title = "Booked elsewhere"))

        assertEquals(
            listOf("Booked elsewhere"),
            model.uiState.value.occurrences.map { it.title },
        )
    }

    @Test
    fun a_calendar_renamed_elsewhere_arrives_too() = vmTest { vms ->
        val repo = FakeRepository(calendars = listOf(CalendarSummary("work", "Work", 0, false, true)))
        val model = vm(vms, repo)

        repo.calendarRows.value = listOf(CalendarSummary("work", "Team", 0, false, true))

        assertEquals(listOf("Team"), model.uiState.value.calendars.map { it.name })
    }

    /**
     * ⚠⚠ Paging **swaps** the subscription rather than adding one. A left-running
     * subscription would keep emitting the old window into the same state — and the
     * bug that produces (the grid flipping between two months as rows change) is far
     * harder to read than a missing update.
     */
    @Test
    fun paging_leaves_only_the_new_windows_subscription_live() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.step(1)   // → September 2026

        repo.rows.value = listOf(
            occurrence(LocalDate(2026, 8, 12), title = "August"),
            occurrence(LocalDate(2026, 9, 15), title = "September"),
        )

        assertEquals(
            listOf("September"),
            model.uiState.value.occurrences.map { it.title },
            "the previous window's subscription is still feeding the screen",
        )
    }

    /** ⚠ A write that never landed must not leave the screen lying about it. */
    @Test
    fun the_store_wins_over_an_optimistic_toggle_that_did_not_land() = vmTest { vms ->
        val repo = FakeRepository(
            occurrences = listOf(occurrence(LocalDate(2026, 8, 12))),
            calendars = listOf(CalendarSummary("work", "Work", 0, false, true)),
        )
        val model = vm(vms, repo)
        model.setCalendarVisible("work", visible = false)
        assertTrue(model.uiState.value.occurrences.isEmpty())

        // The store reports what it actually holds: nothing hidden.
        repo.hidden.value = setOf("something-else")

        assertEquals(1, model.uiState.value.occurrences.size, "the screen kept a write that was refused")
        assertTrue(model.uiState.value.calendars.single().visible)
    }
}
