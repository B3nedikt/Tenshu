package app.tenshu.calendar.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus

/** One cell of a month grid. */
data class MonthCell(
    val date: LocalDate,
    /** False for the leading/trailing days borrowed from the adjacent months. */
    val inMonth: Boolean,
)

/**
 * The 6×7 layout of a month view.
 *
 * ⭐⭐ **Pure, and in `-shared-logic` rather than in the Compose layer, on
 * purpose** — the `:basemap-core` D7 precedent (`calendar-app.md` §5.2). Every
 * fiddly part of a month grid is arithmetic, not drawing: which weekday the
 * month starts on, how many days to borrow from the previous month, whether the
 * week starts on Monday or Sunday. Put that in a `@Composable` and it can only
 * be checked by looking at it; put it here and it is a unit test — *and* the
 * SwiftUI month view (or `UICalendarView`'s decorations) consumes the same
 * answer instead of reimplementing it.
 *
 * ⚠ Always **six rows**. A month needs anywhere from four to six weeks, and a
 * grid that changes height as you page through the year makes the whole screen
 * jump. Fixing the row count costs at most a few extra trailing cells and is
 * what every stock calendar does.
 */
data class MonthGrid(
    val year: Int,
    val month: Int,
    val weekStart: DayOfWeek,
    /** 42 cells, six rows of seven, in reading order. */
    val cells: List<MonthCell>,
) {
    /** The seven weekday headers, starting at [weekStart]. */
    val weekdayOrder: List<DayOfWeek>
        get() = (0..6).map { offset ->
            DayOfWeek.entries[(weekStart.isoDayNumber - 1 + offset) % 7]
        }

    /** The cells as six rows of seven, for a renderer that lays out row by row. */
    val rows: List<List<MonthCell>> get() = cells.chunked(7)

    companion object {
        const val ROWS = 6
        const val COLUMNS = 7

        /**
         * Builds the grid for [year]/[month].
         *
         * ⚠ [weekStart] is a parameter rather than a constant because it is a
         * *locale* fact — Monday across most of Europe, Sunday in the US and
         * much of Asia. Hard-coding it is a bug that only shows up for the half
         * of the world you are not in.
         */
        fun of(year: Int, month: Int, weekStart: DayOfWeek = DayOfWeek.MONDAY): MonthGrid {
            val first = LocalDate(year, month, 1)

            // How many days of the previous month lead the grid. The `+ 7` and
            // the second `% 7` matter: without them a month starting *on* the
            // week-start yields -0 or a negative offset depending on the pair.
            val lead = (first.dayOfWeek.isoDayNumber - weekStart.isoDayNumber + 7) % 7
            val gridStart = first.plus(-lead, DateTimeUnit.DAY)

            val cells = (0 until ROWS * COLUMNS).map { offset ->
                val date = gridStart.plus(offset, DateTimeUnit.DAY)
                MonthCell(date = date, inMonth = date.month.number == month && date.year == year)
            }
            return MonthGrid(year = year, month = month, weekStart = weekStart, cells = cells)
        }
    }
}

/** The month containing [date], as a `(year, month)` pair. */
fun LocalDate.monthOf(): Pair<Int, Int> = year to month.number

/** The first day of the month [delta] months from [year]/[month]. */
fun shiftMonth(year: Int, month: Int, delta: Int): Pair<Int, Int> {
    val total = year * 12 + (month - 1) + delta
    return total / 12 to (total % 12 + 1)
}
