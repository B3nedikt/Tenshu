package app.tenshu.calendar.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Locale-correct names and labels for calendar surfaces.
 *
 * ⚠⚠ **This is deliberately NOT a string catalogue.** ADR 0016 says every
 * user-facing string is authored through `stringResource(…)`, and weekday and
 * month names look like they qualify — but they are *locale data*, not copy.
 * Translating them by hand would mean re-deriving, badly and per language, a
 * table every platform already ships: seven weekdays × two lengths × twelve
 * months × every locale, plus the 12-vs-24-hour convention and the day/month
 * ordering. So CA-2's hard-coded English is closed by asking the platform,
 * which is the ADR's intent rather than a hole in it.
 *
 * ⚠ Everything here follows the **device** locale, and none of it is stored —
 * these are display labels only. What gets written is always the civil ISO form
 * (`calendar-app.md` §3 D3).
 *
 * ⚠ Lives in `-shared-logic` (Compose-free) so the SwiftUI shell shares the
 * same seam rather than growing a second, differently-wrong formatter.
 */
expect object CalendarDateFormat {

    /** An abbreviated weekday, for the month grid's column headers ("Mon"). */
    fun weekdayShort(day: DayOfWeek): String

    /** A full month name, for the month header ("August"). */
    fun monthName(month: Int): String

    /** A medium-length date in the locale's own field order ("10 Aug 2026"). */
    fun mediumDate(date: LocalDate): String

    /**
     * A time of day in the locale's convention.
     *
     * ⚠ 12-hour vs 24-hour is a locale property, not a preference we get to
     * guess — hard-coding `HH:mm` shows "14:00" to someone whose every other app
     * says "2 PM".
     */
    fun shortTime(time: LocalTime): String

    /**
     * Whether this locale writes times on a 24-hour clock.
     *
     * ⚠ Exposed because a *picker* has to be told, and it must agree with what
     * [shortTime] renders beside it — a 12-hour dial that produces "14:00"
     * labels is worse than either convention alone. Asked of the platform
     * rather than sniffed out of a formatted string, which breaks the moment a
     * locale uses non-Latin digits.
     */
    fun uses24HourClock(): Boolean
}

/** The month header a calendar screen shows above its grid. */
fun CalendarDateFormat.monthAndYear(year: Int, month: Int): String =
    "${monthName(month)} $year"
