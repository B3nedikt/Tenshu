package org.tenshu.calendar.common.reminder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The wake-up decision (`calendar-app.md` §4.1).
 *
 * ⭐ Everything the `Punctual` op decides, minus the two effects it cannot be
 * tested with — an alarm firing and a notification appearing.
 */
class ReminderDrainCalculatorTest {

    private val now = Instant.fromEpochMilliseconds(1_785_000_000_000L)

    private fun at(minutesFromNow: Int): Instant = now + minutesFromNow.minutes

    private fun due(id: String, minutesFromNow: Int) = DueReminder(
        key = id,
        eventId = "e-$id",
        recurrenceId = "r-$id",
        reminderId = "rem-$id",
        calendarId = "work",
        fireAt = at(minutesFromNow),
        occurrenceStart = at(minutesFromNow + 15),
        title = "Standup",
        body = "",
        redacted = false,
        snoozed = false,
    )

    @Test
    fun nothing_planned_is_nothing_to_do() {
        val drain = ReminderDrainCalculator.drain(emptyList(), now)

        assertTrue(drain.deliver.isEmpty())
        assertTrue(drain.missed.isEmpty())
        assertNull(drain.nextWakeAt)
    }

    @Test
    fun a_reminder_due_right_now_is_delivered() {
        val drain = ReminderDrainCalculator.drain(listOf(due("a", 0)), now)

        assertEquals(listOf("a"), drain.deliver.map { it.key })
        assertNull(drain.nextWakeAt, "nothing is left to wait for")
    }

    @Test
    fun a_future_reminder_is_not_delivered_but_sets_the_wake() {
        val drain = ReminderDrainCalculator.drain(listOf(due("a", 30)), now)

        assertTrue(drain.deliver.isEmpty())
        assertEquals(at(30), drain.nextWakeAt)
    }

    /**
     * ⚠⚠ **The next wake is the EARLIEST**, not the first in the list. Arming for
     * a later one would let every reminder before it pass unnoticed — and the
     * planner sorts by fire time, so a bug here hides behind sorted input.
     */
    @Test
    fun the_next_wake_is_the_earliest_upcoming_reminder() {
        val drain = ReminderDrainCalculator.drain(
            listOf(due("late", 120), due("soon", 10), due("mid", 60)),
            now,
        )

        assertEquals(at(10), drain.nextWakeAt)
    }

    // --- the catch-up window -------------------------------------------------

    /**
     * ⭐ RE-D6's three-way answer, with Calendar's tighter constant: a reminder
     * that is a little late still gives real warning, so it fires.
     */
    @Test
    fun a_slightly_late_reminder_still_fires() {
        val drain = ReminderDrainCalculator.drain(listOf(due("a", -4)), now)

        assertEquals(listOf("a"), drain.deliver.map { it.key })
        assertTrue(drain.missed.isEmpty())
    }

    /**
     * ⚠⚠ …and a very late one does **not**. A reminder for something that
     * started twelve minutes ago is worse than useless: it reports a thing you
     * have already missed, and teaches you to distrust the ones that arrive on
     * time.
     */
    @Test
    fun a_reminder_past_the_catch_up_window_is_recorded_as_missed_not_fired() {
        val drain = ReminderDrainCalculator.drain(listOf(due("a", -12)), now)

        assertTrue(drain.deliver.isEmpty())
        assertEquals(listOf("a"), drain.missed.map { it.key })
    }

    /** ⚠ The boundary is inclusive — exactly at the window is still worth firing. */
    @Test
    fun the_catch_up_boundary_is_inclusive() {
        val drain = ReminderDrainCalculator.drain(
            listOf(due("a", -5)),
            now,
            catchUpWindow = 5.minutes,
        )

        assertEquals(listOf("a"), drain.deliver.map { it.key })
    }

    @Test
    fun the_window_is_configurable() {
        val planned = listOf(due("a", -12))

        assertTrue(ReminderDrainCalculator.drain(planned, now, 5.minutes).deliver.isEmpty())
        assertEquals(1, ReminderDrainCalculator.drain(planned, now, 20.minutes).deliver.size)
    }

    /** ⚠ A missed reminder must not also set the wake — it is in the past. */
    @Test
    fun missed_reminders_do_not_arm_a_wake() {
        val drain = ReminderDrainCalculator.drain(listOf(due("a", -60), due("b", -90)), now)

        assertEquals(2, drain.missed.size)
        assertNull(drain.nextWakeAt)
    }

    // --- the three-way split -------------------------------------------------

    /**
     * ⚠⚠ **One pass answers all three questions**, and it has to: planning only
     * the future would deliver nothing and re-arm forever, while planning only
     * the past would never wake again.
     */
    @Test
    fun one_pass_splits_missed_due_and_upcoming() {
        val drain = ReminderDrainCalculator.drain(
            listOf(due("stale", -60), due("late", -2), due("nownow", 0), due("soon", 20)),
            now,
        )

        assertEquals(listOf("late", "nownow"), drain.deliver.map { it.key })
        assertEquals(listOf("stale"), drain.missed.map { it.key })
        assertEquals(at(20), drain.nextWakeAt)
    }

    /** ⚠ Deterministic output: a burst arrives in the order the events do. */
    @Test
    fun deliveries_come_back_soonest_first() {
        val drain = ReminderDrainCalculator.drain(
            listOf(due("b", -1), due("a", -3), due("c", 0)),
            now,
        )

        assertEquals(listOf("a", "b", "c"), drain.deliver.map { it.key })
    }
}
