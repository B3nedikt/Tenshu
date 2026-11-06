package org.tenshu.calendar.proxy

import app.tenshu.notify.AppAlert
import app.tenshu.notify.NotificationPresenter
import app.tenshu.notify.ScheduledAlert
import app.tenshu.notify.SchedulingNotificationPresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.common.reminder.ReminderPlanner
import org.tenshu.calendar.deliverDueReminders
import org.tenshu.calendar.saveCalendar
import org.tenshu.calendar.saveEvent
import org.tenshu.calendar.saveReminder
import org.tenshu.calendar.setReminderState
import org.tenshu.calendar.test.createCalendarProxy
import org.tenshu.calendar.test.environmentFor
import org.tenshu.core.common.TestProxy

/**
 * The wake-up, end to end through storage (CA-5c).
 *
 * ⚠⚠ **This is the boundary of what can be tested at all.** Below it,
 * `ReminderPlanner` and `ReminderDrainCalculator` are unit-tested over plain
 * values. Above it — `AlarmManager` firing, an OS throttling us, a notification
 * appearing on a lock screen — nothing is reachable from a test process, and
 * only an on-device pass can say. What this pins is the join: real rows in a
 * real store, read by the real op, reaching the presenter with the right text.
 */
class DeliverDueRemindersTest {

    /** Records what it was asked to show. */
    private class RecordingPresenter(private val enabled: Boolean = true) : NotificationPresenter {
        val posted = mutableListOf<AppAlert>()
        val cancelled = mutableListOf<String>()

        override fun canPost(): Boolean = enabled
        override fun post(alert: AppAlert) { posted += alert }
        override fun cancel(ids: Collection<String>) { cancelled += ids }
    }

    private val now = 1_785_000_000_000L   // a fixed instant; nothing reads a clock

    private fun proxy(presenter: NotificationPresenter, nowMs: Long = now): TestProxy =
        createCalendarProxy(
            presenter = presenter,
            nowEpochMs = { nowMs },
            zoneId = { "Europe/Berlin" },
        )

    private suspend fun service(p: TestProxy): CalendarService =
        environmentFor(p).getService(CalendarService.ID)

    /** An event starting [minutesFromNow] from `now`, in Berlin civil time. */
    private fun startLocal(minutesFromNow: Long): String {
        val instant = Instant.fromEpochMilliseconds(now + minutesFromNow * 60_000L)
        val civil = instant.toLocalDateTime(TimeZone.of("Europe/Berlin"))
        fun p(v: Int) = v.toString().padStart(2, '0')
        return "${civil.year}-${p(civil.month.number)}-${p(civil.day)}T${p(civil.hour)}:${p(civil.minute)}"
    }

    @Test
    fun a_reminder_that_is_due_is_posted_with_the_events_text() = runTest {
        val presenter = RecordingPresenter()
        val p = proxy(presenter)
        val service = service(p)
        service.saveCalendar("work", "Work", 0x4285F4)
        // Starts in 15 minutes, reminder 15 minutes before ⇒ due exactly now.
        service.saveEvent("standup", "Standup", startLocal(15), calendarId = "work",
            timeZoneId = "Europe/Berlin", location = "Room A")
        service.saveReminder("standup", 15)

        service.deliverDueReminders()

        val alert = presenter.posted.single()
        assertEquals("Standup", alert.title)
        assertEquals("Room A", alert.body)
    }

    @Test
    fun a_reminder_that_is_not_due_yet_posts_nothing() = runTest {
        val presenter = RecordingPresenter()
        val p = proxy(presenter)
        val service = service(p)
        service.saveCalendar("work", "Work", 0x4285F4)
        service.saveEvent("standup", "Standup", startLocal(120), calendarId = "work",
            timeZoneId = "Europe/Berlin")
        service.saveReminder("standup", 15)

        service.deliverDueReminders()

        assertTrue(presenter.posted.isEmpty())
    }

    @Test
    fun an_event_with_no_reminder_posts_nothing() = runTest {
        val presenter = RecordingPresenter()
        val p = proxy(presenter)
        val service = service(p)
        service.saveCalendar("work", "Work", 0x4285F4)
        service.saveEvent("standup", "Standup", startLocal(0), calendarId = "work",
            timeZoneId = "Europe/Berlin")

        service.deliverDueReminders()

        assertTrue(presenter.posted.isEmpty())
    }

    /**
     * ⚠⚠ **The Q10 flag, proven at the boundary that matters.** The planner's
     * unit test shows it redacts; this shows the redaction survives the whole
     * path and is what actually reaches the thing that would put it on a lock
     * screen.
     */
    @Test
    fun a_sensitive_calendars_reminder_reaches_the_presenter_with_no_event_text() = runTest {
        val presenter = RecordingPresenter()
        val p = proxy(presenter)
        val service = service(p)
        service.saveCalendar("health", "Health", 0xDB4437, sensitive = true)
        service.saveEvent("appt", "Oncology follow-up", startLocal(15), calendarId = "health",
            timeZoneId = "Europe/Berlin", location = "3rd floor")
        service.saveReminder("appt", 15)

        service.deliverDueReminders()

        val alert = presenter.posted.single()
        assertEquals(ReminderPlanner.REDACTED_TITLE, alert.title)
        assertEquals(ReminderPlanner.REDACTED_BODY, alert.body)
        assertFalse(alert.tapPayload.contains("Oncology"))
    }

    @Test
    fun a_dismissed_occurrence_is_not_posted() = runTest {
        val presenter = RecordingPresenter()
        val p = proxy(presenter)
        val service = service(p)
        service.saveCalendar("work", "Work", 0x4285F4)
        val start = startLocal(15)
        service.saveEvent("standup", "Standup", start, calendarId = "work",
            timeZoneId = "Europe/Berlin")
        service.saveReminder("standup", 15)
        service.setReminderState("standup|15", start, dismissed = true)

        service.deliverDueReminders()

        assertTrue(presenter.posted.isEmpty())
    }

    /**
     * ⚠ A presenter that cannot post is not an error. On Android the user may
     * simply have denied `POST_NOTIFICATIONS`, and the op must still run and
     * still arm — losing the *alert* is the cost of that choice; losing the
     * *schedule* would mean reminders never resumed if they later allowed it.
     */
    @Test
    fun a_presenter_that_cannot_post_is_not_asked_to() = runTest {
        val presenter = RecordingPresenter(enabled = false)
        val p = proxy(presenter)
        val service = service(p)
        service.saveCalendar("work", "Work", 0x4285F4)
        service.saveEvent("standup", "Standup", startLocal(15), calendarId = "work",
            timeZoneId = "Europe/Berlin")
        service.saveReminder("standup", 15)

        val result = service.deliverDueReminders()

        assertTrue(presenter.posted.isEmpty())
        assertTrue(result is org.tenshu.core.TResult.Success<*>, "the op still succeeded: $result")
    }

    // --- the pre-arm platforms (CA-5d) ---------------------------------------

    /** A presenter that schedules ahead, recording what it was handed. */
    private class SchedulingPresenter : SchedulingNotificationPresenter {
        var scheduled: List<ScheduledAlert> = emptyList()
        val posted = mutableListOf<AppAlert>()

        // ⚠⚠ `true`, deliberately. With `false` a test could not tell the
        // pre-arm branch from the post-and-arm one — both would post nothing —
        // and a mutation that deleted the early return survived exactly that way.
        override fun canPost(): Boolean = true
        override fun post(alert: AppAlert) { posted += alert }
        override fun cancel(ids: Collection<String>) = Unit
        override fun replaceScheduled(alerts: List<ScheduledAlert>) { scheduled = alerts }
    }

    /**
     * ⚠⚠ **On a pre-arming platform the OS gets the whole forward set**, not
     * just the one that is due. That is what makes a reminder fire with Control
     * not running (`calendar-app.md` §4.6) — and it is why the op returns before
     * arming an alarm of its own, which would be a second, drifting copy of a
     * schedule the OS already holds.
     */
    @Test
    fun a_scheduling_presenter_is_handed_every_upcoming_reminder() = runTest {
        val presenter = SchedulingPresenter()
        val p = createCalendarProxy(
            presenter = presenter,
            nowEpochMs = { now },
            zoneId = { "Europe/Berlin" },
        )
        val service = service(p)
        service.saveCalendar("work", "Work", 0x4285F4)
        service.saveEvent("a", "Soon", startLocal(60), calendarId = "work", timeZoneId = "Europe/Berlin")
        service.saveEvent("b", "Later", startLocal(600), calendarId = "work", timeZoneId = "Europe/Berlin")
        service.saveReminder("a", 15)
        service.saveReminder("b", 15)

        service.deliverDueReminders()

        assertEquals(listOf("Soon", "Later"), presenter.scheduled.map { it.alert.title })
        assertTrue(presenter.posted.isEmpty(), "the OS delivers; the app does not post")
    }

    /**
     * ⚠⚠ A reminder already due is **not** handed to the OS: a calendar trigger
     * in the past never fires, so it would sit pending forever while the diff
     * believed it was armed.
     *
     * ⚠ The event starts in 12 minutes with a 15-minute reminder, so its alert
     * was due 3 minutes ago — *inside* the catch-up window and therefore really
     * present in the plan. An event further out would put the alert outside the
     * planned window entirely, and the test would pass without the filter ever
     * running: exactly how the first version of this test let a mutation live.
     */
    @Test
    fun a_scheduling_presenter_is_not_handed_a_reminder_that_is_already_due() = runTest {
        val presenter = SchedulingPresenter()
        val p = createCalendarProxy(
            presenter = presenter,
            nowEpochMs = { now },
            zoneId = { "Europe/Berlin" },
        )
        val service = service(p)
        service.saveCalendar("work", "Work", 0x4285F4)
        service.saveEvent("a", "Now-ish", startLocal(12), calendarId = "work",
            timeZoneId = "Europe/Berlin")
        service.saveReminder("a", 15)

        service.deliverDueReminders()

        assertTrue(
            presenter.scheduled.isEmpty(),
            "due 3 minutes ago, so in the plan but not schedulable: ${presenter.scheduled}",
        )
        assertTrue(presenter.posted.isEmpty(), "and the pre-arm path never posts")
    }

    @Test
    fun an_empty_realm_is_a_no_op_rather_than_a_failure() = runTest {
        val presenter = RecordingPresenter()
        val p = proxy(presenter)

        val result = service(p).deliverDueReminders()

        assertTrue(result is org.tenshu.core.TResult.Success<*>, "got $result")
        assertTrue(presenter.posted.isEmpty())
    }
}
