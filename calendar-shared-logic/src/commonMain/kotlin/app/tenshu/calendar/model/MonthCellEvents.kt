package app.tenshu.calendar.model

import app.tenshu.calendar.data.CalendarSummary
import kotlinx.datetime.LocalDate
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/** One event as a month cell draws it: the occurrence, plus the colour it is drawn in. */
data class MonthCellEvent(
    val occurrence: ResolvedOccurrence,
    val colorArgb: Int,
)

/**
 * What one cell shows, once the events have been fitted to the room it has.
 *
 * ⚠ [hiddenCount] is what the "+N more" row renders. Zero means everything fits and
 * there is no such row — not "there is a row saying nothing is hidden".
 */
data class MonthCellFit(
    val shown: List<MonthCellEvent>,
    val hiddenCount: Int,
)

/**
 * The events a month grid draws inside its cells (CA-2e).
 *
 * ⭐ Pure and tested, the same split as [CalendarMonthDots] and for the same reason
 * (`calendar-app.md` §5.2): ordering, the colour join and the overflow arithmetic are
 * decisions, and decisions belong where a unit test can reach them. The renderer is
 * left drawing a list it was handed.
 *
 * ⚠ **Not a widening of [CalendarMonthDots].** That model answers a `UICalendarView`
 * delegate, which gets exactly *one* decoration per cell and therefore deliberately
 * discards everything but a count and a colour. A Compose cell has room for titles, so
 * it needs a different answer from the same data — two consumers, two projections,
 * rather than one model that serves neither well.
 */
object CalendarMonthCells {

    /** ⚠ For an occurrence whose calendar is missing — a race, or a hidden one. */
    const val NEUTRAL: Int = CalendarMonthDots.NEUTRAL

    /**
     * Every day's events, in the order a cell lists them.
     *
     * ⚠⚠ **All-day events sort first, then by start time, then by title.** All-day
     * first because that is what every stock calendar does and what a reader expects
     * at the top of a day; title as the final tiebreak because two events at the same
     * minute must not swap places between two loads of identical data — a cell that
     * reshuffles on every recomposition looks like a bug in the sync, not in a sort.
     * ⚠ The start-time then title pair is the same total order [CalendarMonthDots] and
     * `CalendarAgenda` use, so a cell and the day list beside it cannot disagree about
     * which event is "first".
     */
    fun byDate(
        occurrences: List<ResolvedOccurrence>,
        calendars: List<CalendarSummary>,
    ): Map<LocalDate, List<MonthCellEvent>> {
        val colours = calendars.associate { it.calendarId to it.colorArgb }
        return occurrences
            .groupBy { it.startLocal.date }
            .mapValues { (_, rows) ->
                rows
                    .sortedWith(
                        compareBy({ !it.allDay }, { it.startLocal.time }, { it.title }),
                    )
                    .map { MonthCellEvent(it, colours[it.calendarId] ?: NEUTRAL) }
            }
    }

    /**
     * Fits [events] into [capacity] rows, spending one of them on "+N more" when they
     * do not all fit.
     *
     * ⚠⚠ **The overflow row costs a slot, and forgetting that is the bug this function
     * exists to hold.** With room for three and four events, the answer is *two* events
     * and "+2 more" — not three plus a fourth row that overflows the cell, and not three
     * with the fourth silently dropped. Silently dropping is the worse of the two: a
     * user cannot notice what a calendar never told them about.
     *
     * ⚠ A [capacity] of zero yields nothing shown and everything hidden. The caller is
     * expected to fall back to the dot markers there — a cell too short for one title is
     * too short for "+3 more" as well.
     */
    fun fit(events: List<MonthCellEvent>, capacity: Int): MonthCellFit = when {
        capacity <= 0 -> MonthCellFit(emptyList(), events.size)
        events.size <= capacity -> MonthCellFit(events, 0)
        else -> MonthCellFit(events.take(capacity - 1), events.size - (capacity - 1))
    }
}
