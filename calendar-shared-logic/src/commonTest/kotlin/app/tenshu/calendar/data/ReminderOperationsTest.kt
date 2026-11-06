package app.tenshu.calendar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.Reminder
import org.tenshu.calendar.ReminderState
import org.tenshu.calendar.deleteReminder
import org.tenshu.calendar.eventId
import org.tenshu.calendar.minutesBefore
import org.tenshu.calendar.recurrenceId
import org.tenshu.calendar.reminderId
import org.tenshu.calendar.saveCalendar
import org.tenshu.calendar.saveEvent
import org.tenshu.calendar.saveReminder
import org.tenshu.calendar.setReminderState
import org.tenshu.calendar.snoozedUntilEpochMs
import org.tenshu.calendar.stateId
import org.tenshu.calendar.dismissed
import org.tenshu.calendar.test.createCalendarProxy
import org.tenshu.calendar.test.environmentFor
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.entity.Entity
import org.tenshu.core.get

/**
 * The CA-5 reminder ops, end to end through storage.
 *
 * ⚠ Complements `ReminderPlannerTest` rather than repeating it: the planner's
 * *logic* is tested there over plain inputs, and what is unproven until here is
 * the wiring — that a row written by an op reads back with the values it was
 * given, and that the two cascades actually cascade.
 */
class ReminderOperationsTest {

    private suspend fun seed(proxy: TestProxy): CalendarService =
        environmentFor(proxy).getService<CalendarService>(CalendarService.ID).also { service ->
            service.saveCalendar("work", "Work", 0x4285F4)
            service.saveEvent("standup", "Standup", "2026-08-03T09:00", calendarId = "work",
                recurrenceRule = "FREQ=WEEKLY;BYDAY=MO")
        }

    private suspend fun reminders(service: CalendarService): List<Entity<Reminder>> =
        service.reminders
            .select { fields(Reminder.Field.REMINDER_ID, Reminder.Field.EVENT_ID, Reminder.Field.MINUTES_BEFORE) }
            .getAll()
            .let { (it as? TResult.Success)?.value?.toList() }
            .orEmpty()

    private suspend fun states(service: CalendarService): List<Entity<ReminderState>> =
        service.reminderStates
            .select {
                fields(
                    ReminderState.Field.STATE_ID,
                    ReminderState.Field.REMINDER_ID,
                    ReminderState.Field.RECURRENCE_ID,
                    ReminderState.Field.SNOOZED_UNTIL_EPOCH_MS,
                    ReminderState.Field.DISMISSED,
                )
            }
            .getAll()
            .let { (it as? TResult.Success)?.value?.toList() }
            .orEmpty()

    @Test
    fun a_saved_reminder_reads_back_with_its_offset() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)

        service.saveReminder("standup", 15)

        val reminder = reminders(service).single()
        assertEquals("standup", reminder.eventId.get())
        assertEquals(15L, reminder.minutesBefore.get())
        assertEquals("standup|15", reminder.reminderId.get())
    }

    /**
     * ⭐ The content-derived key at work: two devices independently adding
     * "15 minutes before" converge on one row rather than alerting twice.
     */
    @Test
    fun saving_the_same_reminder_twice_yields_one_row() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)

        service.saveReminder("standup", 15)
        service.saveReminder("standup", 15)

        assertEquals(1, reminders(service).size)
    }

    @Test
    fun two_different_offsets_are_two_reminders() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)

        service.saveReminder("standup", 15)
        service.saveReminder("standup", 60)

        assertEquals(listOf(15L, 60L), reminders(service).map { it.minutesBefore.get() }.sorted())
    }

    /** ⚠ The signed offset has to survive storage, not just the planner's unit test. */
    @Test
    fun a_negative_offset_survives_the_round_trip() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)

        service.saveReminder("standup", -540)

        assertEquals(-540L, reminders(service).single().minutesBefore.get())
    }

    // --- state ---------------------------------------------------------------

    @Test
    fun a_dismissal_reads_back_as_dismissed() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveReminder("standup", 15)

        service.setReminderState("standup|15", "2026-08-03T09:00", dismissed = true)

        val state = states(service).single()
        assertEquals("standup|15", state.reminderId.get())
        assertEquals("2026-08-03T09:00", state.recurrenceId.get())
        assertTrue(state.dismissed.get())
        assertEquals(0L, state.snoozedUntilEpochMs.get())
    }

    @Test
    fun a_snooze_reads_back_with_its_deadline() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveReminder("standup", 15)

        service.setReminderState("standup|15", "2026-08-03T09:00", snoozedUntilEpochMs = 1_785_000_000_000L)

        assertEquals(1_785_000_000_000L, states(service).single().snoozedUntilEpochMs.get())
    }

    /** ⚠ State is per occurrence, so acting on one must not create a row for the series. */
    @Test
    fun state_is_keyed_per_occurrence() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveReminder("standup", 15)

        service.setReminderState("standup|15", "2026-08-03T09:00", dismissed = true)
        service.setReminderState("standup|15", "2026-08-10T09:00", dismissed = true)

        assertEquals(2, states(service).size)
        assertEquals(
            setOf("standup|15|2026-08-03T09:00", "standup|15|2026-08-10T09:00"),
            states(service).map { it.stateId.get() }.toSet(),
        )
    }

    /** ⭐ One op sets the whole state, so a dismissal cannot forget to clear a snooze. */
    @Test
    fun setting_state_replaces_rather_than_merges() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveReminder("standup", 15)

        service.setReminderState("standup|15", "2026-08-03T09:00", snoozedUntilEpochMs = 1_785_000_000_000L)
        service.setReminderState("standup|15", "2026-08-03T09:00", dismissed = true)

        val state = states(service).single()
        assertTrue(state.dismissed.get())
        assertEquals(0L, state.snoozedUntilEpochMs.get(), "the snooze was cleared, not kept")
    }

    // --- the cascade ---------------------------------------------------------

    /**
     * ⚠⚠ **The bug this test exists for.** `Reminder`'s key is content-derived,
     * so re-adding the same offset to the same event mints the *same* id — and
     * an orphaned dismissal would then silently suppress the first occurrence of
     * a reminder the user had just asked for.
     */
    @Test
    fun deleting_a_reminder_takes_its_state_rows_with_it() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveReminder("standup", 15)
        service.setReminderState("standup|15", "2026-08-03T09:00", dismissed = true)
        service.setReminderState("standup|15", "2026-08-10T09:00", dismissed = true)

        service.deleteReminder("standup|15")

        assertTrue(reminders(service).isEmpty())
        assertTrue(states(service).isEmpty(), "an orphaned dismissal would suppress a re-added reminder")
    }

    /** ⚠ …and only that reminder's rows. */
    @Test
    fun deleting_one_reminder_leaves_another_alone() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveReminder("standup", 15)
        service.saveReminder("standup", 60)
        service.setReminderState("standup|60", "2026-08-03T09:00", dismissed = true)

        service.deleteReminder("standup|15")

        assertEquals(listOf(60L), reminders(service).map { it.minutesBefore.get() })
        assertEquals(listOf("standup|60"), states(service).map { it.reminderId.get() })
    }

    @Test
    fun an_empty_realm_has_no_reminders_rather_than_failing() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = environmentFor(proxy).getService<CalendarService>(CalendarService.ID)

        assertTrue(reminders(service).isEmpty())
        assertTrue(states(service).isEmpty())
    }
}
