package app.tenshu.calendar.model

import app.tenshu.calendar.data.CalendarSummary
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/**
 * One day's marker under a month cell.
 *
 * ⚠ Year/month/day as plain integers rather than a `LocalDate`: the consumer is a UIKit delegate
 * that is handed a `DateComponents` and must answer synchronously, so it needs a key it can build
 * without constructing a bridged Kotlin value per cell per frame. [key] is that key.
 */
data class CalendarDayDot(
    val year: Int,
    val month: Int,
    val day: Int,
    /** How many occurrences start on this day. */
    val count: Int,
    val colorArgb: Int,
) {
    /** `20260809` — a total order on days, cheap to build on either side of the bridge. */
    val key: Int get() = year * 10_000 + month * 100 + day

    // ⚠⚠ The channels are unpacked here rather than in the renderer. `colorArgb` is ARGB — what
    // Android's `Color` packs and therefore what the schema carries — while UIKit wants the alpha
    // last, so a consumer has to shift. Doing it in Swift would put untestable bit arithmetic in a
    // file nothing in this environment compiles, and a swapped channel is invisible until someone
    // looks at a screen.
    //
    // ⚠⚠ The alpha is always opaque, whatever the stored byte says — what every Compose renderer
    // does too (`colorArgb or 0xFF000000`). Rows with alpha 0 exist (the first calendar used to be
    // seeded as `0x4285F4`), and honouring the byte made them invisible on iOS only.
    val alpha: Int get() = 0xFF
    val red: Int get() = (colorArgb shr 16) and 0xFF
    val green: Int get() = (colorArgb shr 8) and 0xFF
    val blue: Int get() = colorArgb and 0xFF
}

/**
 * The per-day markers a month grid draws (CA-8b).
 *
 * ⭐ Pure and tested, for the same reason as `CalendarAgenda`: its only consumer is a
 * `UICalendarView` delegate written in Swift, which nothing in this environment compiles. Deciding
 * *which days get a dot and what colour it is* here means the decision is checked; the delegate
 * only looks it up.
 *
 * ⚠ `CalendarUiState.countsByDate` already answers "how many", but not "what colour", and a
 * caller joining counts to calendars itself would be doing this work in a view.
 */
object CalendarMonthDots {

    /** ⚠ For an occurrence whose calendar is not in [calendars] — a race, or a hidden one. */
    const val NEUTRAL: Int = 0xFF9E9E9E.toInt()

    /** ⚠ Not a valid [CalendarDayDot.key] — year 0 does not exist in the ISO proleptic calendar. */
    const val NO_DAY: Int = 0

    /**
     * The lookup key for a day, or [NO_DAY] when there is none.
     *
     * ⚠ It takes a `LocalDate` because its caller *reads* one out of the ui state — never
     * constructs one. Returning a plain `Int` rather than a nullable keeps the Swift side free of
     * a boxed optional in a per-frame path.
     */
    fun keyOf(date: LocalDate?): Int =
        date?.let { it.year * 10_000 + it.month.number * 100 + it.day } ?: NO_DAY

    /**
     * Builds one dot per day that has anything on it.
     *
     * ⚠⚠ **One dot, coloured by the day's first event's calendar.** `UICalendarView` gives a cell
     * exactly one decoration; several dots would need a custom view, and at month scale three
     * 4-point dots are not a thing anyone reads. A day mixing calendars therefore shows only the
     * first one's colour — a real loss, recorded here rather than left to be noticed. [count] is
     * what carries "there is more than one".
     *
     * ⚠ "First" is by start time then title, the same total order `CalendarAgenda` uses, so the
     * colour cannot change between two loads of the same data.
     */
    fun of(
        occurrences: List<ResolvedOccurrence>,
        calendars: List<CalendarSummary>,
    ): List<CalendarDayDot> {
        val colours = calendars.associate { it.calendarId to it.colorArgb }
        return occurrences
            .groupBy { it.startLocal.date }
            .toList()
            .sortedBy { (date, _) -> date }
            .map { (date, rows) ->
                val first = rows.minWithOrNull(
                    compareBy({ it.startLocal.time }, { it.title }),
                )
                CalendarDayDot(
                    year = date.year,
                    month = date.month.number,
                    day = date.day,
                    count = rows.size,
                    colorArgb = colours[first?.calendarId] ?: NEUTRAL,
                )
            }
    }

    /**
     * The days whose marker differs between two builds — added, removed, or changed.
     *
     * ⚠⚠ **Removals count as much as additions.** A day whose last event was deleted keeps its dot
     * until something asks the grid to redraw it, and "redraw what changed" is easy to write as
     * "redraw what is now there".
     *
     * ⚠ It exists because `UICalendarView.reloadDecorations` re-asks its delegate for every date
     * handed to it: passing the whole visible range on each state emission makes a scroll stutter,
     * and the load that produced the new state is already the expensive part. Computing the diff
     * here rather than in the delegate keeps the one piece of real logic on the tested side.
     */
    fun changedKeys(
        previous: List<CalendarDayDot>,
        current: List<CalendarDayDot>,
    ): List<Int> {
        val before = previous.associateBy { it.key }
        val after = current.associateBy { it.key }
        return (before.keys + after.keys)
            .filter { key ->
                val a = before[key]
                val b = after[key]
                a?.count != b?.count || a?.colorArgb != b?.colorArgb
            }
            .sorted()
    }
}
