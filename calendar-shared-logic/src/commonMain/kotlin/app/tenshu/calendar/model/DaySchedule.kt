package app.tenshu.calendar.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/**
 * One day of a time grid: the all-day band above, the laid-out column below.
 *
 * ⚠ The split is not cosmetic. An all-day event has no position on an hour
 * ruler — it is a property of the *day*, not of a time within it — so drawing
 * one as a midnight-to-midnight block would fill the entire column and bury
 * every real appointment behind it. Every stock calendar bands them, and this is
 * where that decision is made rather than in a renderer.
 */
data class DaySchedule(
    val date: LocalDate,
    /** Shown in the header band, in the order the repository returned them. */
    val allDay: List<ResolvedOccurrence>,
    /** Positioned in the hour grid, clipped to this day. */
    val timed: List<PositionedEvent<ResolvedOccurrence>>,
) {
    val isEmpty: Boolean get() = allDay.isEmpty() && timed.isEmpty()
}

/**
 * Builds one day's schedule from occurrences that may start or end elsewhere.
 *
 * ⚠⚠ **Clipping is the whole job.** A 22:00→02:00 event belongs in *two* days'
 * columns, drawn from 22:00 to the bottom on one and from the top to 02:00 on
 * the next — not once, wherever it happens to start. Getting this wrong makes
 * an overnight shift vanish from the morning it ends in.
 *
 * ⚠ A **timed** event spanning midnight is clipped into each day rather than
 * promoted to the all-day band. Only an event the user marked all-day is banded:
 * "22:00 until 02:00" is a time, and moving it out of the grid would lose the
 * only thing the grid is for.
 */
fun daySchedule(
    date: LocalDate,
    occurrences: List<ResolvedOccurrence>,
    minimumMinutes: Int = DayLayout.DEFAULT_MINIMUM_MINUTES,
): DaySchedule {
    val dayStart = date.startMinute()
    val dayEnd = dayStart + MINUTES_PER_DAY

    val allDay = occurrences.filter { it.allDay && it.coversDay(dayStart, dayEnd) }

    val timed = occurrences
        .filter { !it.allDay }
        .mapNotNull { occurrence ->
            val start = occurrence.startLocal.absoluteMinute()
            // ⚠ An inverted or absent end is treated as zero-length rather than
            // dropped: a row that survived storage should still be *visible*,
            // and `DayLayout`'s minimum height gives it something to be.
            val end = maxOf(occurrence.endLocal.absoluteMinute(), start)
            val intersects =
                if (start == end) start >= dayStart && start < dayEnd
                else end > dayStart && start < dayEnd
            if (!intersects) return@mapNotNull null

            occurrence to IntRange(
                (maxOf(start, dayStart) - dayStart).toInt(),
                (minOf(end, dayEnd) - dayStart).toInt(),
            )
        }

    return DaySchedule(
        date = date,
        allDay = allDay,
        timed = DayLayout.arrange(
            items = timed,
            minimumMinutes = minimumMinutes,
            interval = { it.second },
        ).map { positioned -> positioned.mapValue { (occurrence, _) -> occurrence } },
    )
}

/**
 * Whether an all-day occurrence covers [date]'s span.
 *
 * ⚠ The end is **exclusive** (§3 D3): a one-day all-day event runs from 00:00 to
 * 00:00 the next day, and an inclusive test would show it on both.
 */
private fun ResolvedOccurrence.coversDay(dayStart: Long, dayEnd: Long): Boolean {
    val start = startLocal.absoluteMinute()
    val end = maxOf(endLocal.absoluteMinute(), start + MINUTES_PER_DAY)
    return start < dayEnd && end > dayStart
}

/** Minutes from the epoch, so clipping never has to compare dates. */
private fun LocalDate.startMinute(): Long = toEpochDays() * MINUTES_PER_DAY

private fun LocalDateTime.absoluteMinute(): Long =
    date.startMinute() + hour * 60 + minute
