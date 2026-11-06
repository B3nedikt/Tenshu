package app.tenshu.calendar.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * What a host hands the calendar (CA-8a).
 *
 * ⚠ Small, and worth having anyway: the only other caller of these two functions is Swift, which
 * nothing here compiles. This file is the proof that the expressions inside them typecheck and do
 * what the iOS shell assumes.
 */
class CalendarHostInputTest {

    @Test
    fun a_real_date_comes_through_unchanged() {
        assertEquals(LocalDate(2026, 8, 9), CalendarHostInput.today(2026, 8, 9))
    }

    /**
     * ⚠⚠ A shell that crashes on a date component it read from the platform is worse than one that
     * is a day wrong for a frame. The fallback is deliberately the epoch, not "some nearby date":
     * an obviously wrong answer gets reported, a plausible one gets lived with.
     */
    @Test
    fun a_nonsense_date_falls_back_rather_than_throwing() {
        assertEquals(CalendarHostInput.FALLBACK, CalendarHostInput.today(2026, 13, 1))
        assertEquals(CalendarHostInput.FALLBACK, CalendarHostInput.today(2026, 2, 30))
        assertEquals(CalendarHostInput.FALLBACK, CalendarHostInput.today(2026, 0, 0))
        assertEquals(LocalDate(1970, 1, 1), CalendarHostInput.FALLBACK)
    }

    /** ⚠ A leap day is a real date and must not take the fallback. */
    @Test
    fun a_leap_day_is_a_real_date() {
        assertEquals(LocalDate(2028, 2, 29), CalendarHostInput.today(2028, 2, 29))
    }

    @Test
    fun the_week_start_is_iso_numbered() {
        assertEquals(DayOfWeek.MONDAY, CalendarHostInput.weekStart(1))
        assertEquals(DayOfWeek.SUNDAY, CalendarHostInput.weekStart(7))
        assertEquals(DayOfWeek.SATURDAY, CalendarHostInput.weekStart(6))
    }

    /**
     * ⚠⚠ The value most likely to arrive from a mistake, and the one that matters: Foundation's
     * `firstWeekday` is 1 = *Sunday*, so a caller that forgot to shift would pass 1 for Sunday and
     * silently get Monday. That is why the shift lives at the call site and this function's
     * parameter is named for its scheme — the test pins the contract, not the bug.
     */
    @Test
    fun an_out_of_range_day_falls_back_to_monday() {
        assertEquals(DayOfWeek.MONDAY, CalendarHostInput.weekStart(0))
        assertEquals(DayOfWeek.MONDAY, CalendarHostInput.weekStart(8))
        assertEquals(DayOfWeek.MONDAY, CalendarHostInput.weekStart(-1))
    }

    // --- reading parts back out (CA-8d) --------------------------------------

    /**
     * ⚠⚠ The read direction exists so the Swift editor never reaches into a bridged `LocalDate`
     * for `year` / `monthNumber` / `day` — accessor spellings kotlinx-datetime has already
     * deprecated once, and that nothing in this environment can check.
     */
    @Test
    fun a_date_comes_apart_into_the_integers_a_picker_speaks() {
        assertEquals(
            CalendarDateParts(2026, 8, 9),
            CalendarHostInput.dateParts(LocalDate(2026, 8, 9)),
        )
    }

    /** ⚠ A picker must show something, so a null date answers with the fallback's parts. */
    @Test
    fun no_date_still_yields_parts() {
        assertEquals(CalendarDateParts(1970, 1, 1), CalendarHostInput.dateParts(null))
    }

    @Test
    fun a_time_comes_apart_and_round_trips() {
        assertEquals(CalendarTimeParts(14, 35), CalendarHostInput.timeParts(LocalTime(14, 35)))
        assertEquals(LocalTime(14, 35), CalendarHostInput.time(14, 35))
    }

    /** ⚠ Midnight is the fallback because it is unambiguous in every calendar. */
    @Test
    fun no_time_is_midnight() {
        assertEquals(CalendarTimeParts(0, 0), CalendarHostInput.timeParts(null))
        assertEquals(LocalTime(0, 0), CalendarHostInput.FALLBACK_TIME)
    }

    @Test
    fun a_nonsense_time_falls_back_rather_than_throwing() {
        assertEquals(CalendarHostInput.FALLBACK_TIME, CalendarHostInput.time(24, 0))
        assertEquals(CalendarHostInput.FALLBACK_TIME, CalendarHostInput.time(-1, 30))
        assertEquals(CalendarHostInput.FALLBACK_TIME, CalendarHostInput.time(12, 60))
    }

    /** ⚠ 23:59 is a real time and must not take the fallback. */
    @Test
    fun the_last_minute_of_the_day_is_a_real_time() {
        assertEquals(LocalTime(23, 59), CalendarHostInput.time(23, 59))
    }
}
