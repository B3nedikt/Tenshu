package org.tenshu.calendar.common.reminder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/**
 * What should be armed, and when (`calendar-app.md` §4).
 *
 * ⭐ Clockless: every `now` and window is a parameter, so nothing here needs a
 * fake clock — the discipline `RecurrenceExpander` and `CalendarViewModel`
 * already follow, and the reason the two utterly different platform mechanisms
 * above this can be made to agree.
 */
class ReminderPlannerTest {

    private val berlin = TimeZone.of("Europe/Berlin")
    private val monday = LocalDate(2026, 8, 3)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant =
        LocalDateTime(date, LocalTime(hour, minute)).toInstant(berlin)

    private fun occurrence(
        eventId: String = "standup",
        calendarId: String = "work",
        title: String = "Standup",
        location: String = "Room A",
        date: LocalDate = monday,
        hour: Int = 9,
        minute: Int = 0,
        allDay: Boolean = false,
        zoneId: String = "Europe/Berlin",
    ) = ResolvedOccurrence(
        eventId = eventId,
        calendarId = calendarId,
        recurrenceId = "${date}T${hour.toString().padStart(2, '0')}:00",
        title = title,
        location = location,
        notes = "",
        startLocal = LocalDateTime(date, LocalTime(hour, minute)),
        endLocal = LocalDateTime(date, LocalTime(hour + 1, minute)),
        zoneId = zoneId,
        allDay = allDay,
        isOverridden = false,
        recurrenceRule = "",
    )

    private fun reminder(minutesBefore: Int, eventId: String = "standup") =
        ReminderInput("$eventId|$minutesBefore", eventId, minutesBefore)

    private fun plan(
        occurrences: List<ResolvedOccurrence>,
        reminders: List<ReminderInput>,
        states: List<ReminderStateInput> = emptyList(),
        sensitive: Set<String> = emptySet(),
        from: Instant = at(monday, 0),
        until: Instant = at(monday, 23, 59),
        limit: Int = ReminderPlanner.DEFAULT_LIMIT,
    ) = ReminderPlanner.plan(
        occurrences = occurrences,
        reminders = reminders,
        states = states,
        sensitiveCalendarIds = sensitive,
        from = from,
        until = until,
        floatingZoneId = "Europe/Berlin",
        limit = limit,
    )

    // --- the basics ---------------------------------------------------------

    @Test
    fun nothing_is_planned_without_reminders() {
        assertTrue(plan(listOf(occurrence()), emptyList()).isEmpty())
    }

    @Test
    fun a_reminder_fires_its_offset_before_the_occurrence() {
        val due = plan(listOf(occurrence()), listOf(reminder(15))).single()

        assertEquals(at(monday, 8, 45), due.fireAt)
        assertEquals(at(monday, 9), due.occurrenceStart)
        assertEquals("Standup", due.title)
        assertEquals("Room A", due.body)
        assertFalse(due.snoozed)
    }

    /** ⚠ "Remind me when it starts" is a real choice; zero is not a sentinel. */
    @Test
    fun a_zero_offset_fires_at_the_start() {
        assertEquals(at(monday, 9), plan(listOf(occurrence()), listOf(reminder(0))).single().fireAt)
    }

    @Test
    fun several_reminders_on_one_event_all_fire() {
        val due = plan(listOf(occurrence()), listOf(reminder(60), reminder(10)))

        assertEquals(2, due.size)
        assertEquals(listOf(at(monday, 8), at(monday, 8, 50)), due.map { it.fireAt }, "soonest first")
    }

    @Test
    fun a_reminder_only_applies_to_its_own_event() {
        val due = plan(
            listOf(occurrence(eventId = "standup"), occurrence(eventId = "lunch", hour = 13)),
            listOf(reminder(15, eventId = "lunch")),
        )

        assertEquals(listOf("lunch"), due.map { it.eventId })
    }

    /** ⭐ A reminder belongs to the series, so every occurrence of it gets one. */
    @Test
    fun one_reminder_row_covers_every_occurrence_of_a_series() {
        val due = plan(
            occurrences = (3..5).map { occurrence(date = LocalDate(2026, 8, it)) },
            reminders = listOf(reminder(15)),
            until = at(LocalDate(2026, 8, 6), 0),
        )

        assertEquals(3, due.size)
        assertEquals(3, due.map { it.key }.toSet().size, "each occurrence gets its own key")
    }

    // --- the negative offset ------------------------------------------------

    /**
     * ⚠⚠ **The all-day case, and why the offset is signed.** An all-day event
     * starts at 00:00, so "on the day at 9 a.m." — what every stock calendar
     * offers — is minus 540. Modelling only "before" would put the alert at
     * 23:50 the night before, or make it unrepresentable.
     */
    @Test
    fun a_negative_offset_fires_after_the_start_which_is_how_all_day_reminders_work() {
        val due = plan(
            listOf(occurrence(allDay = true, hour = 0, title = "Holiday")),
            listOf(reminder(-540)),
        ).single()

        assertEquals(at(monday, 9), due.fireAt, "09:00 on the day itself")
    }

    // --- time zones ---------------------------------------------------------

    /**
     * ⚠⚠ The event's **own** zone decides the instant, not the device's. A
     * standup at 09:00 Berlin is one moment whichever machine is planning it —
     * the `RoutineLocale` discipline, which exists because a travelling laptop
     * that used its own zone would arm a different alert than the phone.
     */
    @Test
    fun the_events_own_zone_decides_the_instant() {
        val tokyo = plan(
            listOf(occurrence(zoneId = "Asia/Tokyo")),
            listOf(reminder(0)),
            until = at(monday, 23, 59),
        ).single()

        assertEquals(
            LocalDateTime(monday, LocalTime(9, 0)).toInstant(TimeZone.of("Asia/Tokyo")),
            tokyo.fireAt,
        )
    }

    /** ⚠ A floating event ("09:00 wherever I am") reads the supplied zone. */
    @Test
    fun a_floating_event_uses_the_supplied_zone() {
        val due = plan(listOf(occurrence(zoneId = "")), listOf(reminder(0))).single()
        assertEquals(at(monday, 9), due.fireAt)
    }

    /**
     * ⚠ A bad zone drops the reminder rather than throwing: a row that survived
     * storage must not be able to break the whole plan, and the event itself
     * still shows in the grid.
     */
    @Test
    fun an_unparseable_zone_drops_that_reminder_and_leaves_the_rest() {
        val due = plan(
            listOf(occurrence(zoneId = "Mars/Olympus"), occurrence(eventId = "lunch", hour = 13)),
            listOf(reminder(0), reminder(0, eventId = "lunch")),
        )

        assertEquals(listOf("lunch"), due.map { it.eventId })
    }

    // --- the window ---------------------------------------------------------

    @Test
    fun a_reminder_outside_the_window_is_not_planned() {
        val occurrences = listOf(occurrence())

        assertTrue(
            plan(occurrences, listOf(reminder(15)), from = at(monday, 9)).isEmpty(),
            "08:45 is before the window opens",
        )
        assertTrue(
            plan(occurrences, listOf(reminder(15)), until = at(monday, 8)).isEmpty(),
            "and after it closes",
        )
    }

    /** ⚠ The window is inclusive at both ends — a reminder exactly at `from` is due. */
    @Test
    fun the_window_bounds_are_inclusive() {
        val due = plan(
            listOf(occurrence()),
            listOf(reminder(15)),
            from = at(monday, 8, 45),
            until = at(monday, 8, 45),
        )

        assertEquals(1, due.size)
    }

    // --- dismissal and snooze -----------------------------------------------

    @Test
    fun a_dismissed_occurrence_is_not_planned() {
        val due = plan(
            listOf(occurrence()),
            listOf(reminder(15)),
            states = listOf(ReminderStateInput("standup|15", "2026-08-03T09:00", dismissed = true)),
        )

        assertTrue(due.isEmpty())
    }

    /** ⚠ …and only that occurrence: dismissing today's standup keeps tomorrow's. */
    @Test
    fun a_dismissal_applies_to_one_occurrence_not_the_series() {
        val due = plan(
            occurrences = listOf(occurrence(), occurrence(date = LocalDate(2026, 8, 4))),
            reminders = listOf(reminder(15)),
            states = listOf(ReminderStateInput("standup|15", "2026-08-03T09:00", dismissed = true)),
            until = at(LocalDate(2026, 8, 5), 0),
        )

        assertEquals(listOf("2026-08-04T09:00"), due.map { it.recurrenceId })
    }

    @Test
    fun a_snooze_moves_the_alert_to_when_it_expires() {
        val snoozeUntil = at(monday, 9, 30)
        val due = plan(
            listOf(occurrence()),
            listOf(reminder(15)),
            states = listOf(
                ReminderStateInput(
                    "standup|15",
                    "2026-08-03T09:00",
                    snoozedUntilEpochMs = snoozeUntil.toEpochMilliseconds(),
                )
            ),
        ).single()

        assertEquals(snoozeUntil, due.fireAt)
        assertTrue(due.snoozed)
        assertEquals(at(monday, 9), due.occurrenceStart, "the occurrence itself did not move")
    }

    /**
     * ⚠⚠ **Dismissal beats snooze.** The two fields are independent so a
     * dismissal need not remember to clear a snooze, which means the precedence
     * has to be decided somewhere — and "I am done with this" must win, or a
     * dismissed reminder comes back.
     */
    @Test
    fun a_dismissal_wins_over_a_snooze_on_the_same_row() {
        val due = plan(
            listOf(occurrence()),
            listOf(reminder(15)),
            states = listOf(
                ReminderStateInput(
                    "standup|15",
                    "2026-08-03T09:00",
                    snoozedUntilEpochMs = at(monday, 9, 30).toEpochMilliseconds(),
                    dismissed = true,
                )
            ),
        )

        assertTrue(due.isEmpty())
    }

    /** ⚠ `0` means "not snoozed", not "snoozed to the epoch". */
    @Test
    fun a_zero_snooze_is_not_a_snooze() {
        val due = plan(
            listOf(occurrence()),
            listOf(reminder(15)),
            states = listOf(ReminderStateInput("standup|15", "2026-08-03T09:00", snoozedUntilEpochMs = 0L)),
        ).single()

        assertEquals(at(monday, 8, 45), due.fireAt)
        assertFalse(due.snoozed)
    }

    /** ⚠ State is keyed on the occurrence, so it must not leak across events. */
    @Test
    fun state_for_one_reminder_does_not_affect_another() {
        val due = plan(
            listOf(occurrence()),
            listOf(reminder(15), reminder(60)),
            states = listOf(ReminderStateInput("standup|15", "2026-08-03T09:00", dismissed = true)),
        )

        assertEquals(listOf("standup|60"), due.map { it.reminderId })
    }

    // --- redaction (Q10) ----------------------------------------------------

    /**
     * ⚠⚠ **Redaction happens at compose time, and that is the whole point.** On
     * Apple platforms the OS is handed the finished text days in advance and we
     * never see it again — so a presenter that redacted at display time could
     * not, and a "Health" calendar would announce itself on a lock screen.
     */
    @Test
    fun a_sensitive_calendar_yields_no_event_text_at_all() {
        val due = plan(
            listOf(occurrence(calendarId = "health", title = "Oncology follow-up", location = "3rd floor")),
            listOf(reminder(15)),
            sensitive = setOf("health"),
        ).single()

        assertEquals(ReminderPlanner.REDACTED_TITLE, due.title)
        assertEquals(ReminderPlanner.REDACTED_BODY, due.body)
        assertTrue(due.redacted)
    }

    /**
     * ⚠ The **location** is redacted too. "Oncology, 3rd floor" in the body says
     * everything the title would, so redacting only the title would be theatre.
     */
    @Test
    fun redaction_covers_the_location_not_only_the_title() {
        val due = plan(
            listOf(occurrence(calendarId = "health", title = "Appointment", location = "Oncology, 3rd floor")),
            listOf(reminder(15)),
            sensitive = setOf("health"),
        ).single()

        assertFalse(due.body.contains("Oncology"))
    }

    @Test
    fun a_non_sensitive_calendar_keeps_its_text() {
        val due = plan(listOf(occurrence()), listOf(reminder(15)), sensitive = setOf("health")).single()

        assertEquals("Standup", due.title)
        assertFalse(due.redacted)
    }

    @Test
    fun an_untitled_event_still_says_something() {
        val due = plan(listOf(occurrence(title = "")), listOf(reminder(15))).single()
        assertTrue(due.title.isNotBlank())
    }

    // --- the cap ------------------------------------------------------------

    /**
     * ⚠⚠ **The soonest N, not an arbitrary N.** iOS caps an app at 64 pending
     * notifications and silently drops the overflow, so a plan that returned the
     * wrong end of the list would lose *tomorrow's* reminders and keep next
     * month's.
     */
    @Test
    fun the_cap_keeps_the_soonest_reminders() {
        val occurrences = (1..30).map { occurrence(date = LocalDate(2026, 8, it)) }

        val due = plan(
            occurrences,
            listOf(reminder(15)),
            from = at(LocalDate(2026, 8, 1), 0),
            until = at(LocalDate(2026, 9, 1), 0),
            limit = 3,
        )

        assertEquals(3, due.size)
        assertEquals(
            listOf("2026-08-01T09:00", "2026-08-02T09:00", "2026-08-03T09:00"),
            due.map { it.recurrenceId },
        )
    }

    @Test
    fun a_zero_limit_plans_nothing() {
        assertTrue(plan(listOf(occurrence()), listOf(reminder(15)), limit = 0).isEmpty())
    }

    @Test
    fun the_default_limit_matches_the_documented_ios_cap() {
        assertEquals(64, ReminderPlanner.DEFAULT_LIMIT)
    }

    // --- identity -----------------------------------------------------------

    /**
     * ⚠⚠ The key must be stable across re-plans, because on every platform it is
     * how a pending alert is *replaced* rather than duplicated. Re-planning an
     * hour later must not stack a second copy of the same reminder.
     */
    @Test
    fun the_key_is_stable_across_replans_and_unique_per_occurrence() {
        val occurrences = listOf(occurrence(), occurrence(date = LocalDate(2026, 8, 4)))
        val reminders = listOf(reminder(15), reminder(60))
        val until = at(LocalDate(2026, 8, 5), 0)

        val first = plan(occurrences, reminders, until = until)
        val second = plan(occurrences, reminders, until = until)

        assertEquals(first.map { it.key }, second.map { it.key })
        assertEquals(4, first.map { it.key }.toSet().size, "2 occurrences × 2 reminders")
    }

    /** ⚠ Ties are broken deterministically, so a re-plan is not seen as churn. */
    @Test
    fun reminders_due_at_the_same_instant_come_back_in_a_stable_order() {
        val occurrences = listOf(occurrence(), occurrence(eventId = "other", title = "Other"))
        val reminders = listOf(reminder(15), reminder(15, eventId = "other"))

        val first = plan(occurrences, reminders)
        val second = plan(occurrences.reversed(), reminders.reversed())

        assertEquals(first.map { it.key }, second.map { it.key })
    }
}
