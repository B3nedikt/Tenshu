package app.tenshu.control.bridge

import app.tenshu.notify.AlertChannel
import app.tenshu.notify.AlertImportance
import app.tenshu.notify.AppAlert
import app.tenshu.notify.ScheduledAlert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The JSON the macOS tray reads (CA-5d).
 *
 * ⚠ Shaping is tested without a socket — the `pendingRequestsResult` precedent.
 * What the *tray* does with it is Swift, and unreachable from here.
 */
class ControlCalendarRemindersBridgeTest {

    private val channel = AlertChannel("reminders", "Reminders", AlertImportance.HIGH)

    private fun alert(id: String, title: String, body: String = "", atMs: Long = 1_785_000_000_000L) =
        ScheduledAlert(
            alert = AppAlert(id = id, channel = channel, title = title, body = body,
                tapPayload = "calendar/occurrence/e1|2026-08-03T09:00"),
            deliverAt = Instant.fromEpochMilliseconds(atMs),
        )

    @Suppress("UNCHECKED_CAST")
    private fun rows(vararg alerts: ScheduledAlert): List<Map<String, Any?>> =
        calendarRemindersResult(alerts.toList())["reminders"] as List<Map<String, Any?>>

    @Test
    fun an_empty_schedule_is_an_empty_list_not_an_absent_key() {
        val result = calendarRemindersResult(emptyList())

        assertTrue(result.containsKey("reminders"), "the tray reads this key unconditionally")
        assertEquals(emptyList<Any>(), result["reminders"])
    }

    /**
     * ⚠⚠ An unplanned empty schedule (a daemon that has not planned since it started) must say so: a tray that
     * diffs against the OS's own schedule would otherwise read it as "cancel everything" after every restart.
     */
    @Test
    fun the_schedule_says_whether_it_is_a_plan() {
        val schedule = app.tenshu.notify.PendingAlertSchedule()
        assertEquals(false, calendarRemindersResult(schedule.current(), schedule.isPlanned())["planned"])

        schedule.replaceScheduled(emptyList())
        assertEquals(true, calendarRemindersResult(schedule.current(), schedule.isPlanned())["planned"])
    }

    @Test
    fun a_reminder_carries_everything_the_tray_needs_and_nothing_else() {
        val row = rows(alert("k1", "Standup", "Room A")).single()

        assertEquals("k1", row["id"])
        assertEquals("Standup", row["title"])
        assertEquals("Room A", row["body"])
        assertEquals(1_785_000_000_000L, row["deliverAtEpochMs"])
        assertEquals(setOf("id", "title", "body", "deliverAtEpochMs", "tapPayload"), row.keys)
    }

    /**
     * ⚠⚠ The text is whatever the planner composed — already redacted for a
     * sensitive calendar. The bridge must not enrich it, because once the tray
     * hands it to macOS the OS holds that string for days.
     */
    @Test
    fun redacted_text_passes_through_untouched() {
        val row = rows(alert("k1", "Reminder", "")).single()

        assertEquals("Reminder", row["title"])
        assertEquals("", row["body"])
    }

    /** ⚠ Order is the planner's — soonest first — and the tray relies on it for its cap. */
    @Test
    fun order_is_preserved() {
        val out = rows(
            alert("soon", "A", atMs = 1_000L),
            alert("later", "B", atMs = 2_000L),
        )

        assertEquals(listOf("soon", "later"), out.map { it["id"] })
    }
}
