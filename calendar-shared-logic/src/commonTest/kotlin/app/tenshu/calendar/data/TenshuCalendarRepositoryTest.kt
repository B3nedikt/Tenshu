package app.tenshu.calendar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.tenshu.calendar.CalendarPreferences
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.Reminder
import org.tenshu.calendar.ReminderState
import org.tenshu.calendar.dismissed
import org.tenshu.calendar.snoozedUntilEpochMs
import org.tenshu.calendar.deleteOccurrence
import org.tenshu.calendar.saveCalendar
import org.tenshu.calendar.saveEvent
import org.tenshu.calendar.saveEventOverride
import org.tenshu.calendar.test.createCalendarProxy
import org.tenshu.calendar.test.asTenshuClient
import org.tenshu.calendar.test.environmentFor
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.preference.PreferenceService
import org.tenshu.preference.client.set
import org.tenshu.core.get

/**
 * The CA-1b read path end to end: ops write rows, the repository reads them back
 * through the chain DSL and folds them through `OccurrenceResolver`.
 *
 * ⚠ Complements `OccurrenceResolverTest` rather than repeating it. The resolver's
 * *logic* is tested there over plain inputs; what is unproven until here is the
 * wiring — the projections, the `Entity` accessors, and whether optionality
 * survives the round trip through storage.
 */
class TenshuCalendarRepositoryTest {

    private val deviceId = "device-a"

    private suspend fun seed(proxy: TestProxy): CalendarService =
        environmentFor(proxy).getService<CalendarService>(CalendarService.ID).also { service ->
            service.saveCalendar("work", "Work", 0x4285F4)
            service.saveCalendar("personal", "Personal", 0x0F9D58)
        }

    private fun repo(proxy: TestProxy) =
        TenshuCalendarRepository(
            client = proxy.asTenshuClient(),
            deviceIdentity = CalendarDeviceIdentity { deviceId },
            reachability = null /* test: no reachability engine */,
        )

    private suspend fun preferences(proxy: TestProxy): PreferenceService =
        environmentFor(proxy).getService(PreferenceService.ID)

    @Test
    fun calendars_come_back_named_coloured_and_visible_by_default() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)

        val calendars = repo(proxy).calendars()

        assertEquals(listOf("Personal", "Work"), calendars.map { it.name }, "sorted by name")
        assertTrue(calendars.all { it.visible }, "nothing is hidden until a device says so")
        assertEquals(0x4285F4, calendars.single { it.calendarId == "work" }.colorArgb)
    }

    @Test
    fun a_window_query_expands_a_recurring_series() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveEvent(
            eventId = "standup",
            title = "Standup",
            startLocal = "2026-08-03T09:00",
            endLocal = "2026-08-03T09:30",
            calendarId = "work",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=MO",
        )

        val august = repo(proxy).occurrences(LocalDate(2026, 8, 1), LocalDate(2026, 8, 31))

        assertEquals(5, august.size, "five Mondays in August 2026")
        assertTrue(august.all { it.title == "Standup" && it.calendarId == "work" })
    }

    /**
     * ⭐⭐ **The whole point of CA-2h, end to end against a real proxy.** Every other
     * test here reads once and asserts what came back; this one subscribes *first* and
     * asserts that a write which happens afterwards arrives on its own. That is the
     * property a screen depends on, and it cannot be faked at the view-model layer —
     * the emission has to come out of the store.
     */
    @Test
    fun an_event_saved_after_subscribing_arrives_without_a_re_query() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        val august = LocalDate(2026, 8, 1) to LocalDate(2026, 8, 31)

        val arrived = async {
            repo(proxy).observeOccurrences(august.first, august.second).first { it.isNotEmpty() }
        }
        runCurrent()                                   // let it subscribe before the write

        service.saveEvent(
            eventId = "standup",
            title = "Standup",
            startLocal = "2026-08-03T09:00",
            endLocal = "2026-08-03T09:30",
            calendarId = "work",
        )

        assertEquals(listOf("Standup"), arrived.await().map { it.title })
    }

    /** ⚠ An override edited on its own must move the occurrence it overrides. */
    @Test
    fun an_override_saved_after_subscribing_also_re_emits() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveEvent(
            eventId = "standup",
            title = "Standup",
            startLocal = "2026-08-03T09:00",
            endLocal = "2026-08-03T09:30",
            calendarId = "work",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=MO",
        )

        val renamed = async {
            repo(proxy)
                .observeOccurrences(LocalDate(2026, 8, 1), LocalDate(2026, 8, 31))
                .first { window -> window.any { it.title == "Standup, moved" } }
        }
        runCurrent()

        service.saveEventOverride(
            eventId = "standup",
            recurrenceId = "2026-08-10T09:00",
            title = "Standup, moved",
        )

        assertTrue(renamed.await().any { it.title == "Standup, moved" })
    }

    @Test
    fun a_day_query_returns_only_that_day() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveEvent("standup", "Standup", "2026-08-03T09:00", calendarId = "work",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=MO")

        val monday = repo(proxy).occurrencesOn(LocalDate(2026, 8, 10))
        val tuesday = repo(proxy).occurrencesOn(LocalDate(2026, 8, 11))

        assertEquals(1, monday.size)
        assertTrue(tuesday.isEmpty())
    }

    /** ⚠⚠ Optionality has to survive storage, not just the resolver's unit test. */
    @Test
    fun an_override_read_back_through_storage_still_distinguishes_null_from_empty() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveEvent("standup", "Standup", "2026-08-03T09:00", endLocal = "2026-08-03T09:30",
            calendarId = "work", location = "Room A", recurrenceRule = "FREQ=WEEKLY;BYDAY=MO")
        service.saveEventOverride(
            eventId = "standup",
            recurrenceId = "2026-08-10T09:00",
            location = "",     // override with empty
            // title deliberately omitted → inherit
        )

        val overridden = repo(proxy)
            .occurrencesOn(LocalDate(2026, 8, 10))
            .single()

        assertEquals("Standup", overridden.title, "an omitted field must inherit")
        assertEquals("", overridden.location, "an explicit \"\" must survive the round trip")
        assertTrue(overridden.isOverridden)
    }

    @Test
    fun a_skipped_occurrence_disappears_from_the_window() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveEvent("standup", "Standup", "2026-08-03T09:00", calendarId = "work",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=MO")

        service.deleteOccurrence("standup", "2026-08-10T09:00")

        val august = repo(proxy).occurrences(LocalDate(2026, 8, 1), LocalDate(2026, 8, 31))
        assertEquals(4, august.size)
        assertTrue(august.none { it.recurrenceId == "2026-08-10T09:00" })
    }

    // --- per-device visibility ----------------------------------------------

    /**
     * ⭐ Hiding a calendar is a **device-local** preference write, and the window
     * query honours it — the read-side half of the decision to keep `visible`
     * off the synced row.
     */
    @Test
    fun hiding_a_calendar_removes_its_events_from_the_window() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveEvent("w", "Review", "2026-08-05T10:00", calendarId = "work")
        service.saveEvent("p", "Lunch", "2026-08-05T13:00", calendarId = "personal")
        val repo = repo(proxy)

        repo.setCalendarVisible("work", visible = false)

        val day = repo.occurrencesOn(LocalDate(2026, 8, 5))
        assertEquals(listOf("Lunch"), day.map { it.title })
        assertFalse(repo.calendars().single { it.calendarId == "work" }.visible)
    }

    /** ⚠ `includeHidden` is the escape hatch an exporter or scheduler needs. */
    @Test
    fun includeHidden_sees_through_the_device_filter() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveEvent("w", "Review", "2026-08-05T10:00", calendarId = "work")
        val repo = repo(proxy)
        repo.setCalendarVisible("work", visible = false)

        assertTrue(repo.occurrencesOn(LocalDate(2026, 8, 5)).isEmpty())
        assertEquals(
            listOf("Review"),
            repo.occurrences(LocalDate(2026, 8, 5), LocalDate(2026, 8, 5), includeHidden = true)
                .map { it.title },
        )
    }

    /** Un-hiding restores it — the set is edited, not appended to. */
    @Test
    fun showing_a_hidden_calendar_again_restores_its_events() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveEvent("w", "Review", "2026-08-05T10:00", calendarId = "work")
        val repo = repo(proxy)

        repo.setCalendarVisible("work", visible = false)
        repo.setCalendarVisible("work", visible = true)

        assertEquals(listOf("Review"), repo.occurrencesOn(LocalDate(2026, 8, 5)).map { it.title })
    }

    // --- first-run seeding (closes CA-1's loose end) -------------------------

    /**
     * ⭐⭐ A fresh realm cannot create an event until something seeds a default —
     * `SaveEvent` fails loudly rather than guessing. This is that something.
     */
    @Test
    fun seeding_creates_a_first_calendar_and_points_the_default_at_it() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val repo = repo(proxy)

        repo.ensureDefaultCalendar()

        val calendars = repo.calendars()
        assertEquals(1, calendars.size)
        assertEquals("Personal", calendars.single().name)
        // ⚠ Opaque: the seed once wrote the palette blue with alpha 0, an invisible calendar.
        assertEquals(app.tenshu.calendar.model.CalendarPalette.DEFAULT, calendars.single().colorArgb)

        // The proof that matters: an event with no calendar now resolves.
        val service = environmentFor(proxy).getService<CalendarService>(CalendarService.ID)
        val saved = service.saveEvent("e1", "Lunch", "2026-08-05T13:00")
        assertTrue(saved is org.tenshu.core.TResult.Success<*>, "saveEvent failed: $saved")
    }

    /**
     * ⚠⚠ ADR 0025 §8.6 (Codex P1 on 802d5afdc): the seed runs at launch ABOVE the NotSetUp wall. On
     * a Control with no realm it must write nothing — no Personal calendar, no default — and it must
     * run by itself once a realm appears. Paired with the release: the same call then seeds.
     */
    @Test
    fun seeding_waits_for_a_realm_and_runs_when_one_appears() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        var hasRealm = false
        val reachability = app.tenshu.reachability.ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { hasRealm },
        )
        val repo = TenshuCalendarRepository(
            client = proxy.asTenshuClient(),
            deviceIdentity = CalendarDeviceIdentity { deviceId },
            reachability = reachability,
        )

        val seed = async { repo.ensureDefaultCalendar() }
        advanceTimeBy(150); runCurrent()
        assertEquals(app.tenshu.reachability.RealmVerdict.ABSENT, reachability.realmVerdict.value)
        assertFalse(seed.isCompleted, "the seed waits while Control has no realm")
        assertTrue(repo.calendars().isEmpty(), "no calendar into a store with no realm")

        hasRealm = true // the user sets Control up
        advanceTimeBy(150); runCurrent()
        seed.await()
        assertEquals(listOf("Personal"), repo.calendars().map { it.name }, "seeded once a realm appeared")
    }

    /** ⚠⚠ Runs on every launch, so a second call must change nothing. */
    @Test
    fun seeding_is_idempotent_across_launches() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val repo = repo(proxy)

        repo.ensureDefaultCalendar()
        repo.ensureDefaultCalendar()
        repo.ensureDefaultCalendar()

        assertEquals(1, repo.calendars().size, "a calendar per launch would be minted otherwise")
    }

    /**
     * ⚠ A realm that already has calendars is left alone — which of several
     * should receive unattributed events is the user's call, not a coin toss.
     */
    @Test
    fun seeding_does_not_touch_a_realm_that_already_has_calendars() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        repo.ensureDefaultCalendar()

        assertEquals(
            listOf("Personal", "Work"),
            repo.calendars().map { it.name },
            "no third calendar may appear",
        )
    }

    /**
     * ⚠⚠ A default that names a calendar this store does not hold yet means the realm's rows have
     * not arrived — the preference synced first. Seeding then would mint a Personal the user
     * never had, so nothing is written.
     */
    @Test
    fun seeding_leaves_a_realm_whose_default_names_a_calendar_not_here_yet() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val repo = repo(proxy)
        repo.setDefaultCalendar("work")

        repo.ensureDefaultCalendar()

        assertTrue(repo.calendars().isEmpty(), "no Personal over a realm still syncing")
        assertEquals("work", repo.defaultCalendar())
    }

    // --- writes (CA-3) ------------------------------------------------------

    @Test
    fun creating_an_event_mints_an_id_and_the_event_reads_back() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        val id = repo.saveEvent(
            EventDraft(
                calendarId = "work",
                title = "Review",
                startLocal = "2026-08-05T10:00",
                endLocal = "2026-08-05T11:00",
                location = "Room A",
            )
        )

        assertTrue(id.isNotBlank(), "the caller needs the id to navigate to what it created")
        val read = repo.occurrencesOn(LocalDate(2026, 8, 5)).single()
        assertEquals("Review", read.title)
        assertEquals(id, read.eventId)
        assertEquals("Room A", read.location)
    }

    /** ⚠ Two creates of genuinely different events must not collide. */
    @Test
    fun two_different_events_get_different_ids() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        val a = repo.saveEvent(EventDraft(calendarId = "work", title = "Review",
            startLocal = "2026-08-05T10:00", endLocal = "2026-08-05T11:00"))
        val b = repo.saveEvent(EventDraft(calendarId = "work", title = "Retro",
            startLocal = "2026-08-05T14:00", endLocal = "2026-08-05T15:00"))

        assertTrue(a != b)
        assertEquals(2, repo.occurrencesOn(LocalDate(2026, 8, 5)).size)
    }

    /** Editing passes the existing id back, so it replaces rather than duplicates. */
    @Test
    fun saving_an_existing_event_replaces_it() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        val id = repo.saveEvent(EventDraft(calendarId = "work", title = "Review",
            startLocal = "2026-08-05T10:00", endLocal = "2026-08-05T11:00"))

        repo.saveEvent(EventDraft(eventId = id, calendarId = "work", title = "Review (moved)",
            startLocal = "2026-08-05T16:00", endLocal = "2026-08-05T17:00"))

        val day = repo.occurrencesOn(LocalDate(2026, 8, 5))
        assertEquals(listOf("Review (moved)"), day.map { it.title }, "one row, not two")
    }

    /**
     * ⚠⚠ **The bug this test exists for.** `SaveEvent` treats an omitted optional
     * as absent, so a series edit that did not carry the current `exceptionDates`
     * would silently *resurrect* every occurrence the user had skipped — the edit
     * appears to work and the deleted occurrences quietly come back.
     */
    @Test
    fun editing_a_series_preserves_the_occurrences_the_user_already_skipped() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        val id = repo.saveEvent(
            EventDraft(calendarId = "work", title = "Standup", startLocal = "2026-08-03T09:00",
                endLocal = "2026-08-03T09:30", recurrenceRule = "FREQ=WEEKLY;BYDAY=MO")
        )
        repo.deleteOccurrence(id, "2026-08-10T09:00")
        assertEquals(4, repo.occurrences(LocalDate(2026, 8, 1), LocalDate(2026, 8, 31)).size)

        repo.saveEvent(
            EventDraft(eventId = id, calendarId = "work", title = "Standup (renamed)",
                startLocal = "2026-08-03T09:00", endLocal = "2026-08-03T09:30",
                recurrenceRule = "FREQ=WEEKLY;BYDAY=MO")
        )

        val august = repo.occurrences(LocalDate(2026, 8, 1), LocalDate(2026, 8, 31))
        assertEquals(4, august.size, "the skipped occurrence must not come back")
        assertTrue(august.none { it.recurrenceId == "2026-08-10T09:00" })
        assertTrue(august.all { it.title == "Standup (renamed)" }, "the edit itself still applied")
    }

    /**
     * ⭐ The this-vs-all distinction, proven where it actually happens: an
     * occurrence edit moves exactly one and leaves the rest on the rule.
     */
    @Test
    fun saving_one_occurrence_leaves_the_rest_of_the_series_alone() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        val id = repo.saveEvent(
            EventDraft(calendarId = "work", title = "Standup", startLocal = "2026-08-03T09:00",
                endLocal = "2026-08-03T09:30", recurrenceRule = "FREQ=WEEKLY;BYDAY=MO")
        )

        repo.saveOccurrence(
            EventDraft(eventId = id, calendarId = "work", title = "Standup (long)",
                startLocal = "2026-08-10T11:00", endLocal = "2026-08-10T12:00"),
            recurrenceId = "2026-08-10T09:00",
        )

        val august = repo.occurrences(LocalDate(2026, 8, 1), LocalDate(2026, 8, 31))
        assertEquals(5, august.size, "an override must not add or remove an occurrence")
        val moved = august.single { it.recurrenceId == "2026-08-10T09:00" }
        assertEquals("Standup (long)", moved.title)
        assertEquals(11, moved.startLocal.hour, "it moved to where the edit put it")
        assertTrue(moved.isOverridden)
        assertTrue(august.filter { it != moved }.all { it.title == "Standup" }, "the others are untouched")
    }

    @Test
    fun deleting_an_event_removes_every_occurrence() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        val id = repo.saveEvent(
            EventDraft(calendarId = "work", title = "Standup", startLocal = "2026-08-03T09:00",
                endLocal = "2026-08-03T09:30", recurrenceRule = "FREQ=WEEKLY;BYDAY=MO")
        )

        repo.deleteEvent(id)

        assertTrue(repo.occurrences(LocalDate(2026, 8, 1), LocalDate(2026, 8, 31)).isEmpty())
    }

    // --- reminders (CA-5e) --------------------------------------------------

    @Test
    fun reminders_round_trip_and_come_back_sorted() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        val id = repo.saveEvent(EventDraft(calendarId = "work", title = "Standup",
            startLocal = "2026-08-03T09:00", endLocal = "2026-08-03T09:30"))

        repo.setReminders(id, listOf(60, 10))

        assertEquals(listOf(10, 60), repo.remindersFor(id))
    }

    @Test
    fun setting_reminders_replaces_rather_than_appends() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        val id = repo.saveEvent(EventDraft(calendarId = "work", title = "Standup",
            startLocal = "2026-08-03T09:00", endLocal = "2026-08-03T09:30"))

        repo.setReminders(id, listOf(10, 60))
        repo.setReminders(id, listOf(30))

        assertEquals(listOf(30), repo.remindersFor(id))
    }

    /** ⚠ Clearing has to be expressible, or the last reminder can never be removed. */
    @Test
    fun an_empty_list_clears_every_reminder() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        val id = repo.saveEvent(EventDraft(calendarId = "work", title = "Standup",
            startLocal = "2026-08-03T09:00", endLocal = "2026-08-03T09:30"))
        repo.setReminders(id, listOf(10))

        repo.setReminders(id, emptyList())

        assertTrue(repo.remindersFor(id).isEmpty())
    }

    /**
     * ⚠⚠ **The reason `setReminders` diffs instead of clearing and rewriting.**
     * Deleting a reminder cascades to its `ReminderState` rows, and the key is
     * content-derived — so a clear-and-rewrite would bring an *identical*
     * reminder back with the user's dismissal silently discarded.
     */
    @Test
    fun rewriting_an_unchanged_reminder_does_not_discard_its_dismissal() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        val repo = repo(proxy)
        val id = repo.saveEvent(EventDraft(calendarId = "work", title = "Standup",
            startLocal = "2026-08-03T09:00", endLocal = "2026-08-03T09:30"))
        repo.setReminders(id, listOf(10))
        repo.dismissReminder(Reminder.idFor(id, 10), "2026-08-03T09:00")

        // The user adds a second reminder; the first is untouched.
        repo.setReminders(id, listOf(10, 60))

        val states = service.reminderStates
            .select {
                fields(
                    ReminderState.Field.REMINDER_ID,
                    ReminderState.Field.RECURRENCE_ID,
                    ReminderState.Field.SNOOZED_UNTIL_EPOCH_MS,
                    ReminderState.Field.DISMISSED,
                )
            }
            .getAll()
            .let { (it as? TResult.Success)?.value?.toList() }
            .orEmpty()

        assertEquals(1, states.size, "the dismissal survived the second write")
        assertTrue(states.single().dismissed.get())
    }

    @Test
    fun a_snooze_records_its_deadline() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        val repo = repo(proxy)
        val id = repo.saveEvent(EventDraft(calendarId = "work", title = "Standup",
            startLocal = "2026-08-03T09:00", endLocal = "2026-08-03T09:30"))
        repo.setReminders(id, listOf(10))

        repo.snoozeReminder(Reminder.idFor(id, 10), "2026-08-03T09:00", 1_785_000_000_000L)

        val state = service.reminderStates
            .select {
                fields(
                    ReminderState.Field.REMINDER_ID,
                    ReminderState.Field.RECURRENCE_ID,
                    ReminderState.Field.SNOOZED_UNTIL_EPOCH_MS,
                    ReminderState.Field.DISMISSED,
                )
            }
            .getAll()
            .let { (it as? TResult.Success)?.value?.toList() }
            .orEmpty()
            .single()

        assertEquals(1_785_000_000_000L, state.snoozedUntilEpochMs.get())
        assertFalse(state.dismissed.get())
    }

    @Test
    fun an_event_with_no_reminders_reports_none() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        assertTrue(repo(proxy).remindersFor("nope").isEmpty())
    }

    @Test
    fun an_empty_realm_yields_an_empty_window_rather_than_failing() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        assertTrue(repo(proxy).occurrences(LocalDate(2026, 8, 1), LocalDate(2026, 8, 31)).isEmpty())
        assertTrue(repo(proxy).calendars().isEmpty())
    }

    // --- reminder defaults (CA-5e) --------------------------------------------

    /** ⚠ 10 minutes and 09:00-on-the-day: the shipped values, unset. */
    @Test
    fun the_declared_defaults_apply_when_nothing_is_stored() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        assertEquals(10, repo.defaultReminderMinutes(allDay = false))
        assertEquals(-540, repo.defaultReminderMinutes(allDay = true))
    }

    /**
     * ⚠⚠ **Two preferences, and the flag decides which is read.** Reading the
     * timed one for an all-day event is the bug the split exists to prevent, and
     * it would be invisible — a plausible number, alerting at 23:50.
     */
    @Test
    fun each_kind_of_event_reads_its_own_preference() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        preferences(proxy).set(CalendarPreferences.DefaultReminderMinutes, 30L)
        preferences(proxy).set(CalendarPreferences.DefaultAllDayReminderMinutes, -600L)
        val repo = repo(proxy)

        assertEquals(30, repo.defaultReminderMinutes(allDay = false))
        assertEquals(-600, repo.defaultReminderMinutes(allDay = true))
    }

    /** ⚠ -1 is off, and 0 is not — "when it starts" is a real choice. */
    @Test
    fun the_off_sentinel_yields_no_default_while_zero_is_a_real_offset() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        preferences(proxy).set(CalendarPreferences.DefaultReminderMinutes, -1L)
        assertNull(repo.defaultReminderMinutes(allDay = false))

        preferences(proxy).set(CalendarPreferences.DefaultReminderMinutes, 0L)
        assertEquals(0, repo.defaultReminderMinutes(allDay = false))
    }

    /**
     * ⚠⚠ The sentinel is asymmetric. A negative *timed* offset is meaningless, so
     * any of them reads as off; a negative *all-day* offset is the normal case
     * (minutes after midnight), so only the explicit -1 is.
     */
    @Test
    fun a_negative_all_day_offset_is_a_value_while_a_negative_timed_one_is_off() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        preferences(proxy).set(CalendarPreferences.DefaultReminderMinutes, -30L)
        assertNull(repo.defaultReminderMinutes(allDay = false))

        preferences(proxy).set(CalendarPreferences.DefaultAllDayReminderMinutes, -1L)
        assertNull(repo.defaultReminderMinutes(allDay = true))
    }

    // --- calendar management (CA-5f) ------------------------------------------

    @Test
    fun a_created_calendar_comes_back_with_its_colour_and_sensitivity() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        val id = repo.saveCalendar("", "Travel", 0xF4B400, sensitive = true)

        val row = repo.calendars().single { it.calendarId == id }
        assertEquals("Travel", row.name)
        assertEquals(0xF4B400, row.colorArgb)
        assertTrue(row.sensitive)
        assertTrue(row.visible, "a new calendar is visible until a device hides it")
    }

    /** ⚠ An upsert: saving over an id renames and recolours rather than adding. */
    @Test
    fun saving_over_an_existing_id_updates_it_in_place() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        val before = repo.calendars().size

        repo.saveCalendar("work", "Job", 0xDB4437, sensitive = true)

        assertEquals(before, repo.calendars().size)
        val row = repo.calendars().single { it.calendarId == "work" }
        assertEquals("Job", row.name)
        assertTrue(row.sensitive)
    }

    /**
     * ⚠⚠ **A new calendar's id is derived from its name**, so two devices that
     * both create "Travel" while offline converge on one row rather than leaving
     * the user with two calendars they cannot tell apart. Deliberately-duplicate
     * names are refused a layer up, in the view model.
     */
    @Test
    fun two_calendars_created_with_the_same_name_converge_on_one_id() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        val first = repo.saveCalendar("", "Travel", 0xF4B400, sensitive = false)
        val second = repo.saveCalendar("", " travel ", 0x9C27B0, sensitive = false)

        assertEquals(first, second, "same name, same id")
    }

    @Test
    fun deleting_a_calendar_takes_its_events_with_it() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveEvent("w", "Review", "2026-08-05T10:00", calendarId = "work")
        service.saveEvent("p", "Lunch", "2026-08-05T13:00", calendarId = "personal")
        val repo = repo(proxy)

        repo.deleteCalendar("work")

        assertEquals(listOf("Personal"), repo.calendars().map { it.name })
        assertEquals(listOf("Lunch"), repo.occurrencesOn(LocalDate(2026, 8, 5)).map { it.title })
    }

    /**
     * ⚠⚠ **Deleting the default re-points it.** Left dangling, every editor-less
     * create — Mia's path — fails with "no default calendar", from a screen that
     * never mentioned defaults.
     */
    @Test
    fun deleting_the_default_calendar_re_points_the_preference() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        repo.setDefaultCalendar("work")

        repo.deleteCalendar("work")

        assertEquals("personal", repo.defaultCalendar())
    }

    /** ⚠ …and deleting a *non*-default one leaves the preference alone. */
    @Test
    fun deleting_another_calendar_does_not_move_the_default() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        repo.setDefaultCalendar("work")

        repo.deleteCalendar("personal")

        assertEquals("work", repo.defaultCalendar())
    }

    /**
     * ⚠ Blank is the honest answer when the last calendar is gone:
     * `ensureDefaultCalendar` re-seeds on the next launch, and inventing one here
     * would race it.
     */
    @Test
    fun deleting_the_only_calendar_leaves_the_default_blank() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = environmentFor(proxy).getService<CalendarService>(CalendarService.ID)
        service.saveCalendar("only", "Only", 0x4285F4)
        val repo = repo(proxy)
        repo.setDefaultCalendar("only")

        repo.deleteCalendar("only")

        assertEquals("", repo.defaultCalendar())
    }

    // --- subscriptions (CA-7b) ------------------------------------------------

    /**
     * ⚠⚠ **The same rule the proxy enforces, tested on this side too.** It is
     * duplicated on purpose (`:calendar-shared-logic` must not depend on the
     * proxy), so it needs its own test — found by mutation, which showed the
     * repository could start accepting `http://` with nothing failing.
     */
    @Test
    fun only_addresses_we_will_actually_fetch_can_be_subscribed_to() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        assertNull(repo.addSubscription("http://example.com/h.ics", "Holidays"))
        assertNull(repo.addSubscription("file:///etc/passwd", "Holidays"))
        assertNull(repo.addSubscription("   ", "Holidays"))
        assertTrue(repo.subscriptions().isEmpty(), "nothing refused leaves a row behind")
    }

    @Test
    fun subscribing_creates_the_calendar_the_feed_fills() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        val id = repo.addSubscription("https://example.com/h.ics", "Holidays")

        val feed = repo.subscriptions().single()
        assertEquals(id, feed.subscriptionId)
        assertEquals("https://example.com/h.ics", feed.url)
        assertTrue(feed.enabled)
        assertEquals(0L, feed.lastFetchedEpochMs, "never fetched yet")
        assertTrue(repo.calendars().any { it.calendarId == feed.calendarId && it.name == "Holidays" })
    }

    /** ⚠ `webcal://` is stored normalised, so what is fetched is what is shown. */
    @Test
    fun a_webcal_address_is_stored_as_https() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        repo.addSubscription("webcal://example.com/h.ics", "Holidays")

        assertEquals("https://example.com/h.ics", repo.subscriptions().single().url)
    }

    /** ⚠ Re-adding the same feed lands in the calendar it already filled. */
    @Test
    fun subscribing_twice_to_one_feed_makes_one_subscription() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        val before = repo.calendars().size

        repo.addSubscription("https://example.com/h.ics", "Holidays")
        repo.addSubscription("https://example.com/h.ics", "Holidays again")

        assertEquals(1, repo.subscriptions().size)
        assertEquals(before + 1, repo.calendars().size)
    }

    /** ⚠⚠ Pausing keeps the calendar; only the flag moves. */
    @Test
    fun pausing_a_feed_keeps_its_calendar() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        val id = repo.addSubscription("https://example.com/h.ics", "Holidays")!!
        val calendarId = repo.subscriptions().single().calendarId

        repo.setSubscriptionEnabled(id, enabled = false)

        assertFalse(repo.subscriptions().single().enabled)
        assertTrue(repo.calendars().any { it.calendarId == calendarId })
    }

    /** ⚠⚠ …and unsubscribing takes the calendar with it. */
    @Test
    fun unsubscribing_takes_the_calendar_with_it() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        val id = repo.addSubscription("https://example.com/h.ics", "Holidays")!!
        val calendarId = repo.subscriptions().single().calendarId

        repo.removeSubscription(id)

        assertTrue(repo.subscriptions().isEmpty())
        assertTrue(repo.calendars().none { it.calendarId == calendarId })
    }
}
