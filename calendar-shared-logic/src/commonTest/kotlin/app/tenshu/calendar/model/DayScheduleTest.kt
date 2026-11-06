package app.tenshu.calendar.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/**
 * Turning a window of occurrences into one day's grid.
 *
 * ⚠⚠ Almost every case here is about a **boundary** — midnight, an exclusive
 * end, a day the event only passes through. Those are the ones a renderer cannot
 * be asked to get right, because getting them wrong makes an event *vanish*
 * rather than look wrong.
 */
class DayScheduleTest {

    private val monday = LocalDate(2026, 8, 3)
    private val tuesday = LocalDate(2026, 8, 4)

    private fun event(
        name: String,
        from: LocalDateTime,
        to: LocalDateTime,
        allDay: Boolean = false,
    ) = ResolvedOccurrence(
        eventId = name,
        calendarId = "work",
        recurrenceId = "$from",
        title = name,
        location = "",
        notes = "",
        startLocal = from,
        endLocal = to,
        zoneId = "Europe/Berlin",
        allDay = allDay,
        isOverridden = false,
        recurrenceRule = "",
    )

    private fun on(date: LocalDate, hour: Int, minute: Int = 0) =
        LocalDateTime(date, LocalTime(hour, minute))

    private fun DaySchedule.titles() = timed.map { it.value.title }

    // --- the ordinary case --------------------------------------------------

    @Test
    fun a_timed_event_lands_at_its_own_minutes() {
        val schedule = daySchedule(
            monday,
            listOf(event("Standup", on(monday, 9), on(monday, 9, 30))),
        )

        val block = schedule.timed.single()
        assertEquals(9 * 60, block.topMinute)
        assertEquals(9 * 60 + 30, block.bottomMinute)
        assertTrue(schedule.allDay.isEmpty())
    }

    @Test
    fun an_event_on_another_day_does_not_appear() {
        val schedule = daySchedule(
            monday,
            listOf(event("Tomorrow", on(tuesday, 9), on(tuesday, 10))),
        )

        assertTrue(schedule.isEmpty)
    }

    // --- midnight -----------------------------------------------------------

    /**
     * ⚠⚠ **The clipping case.** An overnight shift belongs in *both* days'
     * columns. Rendering it only where it starts makes it disappear from the
     * morning it ends in, which is the morning you are looking at the calendar
     * to plan.
     */
    @Test
    fun an_event_spanning_midnight_is_clipped_into_both_days() {
        val shift = event("Night shift", on(monday, 22), on(tuesday, 2))

        val mondayBlock = daySchedule(monday, listOf(shift)).timed.single()
        val tuesdayBlock = daySchedule(tuesday, listOf(shift)).timed.single()

        assertEquals(22 * 60, mondayBlock.topMinute)
        assertEquals(MINUTES_PER_DAY, mondayBlock.bottomMinute, "runs to the bottom of Monday")

        assertEquals(0, tuesdayBlock.topMinute, "and from the top of Tuesday")
        assertEquals(2 * 60, tuesdayBlock.bottomMinute)
    }

    @Test
    fun a_multi_day_event_fills_the_days_it_passes_through() {
        val conference = event("Conference", on(monday, 14), on(LocalDate(2026, 8, 6), 12))

        val wednesday = daySchedule(LocalDate(2026, 8, 5), listOf(conference)).timed.single()

        assertEquals(0, wednesday.topMinute)
        assertEquals(MINUTES_PER_DAY, wednesday.bottomMinute)
    }

    /**
     * ⚠ An exclusive end at exactly 00:00 belongs to the *previous* day. An
     * inclusive test here would put a zero-height sliver at the top of a day the
     * event has nothing to do with.
     */
    @Test
    fun an_event_ending_exactly_at_midnight_does_not_leak_into_the_next_day() {
        val evening = event("Evening", on(monday, 21), on(tuesday, 0))

        assertEquals(listOf("Evening"), daySchedule(monday, listOf(evening)).titles())
        assertTrue(daySchedule(tuesday, listOf(evening)).timed.isEmpty())
    }

    /** ⚠ …and one starting exactly at 00:00 belongs to the day it starts. */
    @Test
    fun an_event_starting_exactly_at_midnight_belongs_to_that_day() {
        val early = event("Early", on(tuesday, 0), on(tuesday, 1))

        assertTrue(daySchedule(monday, listOf(early)).timed.isEmpty())
        assertEquals(0, daySchedule(tuesday, listOf(early)).timed.single().topMinute)
    }

    /** ⚠ A zero-length event at midnight is real and must still show. */
    @Test
    fun a_zero_length_event_at_midnight_still_appears_on_its_day() {
        val instant = event("Instant", on(tuesday, 0), on(tuesday, 0))

        assertEquals(listOf("Instant"), daySchedule(tuesday, listOf(instant)).titles())
        assertTrue(daySchedule(monday, listOf(instant)).timed.isEmpty())
    }

    // --- the all-day band ---------------------------------------------------

    /**
     * ⚠⚠ An all-day event drawn in the grid would fill the entire column and
     * bury every appointment behind it. It has no position on an hour ruler
     * because it is a property of the day, not of a time within it.
     */
    @Test
    fun an_all_day_event_goes_to_the_band_not_the_grid() {
        val schedule = daySchedule(
            monday,
            listOf(
                event("Holiday", on(monday, 0), on(tuesday, 0), allDay = true),
                event("Standup", on(monday, 9), on(monday, 9, 30)),
            ),
        )

        assertEquals(listOf("Holiday"), schedule.allDay.map { it.title })
        assertEquals(listOf("Standup"), schedule.titles())
    }

    @Test
    fun a_multi_day_all_day_event_bands_on_every_day_it_covers() {
        val trip = event("Trip", on(monday, 0), on(LocalDate(2026, 8, 6), 0), allDay = true)

        listOf(monday, tuesday, LocalDate(2026, 8, 5)).forEach { day ->
            assertEquals(listOf("Trip"), daySchedule(day, listOf(trip)).allDay.map { it.title }, "$day")
        }
        assertTrue(
            daySchedule(LocalDate(2026, 8, 6), listOf(trip)).allDay.isEmpty(),
            "the end is exclusive",
        )
    }

    /**
     * ⚠ A one-day all-day event whose stored end is degenerate (equal to its
     * start, as an importer might write) still covers its own day — otherwise it
     * would be silently invisible.
     */
    @Test
    fun a_degenerate_all_day_event_still_covers_its_own_day() {
        val marker = event("Marker", on(monday, 0), on(monday, 0), allDay = true)

        assertEquals(listOf("Marker"), daySchedule(monday, listOf(marker)).allDay.map { it.title })
        assertTrue(daySchedule(tuesday, listOf(marker)).allDay.isEmpty())
    }

    // --- it is still a layout -----------------------------------------------

    @Test
    fun overlapping_events_on_the_day_are_laid_out_side_by_side() {
        val schedule = daySchedule(
            monday,
            listOf(
                event("A", on(monday, 9), on(monday, 10)),
                event("B", on(monday, 9, 30), on(monday, 10, 30)),
            ),
        )

        assertEquals(listOf(0, 1), schedule.timed.map { it.column })
        assertTrue(schedule.timed.all { it.columnCount == 2 })
    }

    /**
     * ⚠ Clipping happens **before** layout, so a spill-over from yesterday
     * conflicts with this morning's meeting exactly as it visually does.
     */
    @Test
    fun a_spill_over_from_yesterday_conflicts_with_this_mornings_meeting() {
        val schedule = daySchedule(
            tuesday,
            listOf(
                event("Night shift", on(monday, 22), on(tuesday, 2)),
                event("Early call", on(tuesday, 1), on(tuesday, 3)),
            ),
        )

        assertEquals(2, schedule.timed.size)
        assertTrue(schedule.timed.all { it.columnCount == 2 })
    }
}
