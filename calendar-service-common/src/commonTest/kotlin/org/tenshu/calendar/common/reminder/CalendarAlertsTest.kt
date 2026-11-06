package org.tenshu.calendar.common.reminder

import app.tenshu.notify.AlertImportance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Calendar's vocabulary meeting the CA-5b platform seam's. */
class CalendarAlertsTest {

    private fun due(
        title: String = "Standup",
        body: String = "Room A",
        redacted: Boolean = false,
        eventId: String = "standup",
        recurrenceId: String = "2026-08-03T09:00",
    ) = DueReminder(
        key = ReminderPlanner.keyFor("$eventId|15", recurrenceId),
        eventId = eventId,
        recurrenceId = recurrenceId,
        reminderId = "$eventId|15",
        calendarId = "work",
        fireAt = Instant.fromEpochMilliseconds(1_785_000_000_000L),
        occurrenceStart = Instant.fromEpochMilliseconds(1_785_000_900_000L),
        title = title,
        body = body,
        redacted = redacted,
        snoozed = false,
    )

    @Test
    fun a_due_reminder_becomes_an_alert_at_its_fire_time() {
        val scheduled = CalendarAlerts.scheduledAlert(due())

        assertEquals(due().key, scheduled.id, "the planner's key is the OS identity")
        assertEquals(due().fireAt, scheduled.deliverAt)
        assertEquals("Standup", scheduled.alert.title)
        assertEquals("Room A", scheduled.alert.body)
        assertEquals(CalendarAlerts.Channel, scheduled.alert.channel)
    }

    /** ⚠ The channel is a promise Android will not let us take back. */
    @Test
    fun reminders_arrive_on_a_high_importance_channel() {
        assertEquals(AlertImportance.HIGH, CalendarAlerts.Channel.importance)
    }

    /**
     * ⚠⚠ **Redacted text is copied verbatim and never enriched.** Anything added
     * here could not be taken back: on Apple platforms the OS holds this text for
     * days and the app never sees it again.
     */
    @Test
    fun a_redacted_reminder_stays_redacted() {
        val scheduled = CalendarAlerts.scheduledAlert(
            due(title = ReminderPlanner.REDACTED_TITLE, body = "", redacted = true)
        )

        assertEquals(ReminderPlanner.REDACTED_TITLE, scheduled.alert.title)
        assertEquals("", scheduled.alert.body)
    }

    /**
     * ⚠⚠ **The back door Q10 would otherwise leave open.** A tap payload rides
     * in a `PendingIntent` extra, so putting the event's *text* there would
     * smuggle a redacted reminder's title onto the device in the clear.
     */
    @Test
    fun the_tap_payload_carries_identifiers_and_never_the_title() {
        val scheduled = CalendarAlerts.scheduledAlert(
            due(title = "Oncology follow-up", body = "3rd floor")
        )

        assertFalse(scheduled.alert.tapPayload.contains("Oncology"))
        assertFalse(scheduled.alert.tapPayload.contains("3rd floor"))
        assertTrue(scheduled.alert.tapPayload.contains("standup"))
    }

    @Test
    fun a_tap_payload_round_trips_to_its_occurrence() {
        val reminder = due(eventId = "evt-1", recurrenceId = "2026-08-10T09:00")

        val parsed = CalendarAlerts.parseTapPayload(CalendarAlerts.tapPayload(reminder))

        assertEquals("evt-1" to "2026-08-10T09:00", parsed)
    }

    /** ⚠ A foreign or malformed payload yields `null`, so the app does not route on it. */
    @Test
    fun a_payload_that_is_not_ours_does_not_parse() {
        assertNull(CalendarAlerts.parseTapPayload(""))
        assertNull(CalendarAlerts.parseTapPayload("notifications"))
        assertNull(CalendarAlerts.parseTapPayload("calendar/occurrence/"))
        assertNull(CalendarAlerts.parseTapPayload("calendar/occurrence/no-separator"))
        assertNull(CalendarAlerts.parseTapPayload("calendar/occurrence/|missing-event"))
        assertNull(CalendarAlerts.parseTapPayload("calendar/occurrence/missing-recurrence|"))
    }

    /**
     * ⚠ A `recurrenceId` is an ISO date-time and an `eventId` is opaque, so
     * neither is guaranteed free of the separator forever. The first separator
     * wins, matching how the payload is built.
     */
    @Test
    fun a_separator_inside_the_recurrence_id_does_not_break_parsing() {
        val parsed = CalendarAlerts.parseTapPayload("calendar/occurrence/evt-1|2026-08-10T09:00|x")

        assertEquals("evt-1" to "2026-08-10T09:00|x", parsed)
    }
}
