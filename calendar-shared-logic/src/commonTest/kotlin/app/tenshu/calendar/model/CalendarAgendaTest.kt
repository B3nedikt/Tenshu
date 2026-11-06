package app.tenshu.calendar.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/**
 * The agenda's shape (CA-8a).
 *
 * ⚠⚠ This is the whole of the iOS agenda screen that can be tested here — no Swift compiles in
 * this environment, which is exactly why every decision was pushed to this side of the line and
 * the view only draws the answer.
 */
class CalendarAgendaTest {

    private val today = LocalDate(2026, 8, 10)  // a Monday

    private fun occurrence(
        title: String,
        date: LocalDate = today,
        hour: Int = 9,
        minute: Int = 0,
        allDay: Boolean = false,
    ) = ResolvedOccurrence(
        eventId = title,
        calendarId = "work",
        recurrenceId = "$date/$title",
        title = title,
        location = "",
        notes = "",
        startLocal = LocalDateTime(date, LocalTime(hour, minute)),
        endLocal = LocalDateTime(date, LocalTime((hour + 1).coerceAtMost(23), minute)),
        zoneId = "Europe/Berlin",
        allDay = allDay,
        isOverridden = false,
        recurrenceRule = "",
    )

    // --- sectioning ----------------------------------------------------------

    @Test
    fun occurrences_group_into_days_in_date_order() {
        val sections = CalendarAgenda.sections(
            listOf(
                occurrence("Dentist", date = LocalDate(2026, 8, 12)),
                occurrence("Standup"),
                occurrence("Review", date = LocalDate(2026, 8, 11)),
            ),
            today,
        )

        assertEquals(listOf("Standup", "Review", "Dentist"), sections.map { it.occurrences.single().title })
        assertEquals(listOf(CalendarAgenda.TODAY, CalendarAgenda.TOMORROW), sections.take(2).map { it.heading })
    }

    @Test
    fun a_days_events_are_ordered_within_the_day() {
        val sections = CalendarAgenda.sections(
            listOf(
                occurrence("Late", hour = 17),
                occurrence("Early", hour = 8),
                occurrence("Middle", hour = 12),
            ),
            today,
        )

        assertEquals(listOf("Early", "Middle", "Late"), sections.single().occurrences.map { it.title })
    }

    /** ⚠ Ties break on title so the order does not shuffle between reloads. */
    @Test
    fun events_at_the_same_time_keep_a_stable_order() {
        val first = CalendarAgenda.sections(listOf(occurrence("Beta"), occurrence("Alpha")), today)
        val second = CalendarAgenda.sections(listOf(occurrence("Alpha"), occurrence("Beta")), today)

        assertEquals(listOf("Alpha", "Beta"), first.single().occurrences.map { it.title })
        assertEquals(first.single().occurrences.map { it.title }, second.single().occurrences.map { it.title })
    }

    /**
     * ⚠⚠ The load window reaches one day *back* for the time grid's sake
     * (`CalendarViewModel.loadWindow`), so yesterday's occurrences really do arrive here. An
     * agenda is what is coming.
     */
    @Test
    fun anything_before_today_is_dropped() {
        val sections = CalendarAgenda.sections(
            listOf(occurrence("Yesterday", date = LocalDate(2026, 8, 9)), occurrence("Standup")),
            today,
        )

        assertEquals(listOf("Standup"), sections.flatMap { it.occurrences }.map { it.title })
    }

    /**
     * ⚠⚠ Days come from the occurrences, never from the window: an agenda padded out with empty
     * day headers is a calendar with the grid removed.
     */
    @Test
    fun a_day_with_nothing_on_it_gets_no_section() {
        val sections = CalendarAgenda.sections(
            listOf(occurrence("Standup"), occurrence("Dentist", date = LocalDate(2026, 8, 14))),
            today,
        )

        assertEquals(2, sections.size, "the three empty days between them must not appear")
    }

    @Test
    fun nothing_coming_up_is_an_empty_list_not_a_placeholder_section() {
        assertEquals(emptyList(), CalendarAgenda.sections(emptyList(), today))
    }

    /**
     * ⚠ The id a renderer keys on. It is an `Int` because SwiftUI's `ForEach` needs `Hashable` and
     * a bridged Kotlin class is not — and because a number cannot be turned back into a different
     * date by accident.
     */
    @Test
    fun each_section_is_identified_by_its_own_day() {
        val sections = CalendarAgenda.sections(
            listOf(occurrence("Standup"), occurrence("Review", date = LocalDate(2026, 8, 11))),
            today,
        )

        assertEquals(sections.map { it.date.toEpochDays() }, sections.map { it.epochDay })
        assertEquals(sections.map { it.epochDay }.distinct(), sections.map { it.epochDay })
    }

    // --- headings ------------------------------------------------------------

    @Test
    fun only_today_and_tomorrow_are_named_relatively() {
        assertEquals(CalendarAgenda.TODAY, CalendarAgenda.heading(today, today))
        assertEquals(CalendarAgenda.TOMORROW, CalendarAgenda.heading(LocalDate(2026, 8, 11), today))

        val later = CalendarAgenda.heading(LocalDate(2026, 8, 12), today)
        assertTrue(later != CalendarAgenda.TODAY && later != CalendarAgenda.TOMORROW)
        assertTrue(later.contains("12"), "a date the user can act on, not 'in 2 days': was '$later'")
    }

    // --- labels --------------------------------------------------------------

    /**
     * ⚠⚠ Never "00:00". Midnight is how an all-day event is stored, not when it happens, and
     * printing the storage detail makes a public holiday look like a midnight meeting.
     */
    @Test
    fun an_all_day_event_is_labelled_as_such_rather_than_at_midnight() {
        val holiday = occurrence("Holiday", hour = 0, allDay = true)

        assertEquals(CalendarAgenda.ALL_DAY, CalendarAgenda.timeLabel(holiday))
        assertEquals(CalendarAgenda.ALL_DAY_TITLE, CalendarAgenda.spanLabel(holiday))
    }

    @Test
    fun a_timed_event_shows_its_start_and_its_span() {
        val meeting = occurrence("Standup", hour = 9, minute = 30)

        val time = CalendarAgenda.timeLabel(meeting)
        assertTrue(time.contains("9") && time.contains("30"), "was '$time'")

        val span = CalendarAgenda.spanLabel(meeting)
        assertTrue(span.contains("–"), "a range reads as one at a glance; was '$span'")
        assertTrue(span.contains("10"), "and it names the end; was '$span'")
    }

    @Test
    fun an_untitled_event_still_has_something_to_show() {
        assertEquals(CalendarAgenda.UNTITLED, CalendarAgenda.titleOf(occurrence("")))
        assertEquals("Standup", CalendarAgenda.titleOf(occurrence("Standup")))
    }
}
