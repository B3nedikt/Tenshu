package app.tenshu.calendar.model

import app.tenshu.calendar.data.CalendarSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/**
 * The events a month cell lists (CA-2e) — ordering, the colour join, and the overflow
 * arithmetic, which is where this kind of view goes wrong.
 */
class CalendarMonthCellsTest {

    private val work = CalendarSummary("work", "Work", 0xFF2196F3.toInt(), sensitive = false, visible = true)
    private val home = CalendarSummary("home", "Home", 0xFF4CAF50.toInt(), sensitive = false, visible = true)
    private val day = LocalDate(2027, 5, 20)

    private fun occurrence(
        title: String,
        calendarId: String = "work",
        date: LocalDate = day,
        hour: Int = 9,
        minute: Int = 0,
        allDay: Boolean = false,
    ) = ResolvedOccurrence(
        eventId = title,
        calendarId = calendarId,
        recurrenceId = "$date/$title",
        title = title,
        location = "",
        notes = "",
        startLocal = LocalDateTime(date, LocalTime(hour, minute)),
        endLocal = LocalDateTime(date, LocalTime(hour + 1, minute)),
        zoneId = "Europe/Berlin",
        allDay = allDay,
        isOverridden = false,
        recurrenceRule = "",
    )

    private fun events(n: Int) = (1..n).map {
        MonthCellEvent(occurrence("e$it", hour = it), work.colorArgb)
    }

    @Test
    fun groupsByDate_andColoursFromTheOwningCalendar() {
        val byDate = CalendarMonthCells.byDate(
            occurrences = listOf(
                occurrence("Standup"),
                occurrence("Dinner", calendarId = "home", hour = 19),
                occurrence("Next day", date = LocalDate(2027, 5, 21)),
            ),
            calendars = listOf(work, home),
        )

        assertEquals(setOf(day, LocalDate(2027, 5, 21)), byDate.keys)
        assertEquals(
            listOf(work.colorArgb, home.colorArgb),
            byDate.getValue(day).map { it.colorArgb },
        )
    }

    /** ⚠ A calendar we do not have (a race, or a hidden one) must still draw, not vanish. */
    @Test
    fun anUnknownCalendarFallsBackToNeutralRatherThanDroppingTheEvent() {
        val byDate = CalendarMonthCells.byDate(listOf(occurrence("Orphan")), calendars = emptyList())

        assertEquals(CalendarMonthCells.NEUTRAL, byDate.getValue(day).single().colorArgb)
    }

    @Test
    fun allDayEventsSortFirst_thenByStartTime_thenTitle() {
        val byDate = CalendarMonthCells.byDate(
            occurrences = listOf(
                occurrence("Evening", hour = 19),
                occurrence("Morning", hour = 8),
                occurrence("Holiday", allDay = true, hour = 12),
                occurrence("Beta", hour = 8),
            ),
            calendars = listOf(work),
        )

        assertEquals(
            listOf("Holiday", "Beta", "Morning", "Evening"),
            byDate.getValue(day).map { it.occurrence.title },
        )
    }

    @Test
    fun everythingFits_soThereIsNoOverflowRow() {
        val fit = CalendarMonthCells.fit(events(3), capacity = 3)

        assertEquals(3, fit.shown.size)
        assertEquals(0, fit.hiddenCount)
    }

    /**
     * ⚠⚠ The one that matters: the "+N more" row occupies a slot of its own, so four
     * events in room for three is **two** events and "+2 more". Answering "three shown,
     * one hidden" either overflows the cell or drops an event without saying so.
     */
    @Test
    fun theOverflowRowCostsASlot() {
        val fit = CalendarMonthCells.fit(events(4), capacity = 3)

        assertEquals(listOf("e1", "e2"), fit.shown.map { it.occurrence.title })
        assertEquals(2, fit.hiddenCount)
        assertEquals(4, fit.shown.size + fit.hiddenCount, "no event may be unaccounted for")
    }

    @Test
    fun roomForOneRow_spendsItOnTheOverflowCount() {
        val fit = CalendarMonthCells.fit(events(3), capacity = 1)

        assertTrue(fit.shown.isEmpty())
        assertEquals(3, fit.hiddenCount)
    }

    /** ⚠ The caller falls back to dots here — a cell too short for a title is too short for "+N". */
    @Test
    fun noRoomAtAll_hidesEverythingRatherThanDrawingHalfARow() {
        val fit = CalendarMonthCells.fit(events(2), capacity = 0)

        assertTrue(fit.shown.isEmpty())
        assertEquals(2, fit.hiddenCount)
    }

    @Test
    fun anEmptyDayIsEmpty_notAZeroMoreRow() {
        val fit = CalendarMonthCells.fit(emptyList(), capacity = 4)

        assertTrue(fit.shown.isEmpty())
        assertEquals(0, fit.hiddenCount)
    }
}
