package app.tenshu.calendar.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/**
 * What the widget shows (CA-7c).
 *
 * ⚠⚠ This is the whole of the widget that can be tested here — a Glance
 * composable cannot be rendered in this environment, which is precisely why every
 * decision was pushed to this side of the line and the widget only draws the
 * answer.
 */
class WidgetAgendaTest {

    private val today = LocalDate(2026, 8, 3)   // a Monday
    private val nine = 9 * 60

    private fun occurrence(
        title: String,
        date: LocalDate = today,
        startHour: Int = 9,
        startMinute: Int = 0,
        durationMinutes: Int = 60,
        allDay: Boolean = false,
        endDate: LocalDate = date,
    ) = ResolvedOccurrence(
        eventId = title,
        calendarId = "work",
        recurrenceId = "${date}T${startHour.toString().padStart(2, '0')}:00",
        title = title,
        location = "",
        notes = "",
        startLocal = LocalDateTime(date, LocalTime(startHour, startMinute)),
        endLocal = LocalDateTime(
            endDate,
            LocalTime(
                (startHour + durationMinutes / 60).coerceAtMost(23),
                (startMinute + durationMinutes % 60).coerceAtMost(59),
            ),
        ),
        zoneId = "Europe/Berlin",
        allDay = allDay,
        isOverridden = false,
        recurrenceRule = "",
    )

    private fun build(
        vararg occurrences: ResolvedOccurrence,
        nowMinuteOfDay: Int = nine,
        limit: Int = WidgetAgendaBuilder.DEFAULT_LIMIT,
    ) = WidgetAgendaBuilder.build(occurrences.toList(), today, nowMinuteOfDay, limit)

    private fun rows(agenda: WidgetAgenda) = (agenda as? WidgetAgenda.Items)?.rows.orEmpty()

    // --- the states ----------------------------------------------------------

    /**
     * ⚠⚠ `Empty` and `NotConnected` are different states, and the type is what
     * keeps them apart — a failed IPC read yields the same empty list a free day
     * does, and rendering both as "Nothing today" tells the user their calendar is
     * empty when the app simply cannot see it.
     */
    @Test
    fun nothing_coming_up_is_empty_and_is_not_the_same_type_as_not_connected() {
        assertEquals(WidgetAgenda.Empty, build())
        assertTrue(WidgetAgenda.NotConnected != WidgetAgenda.Empty)
    }

    /**
     * ⚠⚠ **The guard is for the *negative* case, and mutation is what showed it.**
     * A zero limit is already handled by `take(0)`, so a test that only passed `0`
     * left the guard looking decorative — but `take(-1)` throws, and a widget that
     * crashes the launcher's host process is a worse failure than one that shows
     * nothing.
     */
    @Test
    fun a_non_positive_limit_shows_nothing_rather_than_throwing() {
        assertEquals(WidgetAgenda.Empty, build(occurrence("Standup"), limit = 0))
        assertEquals(WidgetAgenda.Empty, build(occurrence("Standup"), limit = -1))
    }

    // --- what appears --------------------------------------------------------

    @Test
    fun todays_events_appear_in_time_order() {
        val agenda = build(
            occurrence("Review", startHour = 14),
            occurrence("Standup", startHour = 9, startMinute = 30),
            occurrence("Lunch", startHour = 12),
            nowMinuteOfDay = 8 * 60,
        )

        assertEquals(listOf("Standup", "Lunch", "Review"), rows(agenda).map { it.title })
        assertEquals("09:30", rows(agenda).first().time)
    }

    /**
     * ⚠⚠ **An event under way still shows.** "Standup, started 10 minutes ago" is
     * the most useful thing a calendar widget can say at 09:10, and dropping it
     * leaves the glanceable surface blank during the very meeting the user is in.
     */
    @Test
    fun an_event_already_under_way_stays_up_and_is_marked_started() {
        val agenda = build(
            occurrence("Standup", startHour = 9, durationMinutes = 60),
            nowMinuteOfDay = 9 * 60 + 10,
        )

        val row = rows(agenda).single()
        assertEquals("Standup", row.title)
        assertTrue(row.started)
    }

    /** ⚠ …and it drops off once it has actually ended, not once it has begun. */
    @Test
    fun an_event_that_has_ended_drops_off() {
        val agenda = build(
            occurrence("Standup", startHour = 9, durationMinutes = 60),
            nowMinuteOfDay = 10 * 60 + 1,
        )

        assertEquals(WidgetAgenda.Empty, agenda)
    }

    /**
     * ⚠⚠ An all-day event on today is never "ended" — it runs the whole day, and
     * treating its 00:00 start as past would drop every holiday by 00:01.
     */
    @Test
    fun an_all_day_event_stays_up_all_day() {
        val agenda = build(
            occurrence("Holiday", allDay = true, startHour = 0, endDate = LocalDate(2026, 8, 4)),
            nowMinuteOfDay = 23 * 60 + 59,
        )

        val row = rows(agenda).single()
        assertEquals("Holiday", row.title)
        assertEquals("", row.time, "midnight is how it is stored, not when it happens")
        assertTrue(row.started)
    }

    /** ⚠ The list fills from later days once today is done — a blank widget is a wasted one. */
    @Test
    fun it_looks_ahead_when_today_has_nothing_left() {
        val agenda = build(
            occurrence("Standup", startHour = 9, durationMinutes = 60),
            occurrence("Dentist", date = LocalDate(2026, 8, 5), startHour = 11),
            nowMinuteOfDay = 18 * 60,
        )

        val row = rows(agenda).single()
        assertEquals("Dentist", row.title)
        assertTrue(row.tomorrowOrLater, "so the widget can date it rather than implying today")
        assertFalse(row.started)
    }

    /** ⚠ …but not indefinitely: something three weeks out is not "up next". */
    @Test
    fun it_does_not_look_past_the_horizon() {
        val agenda = WidgetAgendaBuilder.build(
            listOf(occurrence("Far off", date = LocalDate(2026, 8, 30))),
            today = today,
            nowMinuteOfDay = nine,
        )

        assertEquals(WidgetAgenda.Empty, agenda)
    }

    /** ⚠ Yesterday's occurrences never appear, even if the window handed them over. */
    @Test
    fun anything_before_today_is_ignored() {
        val agenda = build(occurrence("Yesterday", date = LocalDate(2026, 8, 2)))

        assertEquals(WidgetAgenda.Empty, agenda)
    }

    @Test
    fun the_list_is_capped() {
        val agenda = build(
            occurrence("A", startHour = 9),
            occurrence("B", startHour = 10),
            occurrence("C", startHour = 11),
            occurrence("D", startHour = 12),
            occurrence("E", startHour = 13),
            nowMinuteOfDay = 8 * 60,
            limit = 3,
        )

        assertEquals(listOf("A", "B", "C"), rows(agenda).map { it.title })
    }

    /** ⚠ A blank title gets a placeholder — an empty row reads as a rendering bug. */
    @Test
    fun an_untitled_event_still_has_something_to_show() {
        val agenda = build(occurrence("", startHour = 10), nowMinuteOfDay = 8 * 60)

        assertEquals(WidgetAgendaBuilder.UNTITLED, rows(agenda).single().title)
    }

    /** ⚠ Ties break on title so the order does not shuffle between updates. */
    @Test
    fun events_at_the_same_time_keep_a_stable_order() {
        val first = build(
            occurrence("Beta", startHour = 10),
            occurrence("Alpha", startHour = 10),
            nowMinuteOfDay = 8 * 60,
        )
        val second = build(
            occurrence("Alpha", startHour = 10),
            occurrence("Beta", startHour = 10),
            nowMinuteOfDay = 8 * 60,
        )

        assertEquals(rows(first).map { it.title }, rows(second).map { it.title })
        assertEquals(listOf("Alpha", "Beta"), rows(first).map { it.title })
    }
}
