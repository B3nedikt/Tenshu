package app.tenshu.calendar.model

import java.text.DateFormat
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

/**
 * ⚠⚠ **`java.text`, not `java.time`, and that is the point.** `java.time` needs
 * API 26 or core-library desugaring, and this module's `minSdk` is 24 with
 * desugaring off — the repo's existing workaround (`TimestampUtil` in
 * `:core-common`) is a `Build.VERSION.SDK_INT` branch with a `java.text`
 * fallback. Using only the fallback means ONE actual shared by Android and
 * desktop instead of two, with no version check that can rot.
 *
 * ⚠ Symbols and formats are resolved **per call** rather than cached in a `val`.
 * `DateFormat` instances are famously not thread-safe, and the default locale
 * can change under a running app (Android's in-place language switch) — a cached
 * formatter would keep rendering the old language until the process restarted.
 */
actual object CalendarDateFormat {

    actual fun weekdayShort(day: DayOfWeek): String =
        DateFormatSymbols.getInstance().shortWeekdays
            // ⚠ `Calendar.SUNDAY` is 1 and the array is 1-based with an empty
            // slot at 0, whereas ISO numbers Monday 1 … Sunday 7. Sunday's ISO 7
            // maps to index 1, and every other day to its ISO number + 1.
            .getOrNull(if (day.isoDayNumber == 7) Calendar.SUNDAY else day.isoDayNumber + 1)
            ?.takeIf { it.isNotBlank() }
            ?: day.name.take(3)

    actual fun monthName(month: Int): String =
        DateFormatSymbols.getInstance().months
            .getOrNull(month - 1)
            ?.takeIf { it.isNotBlank() }
            ?: month.toString()

    actual fun mediumDate(date: LocalDate): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(date.toJavaDate())

    actual fun shortTime(time: LocalTime): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(time.toJavaDate())

    /**
     * ⚠ CLDR's hour letters: `H`/`k` are the 24-hour fields, `h`/`K` the
     * 12-hour ones. Reading the pattern is exact; matching on the rendered
     * output would guess. Defaults to 24-hour if the format is not a
     * `SimpleDateFormat` — an unusual `DateFormat` subclass is not a reason to
     * assume the American convention.
     */
    actual fun uses24HourClock(): Boolean {
        val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT) as? SimpleDateFormat)
            ?.toPattern()
            ?: return true
        // ⚠ Quoted literals can hold any letter ("'at' H:mm"), so only the
        // unquoted halves count — the odd-indexed pieces are inside quotes.
        return pattern.split('\'')
            .filterIndexed { index, _ -> index % 2 == 0 }
            .any { part -> part.any { it == 'H' || it == 'k' } }
    }
}

/**
 * ⚠ Built through `Calendar` in the **default zone**, because that is the zone
 * the `DateFormat` will read it back in — the two have to agree or a civil date
 * can render as the day before. Nothing here crosses a zone boundary: a civil
 * value goes in and a label comes out.
 */
private fun LocalDate.toJavaDate(): Date =
    Calendar.getInstance().apply {
        clear()
        set(year, month.number - 1, day, 12, 0, 0)   // midday: never near a DST edge
    }.time

private fun LocalTime.toJavaDate(): Date =
    Calendar.getInstance().apply {
        clear()
        set(2000, 0, 1, hour, minute, 0)
    }.time
