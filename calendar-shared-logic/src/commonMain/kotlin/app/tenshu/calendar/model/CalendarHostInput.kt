package app.tenshu.calendar.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number

/**
 * The two things a host has to tell the calendar that the calendar refuses to work out for itself:
 * **what today is**, and **which day a week starts on** (CA-8a).
 *
 * ⭐⭐ Both are ambient facts — a clock, a locale — and every layer below takes them as parameters
 * precisely so that none of them can disagree (`calendar-app.md` §3 D3). This object is where an
 * ambient answer stops being ambient, which on Compose is `calendarViewModelsModule()` and on iOS
 * is `CalendarSurface`.
 *
 * ⚠⚠ It exists as *common, tested* code because of who calls it. The iOS caller is Swift, which
 * cannot be compiled or tested in this repo's environment — so a bridged `LocalDate` constructor
 * or a `DayOfWeek` factory named in Swift would be verified for the first time on somebody's Mac.
 * Named here, the compiler checks it now. (It already caught one: `toEpochDays` returns `Long`.)
 */
object CalendarHostInput {

    /**
     * The host's "today", from plain integers.
     *
     * ⚠ Nonsense components fall back to [FALLBACK] rather than throwing. A shell that crashes on
     * a date component it read from the platform is worse than one that is a day wrong for a
     * frame — and unlike a crash, being wrong here is visible and self-correcting on the next
     * read.
     */
    fun today(year: Int, month: Int, day: Int): LocalDate =
        runCatching { LocalDate(year, month, day) }.getOrElse { FALLBACK }

    /**
     * ISO day number (1 = Monday … 7 = Sunday) → [DayOfWeek].
     *
     * ⚠⚠ **ISO, not Foundation.** Apple's `Calendar.firstWeekday` is 1 = *Sunday*, so a caller on
     * that platform has to shift before calling — and doing the shift at exactly one place is what
     * keeps the two numbering schemes from meeting inside a view. The parameter name says which
     * scheme this one speaks.
     *
     * ⚠ Out of range falls back to Monday, same reasoning as [today].
     */
    fun weekStart(isoDayNumber: Int): DayOfWeek =
        if (isoDayNumber in 1..7) DayOfWeek(isoDayNumber) else DayOfWeek.MONDAY

    /**
     * The host's time of day, from plain integers.
     *
     * ⚠ Out-of-range parts fall back to [FALLBACK_TIME] for the same reason as [today]: a shell
     * that crashes on a component it read from a platform picker is worse than one that is an hour
     * wrong for a frame.
     */
    fun time(hour: Int, minute: Int): LocalTime =
        runCatching { LocalTime(hour, minute) }.getOrElse { FALLBACK_TIME }

    /**
     * A date as the integers a platform picker speaks (CA-8d).
     *
     * ⚠⚠ The **read** direction, and it exists for the same reason [today] does: without it the
     * Swift editor would reach into a bridged `LocalDate` for `year` / `monthNumber` / `day` —
     * accessor spellings that kotlinx-datetime has already deprecated once, and that nothing in
     * this repo's environment can check.
     *
     * ⚠ A null date answers with [FALLBACK]'s parts rather than a null of its own: the caller is
     * filling a picker, which must show *something*, and an obviously wrong date is easier to
     * notice than an empty control.
     */
    fun dateParts(date: LocalDate?): CalendarDateParts {
        val resolved = date ?: FALLBACK
        return CalendarDateParts(resolved.year, resolved.month.number, resolved.day)
    }

    /** A time of day as the integers a platform picker speaks (CA-8d). See [dateParts]. */
    fun timeParts(time: LocalTime?): CalendarTimeParts {
        val resolved = time ?: FALLBACK_TIME
        return CalendarTimeParts(resolved.hour, resolved.minute)
    }

    /** The epoch day — a date that is obviously not today, rather than a plausible wrong one. */
    val FALLBACK: LocalDate = LocalDate.fromEpochDays(0)

    /** ⚠ Midnight: the one time of day that is unambiguous in every calendar. */
    val FALLBACK_TIME: LocalTime = LocalTime(0, 0)
}

/** A civil date, as a platform picker's integers. */
data class CalendarDateParts(val year: Int, val month: Int, val day: Int)

/** A civil time of day, as a platform picker's integers. */
data class CalendarTimeParts(val hour: Int, val minute: Int)
