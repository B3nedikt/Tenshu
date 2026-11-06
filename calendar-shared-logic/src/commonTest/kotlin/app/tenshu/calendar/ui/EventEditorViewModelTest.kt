package app.tenshu.calendar.ui

import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.CalendarSubscriptionSummary
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.data.IcsImportResult
import app.tenshu.calendar.data.EditScope
import app.tenshu.calendar.data.EventDraft
import app.tenshu.calendar.model.RecurrencePreset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence
import org.tenshu.viewmodel.test.vmTest
import org.tenshu.viewmodel.test.OwnedViewModels

/**
 * The event editor's state machine (CA-3).
 *
 * ⚠⚠ Most of what is asserted here is *when the scope prompt appears*. The form
 * fields are Material 3's problem; deciding whether an edit means "this one" or
 * "all of them" is the only thing in this screen that can silently destroy the
 * user's data, in either direction.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EventEditorViewModelTest {

    /** ⚠ `viewModelScope` dispatches on `Dispatchers.Main`, absent off Android. */
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val date = LocalDate(2026, 8, 3)   // a Monday

    private class FakeRepository(
        /** ⚠ Live since CA-2i: the chooser's options are collected, so a test can add one. */
        val calendarsLive: MutableStateFlow<List<CalendarSummary>> = MutableStateFlow(listOf(
            CalendarSummary("work", "Work", 0x4285F4, sensitive = false, visible = true),
            CalendarSummary("personal", "Personal", 0x0F9D58, sensitive = false, visible = true),
        )),
        private val failWith: Throwable? = null,
    ) : CalendarRepository {
        val savedSeries = mutableListOf<EventDraft>()
        val savedOccurrences = mutableListOf<Pair<EventDraft, String>>()
        val deletedSeries = mutableListOf<String>()
        val deletedOccurrences = mutableListOf<Pair<String, String>>()

        override suspend fun calendars() = calendarsLive.value
        /**
         * ⚠⚠ What the store holds. It used to be permanently empty, which was a lie the
         * editor never asked about until CA-2k — and under the conflict check every save
         * then read as "the event is gone". A fake that accepts writes must be able to
         * answer for them.
         */
        var stored: List<ResolvedOccurrence> = emptyList()

        override suspend fun occurrences(from: LocalDate, to: LocalDate, includeHidden: Boolean) =
            stored.filter { it.startLocal.date in from..to }
        override suspend fun setCalendarVisible(calendarId: String, visible: Boolean) = Unit

        override fun observeHiddenCalendars(): Flow<Set<String>> = flowOf(emptySet())

        override fun observeOccurrences(
            from: LocalDate,
            to: LocalDate,
        ): Flow<List<ResolvedOccurrence>> = flowOf(emptyList())

        // ⚠ The chooser's options are collected now, so the fake must publish them
        // here — returning an empty flow made every save fail with "no calendar", which
        // is the shape of a fake lying about a read the code under test depends on.
        override fun observeCalendars(): Flow<List<CalendarSummary>> = calendarsLive
        override suspend fun ensureDefaultCalendar() = Unit

        override fun observeSubscriptions(): Flow<List<CalendarSubscriptionSummary>> =
            flowOf(emptyList())

        /** The default-calendar preference, live (it can arrive after the form opened). */
        val defaultLive = MutableStateFlow("")
        override fun observeDefaultCalendar(): Flow<String> = defaultLive

        override suspend fun saveEvent(draft: EventDraft): String {
            failWith?.let { throw it }
            savedSeries += draft
            return draft.eventId.ifBlank { "minted" }
        }

        override suspend fun saveOccurrence(draft: EventDraft, recurrenceId: String) {
            failWith?.let { throw it }
            savedOccurrences += draft to recurrenceId
        }

        override suspend fun deleteEvent(eventId: String) {
            failWith?.let { throw it }
            deletedSeries += eventId
        }

        override suspend fun deleteOccurrence(eventId: String, recurrenceId: String) {
            failWith?.let { throw it }
            deletedOccurrences += eventId to recurrenceId
        }

        // --- reminders (CA-5e) ---
        var existingReminders: List<Int> = emptyList()
        val reminderWrites = mutableListOf<Pair<String, List<Int>>>()

        override suspend fun remindersFor(eventId: String): List<Int> = existingReminders

        /** ⚠ Keyed on the flag, so a test can prove the *right* one was asked for. */
        var timedDefault: Int? = null
        var allDayDefault: Int? = null
        val defaultReads = mutableListOf<Boolean>()

        /**
         * Holds one *kind* of read open, so a test can act while it is in flight.
         *
         * ⚠⚠ Per kind, not one gate for both. A single gate releases the timed and
         * the all-day read together and the fresh answer lands last anyway — which
         * makes a stale-answer test pass whether or not the staleness check exists.
         */
        private val gates = mutableMapOf<Boolean, CompletableDeferred<Unit>>()

        fun hold(allDay: Boolean) { gates[allDay] = CompletableDeferred() }
        fun release(allDay: Boolean) { gates.remove(allDay)?.complete(Unit) }

        override suspend fun defaultReminderMinutes(allDay: Boolean): Int? {
            defaultReads += allDay
            gates[allDay]?.await()
            return if (allDay) allDayDefault else timedDefault
        }

        override suspend fun setReminders(eventId: String, minutesBefore: List<Int>) {
            failWith?.let { throw it }
            reminderWrites += eventId to minutesBefore
        }

        override suspend fun dismissReminder(reminderId: String, recurrenceId: String) = Unit
        override suspend fun saveCalendar(
            calendarId: String,
            name: String,
            colorArgb: Int,
            sensitive: Boolean,
        ): String = calendarId.ifBlank { "minted" }
        override suspend fun deleteCalendar(calendarId: String) = Unit
        override suspend fun defaultCalendar(): String = defaultLive.value
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
        override suspend fun snoozeReminder(
            reminderId: String,
            recurrenceId: String,
            untilEpochMs: Long,
        ) = Unit
    }

    private fun vm(vms: OwnedViewModels, repo: CalendarRepository =
        FakeRepository()) = vms.own(EventEditorViewModel(repo, date))

    /**
     * Opens [occurrence] for editing **and** puts it in the store it came from.
     *
     * ⚠ Both halves, always: the CA-2k conflict check re-reads the row at save time, so
     * a form loaded from a row the store does not have is a form whose every save is a
     * conflict. That is correct behaviour for a deleted event and a wrong fixture for
     * everything else.
     */
    private fun EventEditorViewModel.open(
        occurrence: ResolvedOccurrence,
        into: CalendarRepository,
    ) = apply {
        (into as FakeRepository).stored = listOf(occurrence)
        load(occurrence)
    }

    // --- live inputs, and where live stops (CA-2i) ----------------------------

    @Test
    fun a_calendar_created_while_the_editor_is_open_appears_in_the_chooser() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        repo.calendarsLive.value = repo.calendarsLive.value +
            CalendarSummary("new", "Side project", 0x000000, sensitive = false, visible = true)

        assertEquals(
            listOf("Work", "Personal", "Side project"),
            model.uiState.value.calendars.map { it.name },
        )
    }

    /**
     * ⚠⚠ **The boundary, and the reason the form is not reactive.** A list can be
     * replaced under the user; a field they are typing into cannot. This asserts that
     * an emission arriving mid-edit changes the chooser's options and touches nothing
     * the user has put in — the failure it guards against is "the app ate my edit",
     * which is worse than any staleness it could fix.
     */
    @Test
    fun a_change_arriving_mid_edit_does_not_touch_what_the_user_typed() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setTitle("Lunch with Anna")
        model.setCalendar("personal")

        repo.calendarsLive.value = repo.calendarsLive.value +
            CalendarSummary("new", "Side project", 0x000000, sensitive = false, visible = true)

        assertEquals("Lunch with Anna", model.uiState.value.title, "the title was overwritten")
        assertEquals("personal", model.uiState.value.calendarId, "the event was re-filed")
        assertEquals(3, model.uiState.value.calendars.size, "the chooser did not update")
    }

    /** The 10th of August 2026 is a Monday — the weekday `FREQ=WEEKLY;BYDAY=MO` names. */
    private fun occurrence(
        rrule: String = "",
        allDay: Boolean = false,
        title: String = "Standup",
        startLocal: LocalDateTime = LocalDateTime(LocalDate(2026, 8, 10), LocalTime(9, 0)),
        isOverridden: Boolean = false,
    ) = ResolvedOccurrence(
        eventId = "standup",
        calendarId = "work",
        recurrenceId = "2026-08-10T09:00",
        title = title,
        location = "Room A",
        notes = "bring laptop",
        startLocal = startLocal,
        endLocal = LocalDateTime(startLocal.date, LocalTime(startLocal.hour, startLocal.minute + 30)),
        zoneId = "Europe/Berlin",
        allDay = allDay,
        isOverridden = isOverridden,
        recurrenceRule = rrule,
    )

    // --- read-only calendars (subscribed / mirrored) ---------------------------

    /** ⚠⚠ An event filed into a subscribed or mirrored calendar is deleted by its next refresh. */
    @Test
    fun a_read_only_calendar_is_never_offered_or_preselected() = vmTest { vms ->
        val repo = FakeRepository(MutableStateFlow(listOf(
            CalendarSummary("feed", "A feed", 0, sensitive = false, visible = true, readOnly = true),
            CalendarSummary("work", "Work", 0, sensitive = false, visible = true),
            CalendarSummary("personal", "Personal", 0, sensitive = false, visible = true),
        )))
        val model = vm(vms, repo)

        assertEquals("work", model.uiState.value.calendarId, "the read-only one sorts first and is skipped")
        assertEquals(listOf("work", "personal"), model.uiState.value.writableCalendars.map { it.calendarId })
        model.setCalendar("feed")
        assertEquals("work", model.uiState.value.calendarId, "and cannot be picked")
    }

    /** ⚠⚠ Shown, never saved, deleted or moved — by any of the public entry points. */
    @Test
    fun an_event_in_a_read_only_calendar_cannot_be_changed() = vmTest { vms ->
        val repo = FakeRepository(MutableStateFlow(listOf(
            CalendarSummary("work", "Work", 0, sensitive = false, visible = true, readOnly = true),
            CalendarSummary("personal", "Personal", 0, sensitive = false, visible = true),
        )))
        val model = vm(vms, repo).open(occurrence(), into = repo)

        val state = model.uiState.value
        assertTrue(state.isReadOnly)
        assertFalse(state.canSave)
        model.setTitle("Renamed")
        model.save()
        model.commit(EditScope.ALL_OCCURRENCES)
        model.setCalendar("personal")
        model.delete()
        model.commitDelete(EditScope.ALL_OCCURRENCES)

        assertEquals("work", model.uiState.value.calendarId, "not moved out either")
        assertTrue(repo.savedSeries.isEmpty() && repo.savedOccurrences.isEmpty())
        assertTrue(repo.deletedSeries.isEmpty() && repo.deletedOccurrences.isEmpty())
    }

    // --- creating -----------------------------------------------------------

    @Test
    fun a_new_event_opens_on_the_passed_date_with_the_first_calendar_preselected() = vmTest { vms ->
        val state = vm(vms, ).uiState.value

        assertEquals(date, state.startDate)
        assertEquals("work", state.calendarId)
        assertFalse(state.isEditing)
        assertEquals(RecurrencePreset.NEVER, state.recurrence)
    }

    /** ⚠ A form you cannot save is fine; one you can save into nothing is not. */
    @Test
    fun saving_is_blocked_until_the_event_has_a_title() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        assertFalse(model.uiState.value.canSave)

        model.setTitle("Standup")

        assertTrue(model.uiState.value.canSave)
    }

    @Test
    fun a_blank_titled_save_writes_nothing() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        model.setTitle("   ")
        model.save()

        assertTrue(repo.savedSeries.isEmpty())
    }

    @Test
    fun creating_writes_a_series_with_a_blank_id_and_reports_saved() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setTitle("Standup")
        model.setCalendar("personal")
        model.setStartTime(LocalTime(9, 0))
        model.setEndTime(LocalTime(9, 30))

        model.save()

        val draft = repo.savedSeries.single()
        assertEquals("", draft.eventId, "a create must not invent an id here — the repository mints it")
        assertEquals("personal", draft.calendarId)
        assertEquals("2026-08-03T09:00", draft.startLocal)
        assertEquals("2026-08-03T09:30", draft.endLocal)
        assertTrue(model.uiState.value.saved)
    }

    /** ⚠ Whitespace the user could not see must not become part of the title. */
    @Test
    fun free_text_is_trimmed_on_the_way_out() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setTitle("  Standup  ")
        model.setLocation("  Room A ")
        model.setNotes(" bring laptop ")

        model.save()

        val draft = repo.savedSeries.single()
        assertEquals("Standup", draft.title)
        assertEquals("Room A", draft.location)
        assertEquals("bring laptop", draft.notes)
    }

    /**
     * ⚠⚠ Tapping a spot in the time grid already said *when*. Re-defaulting to
     * 09:00 would throw that away and make the tap indistinguishable from
     * pressing the create button.
     */
    @Test
    fun creating_from_a_tapped_time_starts_there_and_keeps_the_default_duration() = vmTest { vms ->
        val model = vms.own(EventEditorViewModel(FakeRepository(), date, LocalTime(14, 30)))

        assertEquals(LocalTime(14, 30), model.uiState.value.startTime)
        assertEquals(LocalTime(15, 30), model.uiState.value.endTime)
    }

    /** ⚠ A tap late in the evening must not roll the end into the next day. */
    @Test
    fun a_tapped_time_near_midnight_clamps_rather_than_wrapping() = vmTest { vms ->
        val model = vms.own(EventEditorViewModel(FakeRepository(), date, LocalTime(23, 30)))

        assertTrue(model.uiState.value.endTime >= model.uiState.value.startTime)
        assertEquals(23, model.uiState.value.endTime.hour)
    }

    @Test
    fun creating_without_a_tapped_time_keeps_the_forms_own_default() = vmTest { vms ->
        val state = vm(vms, ).uiState.value

        assertEquals(LocalTime(9, 0), state.startTime)
        assertEquals(LocalTime(10, 0), state.endTime)
    }

    // --- times --------------------------------------------------------------

    /**
     * ⭐ Moving the start drags the end, preserving the duration. Without this,
     * setting a later start silently produces a negative-length event and the
     * save button goes dead with no explanation.
     */
    @Test
    fun moving_the_start_carries_the_end_along() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setStartTime(LocalTime(9, 0))
        model.setEndTime(LocalTime(10, 30))    // 90 minutes

        model.setStartTime(LocalTime(14, 0))

        assertEquals(LocalTime(15, 30), model.uiState.value.endTime)
    }

    /** ⚠ Dragging past midnight has to clamp, not wrap into the previous day. */
    @Test
    fun dragging_the_start_late_clamps_the_end_to_the_same_day() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setStartTime(LocalTime(9, 0))
        model.setEndTime(LocalTime(11, 0))

        model.setStartTime(LocalTime(23, 0))

        assertTrue(model.uiState.value.endTime >= model.uiState.value.startTime)
    }

    /** An end set before the start blocks the save and says so. */
    @Test
    fun an_end_before_the_start_is_reported_rather_than_silently_disabling_save() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setTitle("Standup")
        model.setStartTime(LocalTime(14, 0))
        model.setEndTime(LocalTime(9, 0))

        assertTrue(model.uiState.value.endBeforeStart)
        assertFalse(model.uiState.value.canSave)
    }

    /**
     * ⚠⚠ An all-day event is a **date range with an exclusive end** (§3 D3), so
     * it ends at 00:00 the following day. Storing 23:59 would make the last
     * minute of the day not part of it; storing the picked time would ignore
     * what "all day" means.
     */
    @Test
    fun an_all_day_event_spans_midnight_to_midnight() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setTitle("Holiday")
        model.setStartTime(LocalTime(14, 0))   // deliberately non-midnight
        model.setAllDay(true)

        model.save()

        val draft = repo.savedSeries.single()
        assertEquals("2026-08-03T00:00", draft.startLocal)
        assertEquals("2026-08-04T00:00", draft.endLocal, "exclusive end = the next day")
        assertTrue(draft.allDay)
    }

    /** ⚠ The time comparison must not block an all-day save — its times are ignored. */
    @Test
    fun an_all_day_event_saves_regardless_of_the_picked_times() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setTitle("Holiday")
        model.setStartTime(LocalTime(14, 0))
        model.setEndTime(LocalTime(9, 0))

        model.setAllDay(true)

        assertTrue(model.uiState.value.canSave)
    }

    // --- recurrence ---------------------------------------------------------

    @Test
    fun a_chosen_recurrence_becomes_an_rrule_anchored_on_the_start_date() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setTitle("Standup")
        model.setRecurrence(RecurrencePreset.WEEKLY)

        model.save()

        assertEquals("FREQ=WEEKLY;BYDAY=MO", repo.savedSeries.single().recurrenceRule)
    }

    @Test
    fun a_one_off_stores_no_rule() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setTitle("Standup")

        model.save()

        assertEquals("", repo.savedSeries.single().recurrenceRule)
    }

    /**
     * ⚠⚠ A rule the editor cannot model is shown but not offered. Letting the
     * control change it would rewrite `FREQ=MONTHLY;BYSETPOS=-1` into plain
     * `FREQ=MONTHLY` the moment the user touched the title — §3 D2's "refuse to
     * edit rather than silently rewrite".
     */
    @Test
    fun a_custom_rule_is_read_only_and_cannot_be_replaced_by_a_preset() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(rrule = "FREQ=MONTHLY;BYSETPOS=-1;BYDAY=FR"), repo)

        assertEquals(RecurrencePreset.CUSTOM, model.uiState.value.recurrence)
        assertTrue(model.uiState.value.recurrenceIsReadOnly)

        model.setRecurrence(RecurrencePreset.DAILY)

        assertEquals(RecurrencePreset.CUSTOM, model.uiState.value.recurrence, "the rule must survive the attempt")
    }

    // --- editing: a change that arrived while you were typing (CA-2k) --------

    /**
     * ⭐⭐ The case the whole thing exists for: the other device moved the event while
     * this form was open. The save **writes nothing** and asks — because an editor that
     * quietly overwrote it would make the other person's change vanish with no trace
     * that it ever existed.
     */
    @Test
    fun a_save_over_someone_elses_change_asks_instead_of_overwriting() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        model.setTitle("Standup, moved")

        // The other device renamed it in the meantime.
        repo.stored = listOf(occurrence(title = "Sprint sync"))
        model.save()

        assertEquals("Sprint sync", model.uiState.value.conflict?.theirs?.title)
        assertTrue(repo.savedSeries.isEmpty(), "it wrote over the other change anyway")
        assertFalse(model.uiState.value.isSaving, "the form is stuck spinning behind the prompt")
    }

    @Test
    fun an_unchanged_event_saves_straight_through_without_asking() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        model.setTitle("Standup, moved")

        model.save()

        assertNull(model.uiState.value.conflict)
        assertEquals(listOf("Standup, moved"), repo.savedSeries.map { it.title })
    }

    /** ⚠ Keeping mine is a decision to overwrite — it must not re-ask. */
    @Test
    fun keeping_mine_writes_the_form_as_it_stands() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        model.setTitle("Mine")
        repo.stored = listOf(occurrence(title = "Theirs"))
        model.save()

        model.keepMine()

        assertEquals(listOf("Mine"), repo.savedSeries.map { it.title })
        assertNull(model.uiState.value.conflict)
    }

    /**
     * ⚠⚠ Taking theirs re-seeds the *baseline* too, not just the fields. Without that
     * the next save would conflict against a version the user has already abandoned —
     * a prompt they can never get past.
     */
    @Test
    fun taking_theirs_replaces_the_form_and_the_baseline_with_it() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        model.setTitle("Mine")
        repo.stored = listOf(occurrence(title = "Theirs"))
        model.save()

        model.useTheirs()
        assertEquals("Theirs", model.uiState.value.title)
        assertTrue(repo.savedSeries.isEmpty(), "taking theirs is not a write")

        model.save()
        assertNull(model.uiState.value.conflict, "it asked again about a version already accepted")
        assertEquals(listOf("Theirs"), repo.savedSeries.map { it.title })
    }

    /** ⚠ Keeping both leaves theirs alone and files mine as a new event — hence a create. */
    @Test
    fun keeping_both_saves_mine_as_a_new_event() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        model.setTitle("Mine")
        repo.stored = listOf(occurrence(title = "Theirs"))
        model.save()

        model.saveBoth()

        assertEquals(listOf("Mine"), repo.savedSeries.map { it.title })
        assertEquals("", repo.savedSeries.single().eventId, "it overwrote theirs instead of adding")
    }

    /**
     * ⚠ Deleted — or moved to another day by the other device. The two are not
     * distinguished, so the offer is "save anyway" rather than a claim about which.
     */
    @Test
    fun an_event_that_is_no_longer_there_asks_before_recreating_it() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        repo.stored = emptyList()

        model.save()

        assertNotNull(model.uiState.value.conflict)
        assertNull(model.uiState.value.conflict?.theirs)
        assertTrue(repo.savedSeries.isEmpty())
    }

    /**
     * ⚠⚠ A read that fails is **not** a conflict. The save will fail on its own and say
     * so; refusing to write on an unreachable proxy would turn every outage into a
     * prompt about a change nobody made.
     */
    @Test
    fun a_failed_check_does_not_invent_a_conflict() = vmTest { vms ->
        val repo = FakeRepository(failWith = IllegalStateException("no proxy"))
        val model = vm(vms, repo)
        model.open(occurrence(), repo)

        model.save()

        assertNull(model.uiState.value.conflict)
    }

    // --- deleting over someone else's change (CA-2l) -------------------------

    /**
     * ⚠⚠ A delete destroys more than it did when the user asked for it: the other
     * device's change goes with the event. That is worth one question — and the copy
     * says so rather than repeating the save prompt's wording.
     */
    @Test
    fun deleting_an_event_someone_else_just_changed_asks_first() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        repo.stored = listOf(occurrence(title = "Moved to Thursday"))

        model.delete()

        assertEquals(ConflictIntent.DELETE, model.uiState.value.conflict?.intent)
        assertEquals("Moved to Thursday", model.uiState.value.conflict?.theirs?.title)
        assertTrue(repo.deletedSeries.isEmpty(), "it deleted over the other change anyway")
        assertFalse(model.uiState.value.deleted)
    }

    @Test
    fun deleting_an_unchanged_event_goes_straight_through() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)

        model.delete()

        assertNull(model.uiState.value.conflict)
        assertEquals(listOf("standup"), repo.deletedSeries)
        assertTrue(model.uiState.value.deleted)
    }

    /**
     * ⭐⭐ **Already deleted elsewhere is a success, not a conflict.** The user's intent
     * is already the world's state, so asking permission would be asking about something
     * nobody disagrees with and nobody can undo.
     *
     * ⚠ And it reports `deleted` **without calling the op**: whether deleting a missing
     * row throws is the op's business, and the honest outcome here does not depend on it.
     */
    @Test
    fun an_event_already_deleted_elsewhere_just_closes() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        repo.stored = emptyList()

        model.delete()

        assertNull(model.uiState.value.conflict, "it asked about a deletion that already happened")
        assertTrue(model.uiState.value.deleted)
        assertTrue(repo.deletedSeries.isEmpty(), "it called delete on a row that was gone")
    }

    @Test
    fun deleting_anyway_goes_ahead_with_the_scope_that_was_asked_for() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        repo.stored = listOf(occurrence(title = "Theirs"))
        model.delete()

        model.keepMine()

        assertEquals(listOf("standup"), repo.deletedSeries)
        assertTrue(model.uiState.value.deleted)
    }

    /** ⚠ For a delete, taking theirs is what *cancels* it — the event stays and is shown. */
    @Test
    fun keeping_theirs_cancels_the_delete_and_shows_their_version() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        repo.stored = listOf(occurrence(title = "Theirs"))
        model.delete()

        model.useTheirs()

        assertTrue(repo.deletedSeries.isEmpty(), "cancelling the delete still deleted it")
        assertFalse(model.uiState.value.deleted)
        assertEquals("Theirs", model.uiState.value.title)
    }

    // --- editing: the scope prompt ------------------------------------------

    @Test
    fun loading_an_occurrence_fills_the_form_from_it() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(rrule = "FREQ=WEEKLY;BYDAY=MO"), repo)
        val state = model.uiState.value

        assertTrue(state.isEditing)
        assertEquals("Standup", state.title)
        assertEquals("Room A", state.location)
        assertEquals(LocalDate(2026, 8, 10), state.startDate)
        assertEquals(LocalTime(9, 0), state.startTime)
        assertEquals(LocalTime(9, 30), state.endTime)
        assertEquals(RecurrencePreset.WEEKLY, state.recurrence)
    }

    /**
     * ⚠⚠ **The rule is classified against the occurrence's ORIGINAL date.** An
     * override that moved this Monday standup to a Wednesday leaves the series
     * weekly-on-Monday; classifying `BYDAY=MO` against the *effective* Wednesday
     * would call it `CUSTOM`, and the editor would then refuse to edit a rule it
     * wrote itself.
     */
    @Test
    fun a_moved_occurrence_still_recognises_its_own_series_rule() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        model.open(
            occurrence(
                rrule = "FREQ=WEEKLY;BYDAY=MO",
                startLocal = LocalDateTime(LocalDate(2026, 8, 12), LocalTime(9, 0)),   // a Wednesday
                isOverridden = true,
            ),
            repo,
        )

        assertEquals(RecurrencePreset.WEEKLY, model.uiState.value.recurrence)
        assertFalse(model.uiState.value.recurrenceIsReadOnly)
    }

    /**
     * ⚠⚠ A create opens on the configured default — the calendar the Calendars screen calls
     * "Default for new events" — not on whichever calendar happens to come first.
     */
    @Test
    fun a_create_preselects_the_default_calendar() = vmTest { vms ->
        val repo = FakeRepository().apply { defaultLive.value = "personal" }
        val model = vm(vms, repo)
        assertEquals("personal", model.uiState.value.calendarId)
    }

    /** A default that names no existing calendar falls back to the first one. */
    @Test
    fun a_dangling_default_falls_back_to_the_first_calendar() = vmTest { vms ->
        val repo = FakeRepository().apply { defaultLive.value = "gone" }
        val model = vm(vms, repo)
        assertEquals("work", model.uiState.value.calendarId)
    }

    /** A default that arrives after the form opened still wins — nobody had chosen yet. */
    @Test
    fun a_default_arriving_late_replaces_the_automatic_pick() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        assertEquals("work", model.uiState.value.calendarId)

        repo.defaultLive.value = "personal"

        assertEquals("personal", model.uiState.value.calendarId)
    }

    /** ⚠ …but never over a calendar the user picked. */
    @Test
    fun a_late_default_never_replaces_the_users_pick() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setCalendar("work")

        repo.defaultLive.value = "personal"

        assertEquals("work", model.uiState.value.calendarId)
    }

    /** ⚠ …nor over an edited event's own calendar. */
    @Test
    fun a_default_never_re_files_an_edited_event() = vmTest { vms ->
        val repo = FakeRepository().apply { defaultLive.value = "personal" }
        val model = vm(vms, repo)
        model.open(occurrence(), repo)

        repo.defaultLive.value = "personal2"
        repo.calendarsLive.value = repo.calendarsLive.value +
            CalendarSummary("personal2", "Personal 2", 0x0F9D58, sensitive = false, visible = true)

        assertEquals("work", model.uiState.value.calendarId)
    }

    /** ⚠ An edit must not silently re-file the event into "the first calendar". */
    @Test
    fun loading_keeps_the_events_own_calendar() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        assertEquals("work", model.uiState.value.calendarId)
    }

    /**
     * ⚠⚠ The central case: editing one occurrence of a series raises the prompt
     * and writes **nothing** until it is answered.
     */
    @Test
    fun editing_a_recurring_event_asks_before_writing_anything() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(rrule = "FREQ=WEEKLY;BYDAY=MO"), repo)
        model.setTitle("Standup (moved)")

        model.save()

        assertTrue(model.uiState.value.askingScope)
        assertTrue(repo.savedSeries.isEmpty() && repo.savedOccurrences.isEmpty(), "no write before the answer")
        assertFalse(model.uiState.value.saved)
    }

    @Test
    fun answering_this_occurrence_writes_an_override_keyed_to_the_original_start() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(rrule = "FREQ=WEEKLY;BYDAY=MO"), repo)
        model.setTitle("Standup (moved)")
        model.save()

        model.commit(EditScope.THIS_OCCURRENCE)

        val (draft, recurrenceId) = repo.savedOccurrences.single()
        assertEquals("2026-08-10T09:00", recurrenceId)
        assertEquals("Standup (moved)", draft.title)
        assertTrue(repo.savedSeries.isEmpty(), "the series itself must be untouched")
        assertFalse(model.uiState.value.askingScope)
        assertTrue(model.uiState.value.saved)
    }

    @Test
    fun answering_all_occurrences_writes_the_series() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(rrule = "FREQ=WEEKLY;BYDAY=MO"), repo)
        model.setTitle("Standup (moved)")
        model.save()

        model.commit(EditScope.ALL_OCCURRENCES)

        assertEquals("standup", repo.savedSeries.single().eventId)
        assertTrue(repo.savedOccurrences.isEmpty())
    }

    @Test
    fun dismissing_the_prompt_leaves_the_form_intact_and_unsaved() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(rrule = "FREQ=WEEKLY;BYDAY=MO"), repo)
        model.setTitle("Standup (moved)")
        model.save()

        model.dismissScopePrompt()

        assertFalse(model.uiState.value.askingScope)
        assertFalse(model.uiState.value.saved)
        assertEquals("Standup (moved)", model.uiState.value.title, "the edit must not be discarded")
        assertTrue(repo.savedSeries.isEmpty() && repo.savedOccurrences.isEmpty())
    }

    /**
     * ⚠⚠ The prompt must NOT appear for a one-off — a dialog with one meaningful
     * answer trains people to dismiss dialogs without reading them, which is
     * exactly how the recurring case then gets answered wrongly.
     */
    @Test
    fun editing_a_one_off_saves_straight_through_without_asking() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        model.setTitle("Coffee")

        model.save()

        assertFalse(model.uiState.value.askingScope)
        assertEquals("standup", repo.savedSeries.single().eventId)
    }

    /** ⚠ Nor for a create: there is nothing yet for "this occurrence" to mean. */
    @Test
    fun creating_a_recurring_event_does_not_ask() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setTitle("Standup")
        model.setRecurrence(RecurrencePreset.WEEKLY)

        model.save()

        assertFalse(model.uiState.value.askingScope)
        assertEquals(1, repo.savedSeries.size)
    }

    // --- reminders (CA-5e) ---------------------------------------------------

    @Test
    fun a_new_event_starts_with_no_reminders_when_the_default_is_off() = vmTest { vms ->
        assertTrue(vm(vms, ).uiState.value.reminderMinutes.isEmpty())
    }

    @Test
    fun toggling_adds_then_removes_an_offset_and_keeps_the_list_sorted() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        model.toggleReminder(30)
        model.toggleReminder(10)
        assertEquals(listOf(10, 30), model.uiState.value.reminderMinutes)

        model.toggleReminder(10)
        assertEquals(listOf(30), model.uiState.value.reminderMinutes)
    }

    /** ⚠ Toggling the same offset twice must not leave two of it. */
    @Test
    fun an_offset_cannot_be_added_twice() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        model.toggleReminder(15)
        model.toggleReminder(15)
        model.toggleReminder(15)

        assertEquals(listOf(15), model.uiState.value.reminderMinutes)
    }

    /**
     * ⚠⚠ **Reminders are written after the event, with the id the save
     * returned.** A create has no id until then, so a reminder filed against a
     * blank one would be orphaned by construction — and invisible, because
     * nothing would ever resolve an occurrence for it.
     */
    @Test
    fun creating_writes_reminders_against_the_minted_id() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setTitle("Standup")
        model.toggleReminder(15)

        model.save()

        assertEquals(listOf("minted" to listOf(15)), repo.reminderWrites)
    }

    @Test
    fun editing_writes_reminders_against_the_existing_id() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        model.toggleReminder(60)

        model.save()

        assertEquals(listOf("standup" to listOf(60)), repo.reminderWrites)
    }

    /** ⚠ Clearing the last reminder has to be expressible — an empty list is a write. */
    @Test
    fun removing_every_reminder_writes_an_empty_list_rather_than_skipping() = vmTest { vms ->
        val repo = FakeRepository().apply { existingReminders = listOf(15) }
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        assertEquals(listOf(15), model.uiState.value.reminderMinutes)

        model.toggleReminder(15)
        model.save()

        assertEquals(listOf("standup" to emptyList<Int>()), repo.reminderWrites)
    }

    @Test
    fun loading_an_event_shows_the_reminders_it_already_has() = vmTest { vms ->
        val repo = FakeRepository().apply { existingReminders = listOf(10, 60) }
        val model = vm(vms, repo)

        model.open(occurrence(), repo)

        assertEquals(listOf(10, 60), model.uiState.value.reminderMinutes)
    }

    /**
     * ⚠⚠ **A single-occurrence edit must not touch reminders.** A reminder
     * belongs to the *series*, so writing them here would silently change every
     * occurrence from a screen that promised to change exactly one.
     */
    @Test
    fun editing_one_occurrence_leaves_the_series_reminders_alone() = vmTest { vms ->
        val repo = FakeRepository().apply { existingReminders = listOf(15) }
        val model = vm(vms, repo)
        model.open(occurrence(rrule = "FREQ=WEEKLY;BYDAY=MO"), repo)
        model.toggleReminder(60)
        model.save()

        model.commit(EditScope.THIS_OCCURRENCE)

        assertTrue(repo.reminderWrites.isEmpty(), "got ${repo.reminderWrites}")
    }

    /** ⚠ …but answering "all occurrences" does write them. */
    @Test
    fun editing_the_series_writes_its_reminders() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(rrule = "FREQ=WEEKLY;BYDAY=MO"), repo)
        model.toggleReminder(60)
        model.save()

        model.commit(EditScope.ALL_OCCURRENCES)

        assertEquals(listOf("standup" to listOf(60)), repo.reminderWrites)
    }

    // --- deleting -----------------------------------------------------------

    @Test
    fun deleting_a_recurring_event_asks_the_same_question() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(rrule = "FREQ=WEEKLY;BYDAY=MO"), repo)

        model.delete()

        assertTrue(model.uiState.value.askingDeleteScope)
        assertTrue(repo.deletedSeries.isEmpty() && repo.deletedOccurrences.isEmpty())
    }

    /** ⭐ Skipping one occurrence narrows the rule's output; it destroys nothing. */
    @Test
    fun deleting_this_occurrence_skips_it_and_leaves_the_series() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(rrule = "FREQ=WEEKLY;BYDAY=MO"), repo)
        model.delete()

        model.commitDelete(EditScope.THIS_OCCURRENCE)

        assertEquals(listOf("standup" to "2026-08-10T09:00"), repo.deletedOccurrences)
        assertTrue(repo.deletedSeries.isEmpty())
        assertTrue(model.uiState.value.deleted)
    }

    @Test
    fun deleting_all_occurrences_removes_the_series() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(rrule = "FREQ=WEEKLY;BYDAY=MO"), repo)
        model.delete()

        model.commitDelete(EditScope.ALL_OCCURRENCES)

        assertEquals(listOf("standup"), repo.deletedSeries)
    }

    @Test
    fun deleting_a_one_off_does_not_ask() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.open(occurrence(), repo)

        model.delete()

        assertFalse(model.uiState.value.askingDeleteScope)
        assertEquals(listOf("standup"), repo.deletedSeries)
    }

    /** ⚠ There is nothing to delete before the event exists. */
    @Test
    fun deleting_an_unsaved_draft_does_nothing() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setTitle("Standup")

        model.delete()

        assertTrue(repo.deletedSeries.isEmpty())
        assertFalse(model.uiState.value.deleted)
        assertFalse(model.uiState.value.askingDeleteScope)
    }

    // --- failure ------------------------------------------------------------

    /**
     * ⚠⚠ A failed save must not report `saved` — the screen would close over an
     * event that was never written, which is the worst possible way to lose it.
     */
    @Test
    fun a_failed_save_surfaces_an_error_and_does_not_report_saved() = vmTest { vms ->
        val model = vm(vms, FakeRepository(failWith = IllegalStateException("no calendar")))
        model.setTitle("Standup")

        model.save()

        val state = model.uiState.value
        assertFalse(state.saved)
        assertFalse(state.isSaving)
        assertNotNull(state.errorMessage)
        assertEquals("no calendar", state.errorMessage)
    }

    // --- reminder defaults (CA-5e) --------------------------------------------

    @Test
    fun a_new_event_starts_with_the_configured_default() = vmTest { vms ->
        val repo = FakeRepository().apply { timedDefault = 10 }

        assertEquals(listOf(10), vm(vms, repo).uiState.value.reminderMinutes)
    }

    /**
     * ⚠⚠ **The whole reason there are two preferences.** An all-day event starts
     * at 00:00, so a timed "10 minutes before" would alert at 23:50 the night
     * before — the bug every stock calendar avoids by asking a different question
     * the moment you tick all-day.
     */
    @Test
    fun ticking_all_day_swaps_in_the_all_day_default() = vmTest { vms ->
        val repo = FakeRepository().apply { timedDefault = 10; allDayDefault = -540 }
        val model = vm(vms, repo)
        assertEquals(listOf(10), model.uiState.value.reminderMinutes)

        model.setAllDay(true)

        assertEquals(listOf(-540), model.uiState.value.reminderMinutes)
        assertEquals(listOf(false, true), repo.defaultReads)
    }

    /** ⚠ And back, so the mistake is undoable rather than one-way. */
    @Test
    fun un_ticking_all_day_swaps_the_timed_default_back() = vmTest { vms ->
        val repo = FakeRepository().apply { timedDefault = 10; allDayDefault = -540 }
        val model = vm(vms, repo)
        model.setAllDay(true)

        model.setAllDay(false)

        assertEquals(listOf(10), model.uiState.value.reminderMinutes)
    }

    /**
     * ⚠⚠ **A default may be replaced silently; a choice may not.** This is the
     * one behaviour that makes the swap above safe — without it, ticking all-day
     * would throw away reminders the user had just picked.
     */
    @Test
    fun ticking_all_day_does_not_overwrite_reminders_the_user_chose() = vmTest { vms ->
        val repo = FakeRepository().apply { timedDefault = 10; allDayDefault = -540 }
        val model = vm(vms, repo)
        model.toggleReminder(30)
        assertEquals(listOf(10, 30), model.uiState.value.reminderMinutes)

        model.setAllDay(true)

        assertEquals(listOf(10, 30), model.uiState.value.reminderMinutes)
    }

    /** ⚠ Setting the same value is not a change; re-reading would be a wasted round trip. */
    @Test
    fun setting_all_day_to_what_it_already_is_does_not_re_read_the_default() = vmTest { vms ->
        val repo = FakeRepository().apply { timedDefault = 10 }
        val model = vm(vms, repo)
        repo.defaultReads.clear()

        model.setAllDay(false)

        assertTrue(repo.defaultReads.isEmpty(), "got ${repo.defaultReads}")
    }

    /**
     * ⚠⚠ **An edit's stored list is the user's answer.** Applying a default over
     * an existing event would silently re-add a reminder the user had removed,
     * every time they opened the editor.
     */
    @Test
    fun editing_an_existing_event_never_applies_a_default() = vmTest { vms ->
        val repo = FakeRepository().apply { timedDefault = 10; allDayDefault = -540 }
        val model = vm(vms, repo)
        model.open(occurrence(), repo)
        assertTrue(model.uiState.value.reminderMinutes.isEmpty())

        model.setAllDay(true)

        assertTrue(model.uiState.value.reminderMinutes.isEmpty())
    }

    /**
     * ⚠⚠ **The read is a round trip, and the answer can arrive stale.** Ticking
     * all-day while the timed read is still out would otherwise let 10-minutes-
     * before land on a midnight event — the very case the swap exists to prevent,
     * arriving by the back door.
     *
     * ⚠ Only the *timed* read is held, so the stale answer resumes **after** the
     * fresh one. Holding both would let the fresh answer land last regardless and
     * the test would pass with no staleness check at all.
     */
    @Test
    fun a_default_that_arrives_after_the_question_changed_is_dropped() = vmTest { vms ->
        val repo = FakeRepository().apply {
            timedDefault = 10
            allDayDefault = -540
            hold(allDay = false)
        }
        val model = vm(vms, repo)                     // the timed read is now in flight

        model.setAllDay(true)                    // …and the question changes under it
        assertEquals(listOf(-540), model.uiState.value.reminderMinutes)

        repo.release(allDay = false)             // the stale timed answer arrives last

        assertEquals(listOf(-540), model.uiState.value.reminderMinutes)
    }

    /** ⚠ The same race, resolved the other way: a choice made mid-flight wins. */
    @Test
    fun a_default_that_arrives_after_the_user_chose_is_dropped() = vmTest { vms ->
        val repo = FakeRepository().apply { timedDefault = 10; hold(allDay = false) }
        val model = vm(vms, repo)

        model.toggleReminder(30)
        repo.release(allDay = false)

        assertEquals(listOf(30), model.uiState.value.reminderMinutes)
    }

    /**
     * ⚠ Once the choice is the user's, no default is read again. Not cosmetic —
     * the read is an IPC round trip to the proxy whose answer is guaranteed to be
     * discarded, issued on every all-day tap for the rest of the session.
     */
    @Test
    fun once_the_user_has_chosen_the_default_is_not_read_again() = vmTest { vms ->
        val repo = FakeRepository().apply { timedDefault = 10; allDayDefault = -540 }
        val model = vm(vms, repo)
        model.toggleReminder(30)
        repo.defaultReads.clear()

        model.setAllDay(true)

        assertTrue(repo.defaultReads.isEmpty(), "got ${repo.defaultReads}")
    }

    @Test
    fun a_failed_delete_surfaces_an_error_and_does_not_report_deleted() = vmTest { vms ->
        val repo = FakeRepository(failWith = IllegalStateException("gone"))
        val model = vm(vms, repo)
        model.open(occurrence(), repo)

        model.delete()

        assertFalse(model.uiState.value.deleted)
        assertNotNull(model.uiState.value.errorMessage)
    }

    // --- setting from a platform picker's integers (CA-8d) -------------------

    /**
     * ⭐ The `showMonth` / `selectDay` precedent: a native picker reports date *components*, not a
     * date object. SwiftUI's `DatePicker` binds a `Date`, which Foundation turns into components.
     */
    @Test
    fun a_start_date_can_be_set_from_its_parts() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)

        model.setStartDateFromParts(2027, 3, 14)

        assertEquals(LocalDate(2027, 3, 14), model.uiState.value.startDate)
    }

    /**
     * ⚠⚠ The part worth a test: the integer setter must keep the drag-the-end-along behaviour.
     * Duplicating that arithmetic rather than delegating is how an editor starts producing
     * negative-length events on one platform only.
     */
    @Test
    fun setting_the_start_time_from_parts_still_drags_the_end_along() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setStartTimeFromParts(9, 0)
        model.setEndTimeFromParts(10, 30)

        model.setStartTimeFromParts(14, 0)

        assertEquals(LocalTime(14, 0), model.uiState.value.startTime)
        assertEquals(LocalTime(15, 30), model.uiState.value.endTime, "the 90-minute duration is kept")
    }

    @Test
    fun an_end_time_can_be_set_from_its_parts() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setStartTimeFromParts(9, 0)

        model.setEndTimeFromParts(11, 45)

        assertEquals(LocalTime(11, 45), model.uiState.value.endTime)
    }

    /**
     * ⚠ Nonsense components can only arrive from a platform callback. Leaving the field as the
     * user last set it beats crashing an editor with unsaved text in it.
     */
    @Test
    fun nonsense_parts_leave_the_field_alone_rather_than_throwing() = vmTest { vms ->
        val repo = FakeRepository()
        val model = vm(vms, repo)
        model.setStartDateFromParts(2027, 3, 14)
        model.setStartTimeFromParts(9, 0)
        model.setEndTimeFromParts(10, 0)

        model.setStartDateFromParts(2027, 2, 30)
        model.setStartTimeFromParts(24, 0)
        model.setEndTimeFromParts(10, 60)

        assertEquals(LocalDate(2027, 3, 14), model.uiState.value.startDate)
        assertEquals(LocalTime(9, 0), model.uiState.value.startTime)
        assertEquals(LocalTime(10, 0), model.uiState.value.endTime)
    }
}
