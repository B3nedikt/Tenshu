package app.tenshu.calendar.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** Which seven days a week view shows — the locale-sensitive half. */
class WeekGridTest {

    private val wednesday = LocalDate(2026, 8, 12)

    @Test
    fun a_week_is_seven_consecutive_days_from_the_week_start() {
        val week = WeekGrid.containing(wednesday)

        assertEquals(7, week.days.size)
        assertEquals(LocalDate(2026, 8, 10), week.start)
        assertEquals(LocalDate(2026, 8, 16), week.end)
        assertEquals(week.days, week.days.sorted())
    }

    /**
     * ⚠⚠ The week start is a *locale* fact — Monday across most of Europe,
     * Sunday in the US and much of Asia. The same Wednesday belongs to two
     * different weeks depending on where you are, and hard-coding one is a bug
     * that only shows up for the half of the world you are not in.
     */
    @Test
    fun a_sunday_start_shifts_the_whole_week() {
        val week = WeekGrid.containing(wednesday, DayOfWeek.SUNDAY)

        assertEquals(LocalDate(2026, 8, 9), week.start)
        assertEquals(LocalDate(2026, 8, 15), week.end)
        assertEquals(DayOfWeek.SUNDAY, week.days.first().dayOfWeek)
    }

    /**
     * ⚠ A date landing *on* the week start must not be pushed back a week —
     * the modulo arithmetic that gets this wrong looks right for six days out
     * of seven.
     */
    @Test
    fun a_date_on_the_week_start_begins_its_own_week() {
        assertEquals(
            LocalDate(2026, 8, 10),
            WeekGrid.containing(LocalDate(2026, 8, 10)).start,
        )
        assertEquals(
            LocalDate(2026, 8, 9),
            WeekGrid.containing(LocalDate(2026, 8, 9), DayOfWeek.SUNDAY).start,
        )
    }

    /** ⚠ A week straddling a month or year boundary is still seven days. */
    @Test
    fun a_week_spanning_a_year_boundary_is_contiguous() {
        val week = WeekGrid.containing(LocalDate(2026, 12, 31))

        assertEquals(LocalDate(2026, 12, 28), week.start)
        assertEquals(LocalDate(2027, 1, 3), week.end)
        week.days.zipWithNext { a, b ->
            assertEquals(1, b.toEpochDays() - a.toEpochDays())
        }
    }
}
