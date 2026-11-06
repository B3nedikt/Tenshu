package app.tenshu.calendar.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * The month grid's arithmetic — the half of a month view that is not drawing.
 *
 * ⭐ Every case here would otherwise only be checkable by looking at a screen,
 * which is exactly why the math lives in `-shared-logic` and not in a
 * `@Composable`.
 */
class MonthGridTest {

    @Test
    fun a_grid_is_always_six_rows_of_seven() {
        // February 2026 needs four weeks; October 2026 needs five. Both pad to six.
        listOf(2 to 2026, 10 to 2026).forEach { (month, year) ->
            val grid = MonthGrid.of(year, month)
            assertEquals(42, grid.cells.size, "$year-$month")
            assertEquals(6, grid.rows.size)
            assertTrue(grid.rows.all { it.size == 7 })
        }
    }

    /**
     * ⚠ The lead offset is where a month grid goes wrong. 1 August 2026 is a
     * Saturday, so a Monday-start grid borrows five days from July.
     */
    @Test
    fun leading_days_come_from_the_previous_month() {
        val grid = MonthGrid.of(2026, 8, DayOfWeek.MONDAY)

        assertEquals(LocalDate(2026, 7, 27), grid.cells.first().date)
        assertFalse(grid.cells.first().inMonth)
        assertEquals(5, grid.cells.count { !it.inMonth && it.date.month.number == 7 })
        assertEquals(LocalDate(2026, 8, 1), grid.cells[5].date)
        assertTrue(grid.cells[5].inMonth)
    }

    /** Trailing cells come from the following month and are marked out-of-month. */
    @Test
    fun trailing_days_come_from_the_next_month() {
        val grid = MonthGrid.of(2026, 8, DayOfWeek.MONDAY)
        val last = grid.cells.last()
        assertEquals(9, last.date.month.number)
        assertFalse(last.inMonth)
    }

    @Test
    fun exactly_the_months_days_are_marked_in_month() {
        assertEquals(31, MonthGrid.of(2026, 8).cells.count { it.inMonth })
        assertEquals(28, MonthGrid.of(2026, 2).cells.count { it.inMonth })
        assertEquals(29, MonthGrid.of(2024, 2).cells.count { it.inMonth })
        assertEquals(30, MonthGrid.of(2026, 9).cells.count { it.inMonth })
    }

    /**
     * ⚠⚠ Week start is a locale fact, not a constant. The same month laid out
     * Sunday-first borrows a different number of leading days — hard-coding
     * Monday is a bug that only shows up for the half of the world you are not in.
     */
    @Test
    fun the_week_start_changes_the_layout() {
        val monday = MonthGrid.of(2026, 8, DayOfWeek.MONDAY)
        val sunday = MonthGrid.of(2026, 8, DayOfWeek.SUNDAY)

        assertEquals(LocalDate(2026, 7, 27), monday.cells.first().date)
        assertEquals(LocalDate(2026, 7, 26), sunday.cells.first().date)
        assertEquals(DayOfWeek.MONDAY, monday.weekdayOrder.first())
        assertEquals(DayOfWeek.SUNDAY, sunday.weekdayOrder.first())
    }

    /**
     * ⚠ The `+ 7` in the lead calculation exists for this case: a month that
     * begins *on* the week-start must borrow **nothing**, not seven days.
     */
    @Test
    fun a_month_starting_on_the_week_start_borrows_no_leading_days() {
        // 1 June 2026 is a Monday.
        val grid = MonthGrid.of(2026, 6, DayOfWeek.MONDAY)
        assertEquals(LocalDate(2026, 6, 1), grid.cells.first().date)
        assertTrue(grid.cells.first().inMonth)
    }

    @Test
    fun weekday_headers_rotate_with_the_week_start() {
        assertEquals(
            listOf(
                DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
            ),
            MonthGrid.of(2026, 8, DayOfWeek.SUNDAY).weekdayOrder,
        )
    }

    /** Cells are contiguous — no gaps, no repeats. */
    @Test
    fun the_grid_is_a_contiguous_run_of_dates() {
        val cells = MonthGrid.of(2026, 8).cells
        cells.zipWithNext { a, b ->
            assertEquals(1, b.date.toEpochDays() - a.date.toEpochDays(), "gap at ${a.date}")
        }
    }

    // --- month arithmetic ---------------------------------------------------

    @Test
    fun shifting_months_rolls_the_year() {
        assertEquals(2027 to 1, shiftMonth(2026, 12, 1))
        assertEquals(2025 to 12, shiftMonth(2026, 1, -1))
        assertEquals(2026 to 9, shiftMonth(2026, 8, 1))
        assertEquals(2027 to 8, shiftMonth(2026, 8, 12))
    }

    /** ⚠ A December→January grid must not silently stay in the old year. */
    @Test
    fun a_year_boundary_grid_is_built_correctly() {
        val (year, month) = shiftMonth(2026, 12, 1)
        val grid = MonthGrid.of(year, month)
        assertEquals(2027, grid.year)
        assertEquals(1, grid.month)
        assertTrue(grid.cells.any { it.inMonth && it.date == LocalDate(2027, 1, 1) })
    }
}
