package app.tenshu.photos.time

import app.tenshu.photos.ui.screens.feed.FeedDensity
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Every timezone-shaped function Photos draws with: section starts, the scrubber's years, and the
 * header / viewer / About-sheet labels.
 *
 * ⚠⚠ **Here, not in `:photos-shared-ui`** (`photos-ios.md` §1.2, PIO-0). They used to live beside
 * the Compose screens so this module could stay date-time-free — and that made them unreachable
 * from the SwiftUI shell, whose only alternative was the view models' UTC-ish defaults. Those put a
 * photograph taken late on 31 December under the wrong year, silently. One copy, reached by every
 * shell, is the fix; `:calendar-shared-logic` carries the same dependency for the same reason.
 *
 * ⚠ The device's **current** zone, never one cached for the process: a phone that crosses a
 * timezone mid-scroll should re-section on the next render, and a zone held until relaunch would
 * leave the feed showing yesterday's headers. Each function has a zone-taking twin for tests.
 *
 * ⚠⚠ But not resolved PER CALL either ([zone]): on Darwin `TimeZone.currentSystemDefault()` reads
 * and parses the zone's tzdb file every time, about half a millisecond in a Debug build. These
 * functions run once per photograph — Collections' Years walk, the feed's separators — and mostly
 * on the main thread, so a 5 000-photograph library froze the app for 2.7 s on every return to the
 * Collections tab, 91 % of it in that one call (`photos-ios.md` §8.22).
 *
 * ⚠ The labels are deliberately plain and locale-independent for now. A real date format is a
 * `:ui-core` concern with a locale story attached (Calendar owns that question). ⚠ The SwiftUI shell
 * uses these *verbatim* rather than `DateFormatter`, so the feed's headers and the scrubber's bubble
 * agree on both platforms by calling the same function.
 */
@OptIn(ExperimentalTime::class)
object PhotoDates {

    private val held = HeldZone()

    /** The device's zone, as every function here reads it. */
    internal fun zone(): TimeZone = held.get()

    /** Local midnight for an instant. */
    fun dayStart(epochMs: Long): Long = dayStart(epochMs, zone())

    internal fun dayStart(epochMs: Long, zone: TimeZone): Long =
        dateOf(epochMs, zone).atStartOfDayIn(zone).toEpochMilliseconds()

    /** The calendar year an instant falls in, in the device's current zone (PH-3's Years, PH-17c′'s pills). */
    fun yearOf(epochMs: Long): Int = yearOf(epochMs, zone())

    internal fun yearOf(epochMs: Long, zone: TimeZone): Int = dateOf(epochMs, zone).year

    /**
     * The start of the section [epochMs] belongs to at [density], in the viewer's zone (S1).
     *
     * ⚠⚠ Local, not UTC-rounded. A photograph taken at 00:30 on 1 January belongs to the year that
     * has just begun where the user was standing, and arithmetic on epoch milliseconds puts it in
     * the one that just ended for anybody east of Greenwich.
     */
    fun sectionStart(density: FeedDensity, epochMs: Long): Long =
        sectionStart(density, epochMs, zone())

    internal fun sectionStart(density: FeedDensity, epochMs: Long, zone: TimeZone): Long {
        val date = dateOf(epochMs, zone)
        val start = when (density) {
            FeedDensity.Mosaic, FeedDensity.Day -> date
            FeedDensity.Month -> LocalDate(date.year, date.month, 1)
            FeedDensity.Year -> LocalDate(date.year, 1, 1)
        }
        return start.atStartOfDayIn(zone).toEpochMilliseconds()
    }

    /** A day header — "17 December 2024". */
    fun dayLabel(dayStartMs: Long): String = dayLabel(dayStartMs, zone())

    internal fun dayLabel(dayStartMs: Long, zone: TimeZone): String {
        val date = dateOf(dayStartMs, zone)
        return "${date.day} ${monthName(date.month.ordinal + 1)} ${date.year}"
    }

    /**
     * What a section header reads at [density] — "17 December 2024", "December 2024", "2024".
     *
     * ⚠ The day form is [dayLabel] verbatim rather than a second implementation: the feed's
     * headers and the scrubber's bubble have to agree, and they agree by calling the same function.
     */
    fun sectionLabel(density: FeedDensity, sectionStartMs: Long): String =
        sectionLabel(density, sectionStartMs, zone())

    internal fun sectionLabel(density: FeedDensity, sectionStartMs: Long, zone: TimeZone): String {
        if (density == FeedDensity.Mosaic || density == FeedDensity.Day) return dayLabel(sectionStartMs, zone)
        val date = dateOf(sectionStartMs, zone)
        return when (density) {
            FeedDensity.Year -> date.year.toString()
            else -> monthName(date.month.ordinal + 1) + " " + date.year
        }
    }

    /**
     * The viewer bar's title — "9 Apr" (PH-9).
     *
     * ⚠ Short and year-less on purpose: it names the photograph you are looking at, not a calendar
     * position, and the feed's headers above already carry the year.
     */
    fun shortDate(epochMs: Long): String = shortDate(epochMs, zone())

    internal fun shortDate(epochMs: Long, zone: TimeZone): String {
        val date = dateOf(epochMs, zone)
        return "${date.day} ${monthName(date.month.ordinal + 1).take(3)}"
    }

    /** The viewer subtitle's time half — "21:26", in the device's current zone. */
    fun timeOfDay(epochMs: Long): String = timeOfDay(epochMs, zone())

    internal fun timeOfDay(epochMs: Long, zone: TimeZone): String {
        val time = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).time
        return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    }

    /**
     * The About sheet's header — `"Thu, 9 Apr 2026  ·  21:26"` (PH-10).
     *
     * ⚠ Carries the **weekday**, which the bar's short date does not: a panel about one photograph
     * is where "which day was that?" is actually the question, and the day name answers it faster
     * than the date does.
     */
    fun capturedAt(epochMs: Long): String = capturedAt(epochMs, zone())

    internal fun capturedAt(epochMs: Long, zone: TimeZone): String {
        val at = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        val day = at.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
        val month = monthName(at.date.month.ordinal + 1).take(3)
        return "$day, ${at.date.day} $month ${at.date.year}  ·  ${timeOfDay(epochMs, zone)}"
    }

    private fun dateOf(epochMs: Long, zone: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date

    private fun monthName(number: Int): String = when (number) {
        1 -> "January"; 2 -> "February"; 3 -> "March"; 4 -> "April"
        5 -> "May"; 6 -> "June"; 7 -> "July"; 8 -> "August"
        9 -> "September"; 10 -> "October"; 11 -> "November"; else -> "December"
    }
}

/**
 * The device's zone, resolved at most once per [heldFor] — a walk over a library pays for it once,
 * and a zone change is still seen by the next render.
 *
 * ⚠ A race resolves twice and keeps either answer; both are the zone of that moment.
 */
internal class HeldZone(
    private val heldFor: Duration = 1.seconds,
    private val clock: TimeSource = TimeSource.Monotonic,
    private val resolve: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    private class Resolved(val zone: TimeZone, val at: TimeMark)

    @Volatile
    private var resolved: Resolved? = null

    fun get(): TimeZone {
        resolved?.let { last -> if (last.at.elapsedNow() < heldFor) return last.zone }
        return resolve().also { resolved = Resolved(it, clock.markNow()) }
    }
}
