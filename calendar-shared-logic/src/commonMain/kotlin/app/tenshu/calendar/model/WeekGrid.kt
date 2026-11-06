package app.tenshu.calendar.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

/**
 * The seven days a week view shows.
 *
 * ⭐ Trivial arithmetic, and in `-shared-logic` for the same reason [MonthGrid]
 * is: "which Monday does this Thursday belong to" is a *locale* question, the
 * SwiftUI week view has to answer it identically, and two answers that disagree
 * put the same event in two different weeks depending on which screen you are
 * looking at.
 */
data class WeekGrid(
    val weekStart: DayOfWeek,
    /** Seven dates, starting at [weekStart]. */
    val days: List<LocalDate>,
) {
    val start: LocalDate get() = days.first()
    val end: LocalDate get() = days.last()

    companion object {
        const val DAYS = 7

        /** The week [date] falls in, under [weekStart]. */
        fun containing(date: LocalDate, weekStart: DayOfWeek = DayOfWeek.MONDAY): WeekGrid {
            // The `+ 7` and second `% 7` matter for the same reason they do in
            // MonthGrid: without them a date landing *on* the week start yields
            // a negative offset for some pairs.
            val back = (date.dayOfWeek.isoDayNumber - weekStart.isoDayNumber + 7) % 7
            val first = date.plus(-back, DateTimeUnit.DAY)
            return WeekGrid(weekStart, (0 until DAYS).map { first.plus(it, DateTimeUnit.DAY) })
        }
    }
}
