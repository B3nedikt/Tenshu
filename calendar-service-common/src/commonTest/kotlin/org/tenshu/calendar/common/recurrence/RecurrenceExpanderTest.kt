package org.tenshu.calendar.common.recurrence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * The recurrence walk (`calendar-app.md` §3 D2).
 *
 * ⭐ Every case here is expressed in whole dates with explicit windows: the
 * expander takes no clock and no zone, so these tests cannot go stale in
 * January and cannot pass in one timezone and fail in another.
 */
class RecurrenceExpanderTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate(y, m, day)

    private fun expand(
        rrule: String?,
        start: LocalDate,
        from: LocalDate,
        to: LocalDate,
        exceptions: Set<LocalDate> = emptySet(),
    ) = RecurrenceExpander.expand(
        rule = rrule?.let { RecurrenceRule.parse(it) },
        start = start,
        windowStart = from,
        windowEnd = to,
        exceptions = exceptions,
    )

    // --- one-off events -----------------------------------------------------

    @Test
    fun a_non_recurring_event_yields_its_anchor_when_the_window_contains_it() {
        assertEquals(
            listOf(d(2026, 8, 5)),
            expand(null, d(2026, 8, 5), d(2026, 8, 1), d(2026, 8, 31)),
        )
    }

    @Test
    fun a_non_recurring_event_outside_the_window_yields_nothing() {
        assertTrue(expand(null, d(2026, 7, 5), d(2026, 8, 1), d(2026, 8, 31)).isEmpty())
    }

    /** An inverted window is a caller bug, but it must not loop or throw. */
    @Test
    fun an_inverted_window_yields_nothing() {
        assertTrue(expand("FREQ=DAILY", d(2026, 8, 1), d(2026, 8, 31), d(2026, 8, 1)).isEmpty())
    }

    // --- the anchor ---------------------------------------------------------

    /**
     * ⭐ RFC 5545: `DTSTART` is always an occurrence, even when it does not
     * satisfy the rule's own `BYDAY`. Dropping it is the classic expander bug —
     * the series appears to start a week late.
     */
    @Test
    fun the_anchor_is_always_an_occurrence_even_when_it_does_not_match_byday() {
        // 2026-08-05 is a Wednesday; the rule says Tuesdays.
        val result = expand("FREQ=WEEKLY;BYDAY=TU", d(2026, 8, 5), d(2026, 8, 1), d(2026, 8, 20))
        assertEquals(d(2026, 8, 5), result.first())
        assertEquals(listOf(d(2026, 8, 5), d(2026, 8, 11), d(2026, 8, 18)), result)
    }

    // --- daily / weekly -----------------------------------------------------

    @Test
    fun daily_with_an_interval_steps_by_that_many_days() {
        assertEquals(
            listOf(d(2026, 8, 1), d(2026, 8, 4), d(2026, 8, 7), d(2026, 8, 10)),
            expand("FREQ=DAILY;INTERVAL=3", d(2026, 8, 1), d(2026, 8, 1), d(2026, 8, 11)),
        )
    }

    @Test
    fun weekly_with_several_weekdays_emits_each_of_them() {
        // Anchor Monday 2026-08-03; rule says Mon + Thu.
        assertEquals(
            listOf(d(2026, 8, 3), d(2026, 8, 6), d(2026, 8, 10), d(2026, 8, 13)),
            expand("FREQ=WEEKLY;BYDAY=MO,TH", d(2026, 8, 3), d(2026, 8, 1), d(2026, 8, 15)),
        )
    }

    /**
     * ⚠ Fortnightly counts **weeks from the anchor's week**, not days ÷ 7 — the
     * latter drifts whenever the anchor is not the first day of its week.
     */
    @Test
    fun fortnightly_counts_whole_weeks_from_the_anchors_week() {
        // Anchor Wednesday 2026-08-05, every other week on Mon+Wed.
        val result = expand("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", d(2026, 8, 5), d(2026, 8, 1), d(2026, 9, 3))
        assertEquals(
            listOf(
                d(2026, 8, 5),   // anchor (its own week is week 0)
                d(2026, 8, 17), d(2026, 8, 19), // week 2
                d(2026, 8, 31), d(2026, 9, 2),  // week 4
            ),
            result,
        )
        // The intervening week is genuinely skipped.
        assertTrue(d(2026, 8, 10) !in result)
        assertTrue(d(2026, 8, 12) !in result)
    }

    // --- monthly ------------------------------------------------------------

    @Test
    fun monthly_on_a_day_of_month_walks_months() {
        assertEquals(
            listOf(d(2026, 1, 15), d(2026, 2, 15), d(2026, 3, 15)),
            expand("FREQ=MONTHLY", d(2026, 1, 15), d(2026, 1, 1), d(2026, 3, 31)),
        )
    }

    /**
     * ⚠⚠ The month-length trap. "The 31st, monthly" has no February — RFC 5545
     * *skips* such an occurrence. A naive `plus(1, MONTH)` on a 31st rolls into
     * the 1st or 2nd of the next month and invents dates nobody asked for.
     */
    @Test
    fun monthly_on_the_31st_skips_months_that_have_no_31st() {
        val result = expand(
            "FREQ=MONTHLY;BYMONTHDAY=31",
            start = d(2026, 1, 31),
            from = d(2026, 1, 1),
            to = d(2026, 6, 30),
        )
        assertEquals(
            listOf(d(2026, 1, 31), d(2026, 3, 31), d(2026, 5, 31)),
            result,
        )
        // Nothing was invented in the short months.
        assertTrue(result.none { it.month.number == 2 || it.month.number == 4 || it.month.number == 6 })
    }

    @Test
    fun monthly_by_negative_monthday_is_the_last_day() {
        assertEquals(
            listOf(d(2026, 1, 31), d(2026, 2, 28), d(2026, 3, 31)),
            expand("FREQ=MONTHLY;BYMONTHDAY=-1", d(2026, 1, 31), d(2026, 1, 1), d(2026, 3, 31)),
        )
    }

    /** "The second Monday of the month". */
    @Test
    fun monthly_by_positive_ordinal_weekday() {
        assertEquals(
            listOf(d(2026, 1, 12), d(2026, 2, 9), d(2026, 3, 9)),
            expand("FREQ=MONTHLY;BYDAY=2MO", d(2026, 1, 12), d(2026, 1, 1), d(2026, 3, 31)),
        )
    }

    /** ⚠ "The last Friday of the month" — the negative ordinal is not exotic. */
    @Test
    fun monthly_by_negative_ordinal_weekday() {
        assertEquals(
            listOf(d(2026, 1, 30), d(2026, 2, 27), d(2026, 3, 27)),
            expand("FREQ=MONTHLY;BYDAY=-1FR", d(2026, 1, 30), d(2026, 1, 1), d(2026, 3, 31)),
        )
    }

    // --- yearly -------------------------------------------------------------

    @Test
    fun yearly_repeats_the_anchor_date() {
        assertEquals(
            listOf(d(2026, 3, 14), d(2027, 3, 14), d(2028, 3, 14)),
            expand("FREQ=YEARLY", d(2026, 3, 14), d(2026, 1, 1), d(2028, 12, 31)),
        )
    }

    /**
     * A 29 February anchor. Clamping keeps the walk on a real date; the
     * `BYMONTHDAY` test then skips the non-leap years.
     */
    @Test
    fun yearly_on_the_29th_of_february_only_lands_in_leap_years() {
        assertEquals(
            listOf(d(2024, 2, 29), d(2028, 2, 29)),
            expand(
                "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29",
                start = d(2024, 2, 29),
                from = d(2024, 1, 1),
                to = d(2029, 12, 31),
            ),
        )
    }

    // --- bounds -------------------------------------------------------------

    @Test
    fun count_bounds_the_series() {
        assertEquals(
            listOf(d(2026, 8, 1), d(2026, 8, 2), d(2026, 8, 3)),
            expand("FREQ=DAILY;COUNT=3", d(2026, 8, 1), d(2026, 8, 1), d(2026, 8, 31)),
        )
    }

    /**
     * ⚠⚠ `COUNT` counts from the **anchor**, not from the window. A windowed read
     * past the end of a bounded series must produce nothing — which only works
     * if the walk counts from the anchor rather than starting at the window.
     */
    @Test
    fun a_window_beyond_a_counted_series_yields_nothing() {
        assertTrue(
            expand("FREQ=DAILY;COUNT=5", d(2026, 8, 1), d(2026, 9, 1), d(2026, 9, 30)).isEmpty(),
        )
    }

    @Test
    fun until_bounds_the_series_inclusively() {
        assertEquals(
            listOf(d(2026, 8, 1), d(2026, 8, 2), d(2026, 8, 3)),
            expand("FREQ=DAILY;UNTIL=20260803", d(2026, 8, 1), d(2026, 8, 1), d(2026, 8, 31)),
        )
    }

    // --- exceptions ---------------------------------------------------------

    @Test
    fun exception_dates_are_removed_from_the_result() {
        assertEquals(
            listOf(d(2026, 8, 1), d(2026, 8, 3)),
            expand(
                "FREQ=DAILY",
                d(2026, 8, 1),
                d(2026, 8, 1),
                d(2026, 8, 3),
                exceptions = setOf(d(2026, 8, 2)),
            ),
        )
    }

    /**
     * ⚠⚠ An excepted occurrence still consumes a `COUNT` slot. The other reading
     * — pulling a replacement forward from beyond the end — silently extends
     * every series the user prunes, which is both wrong per RFC 5545 and
     * surprising.
     */
    @Test
    fun an_exception_consumes_its_count_slot_rather_than_pulling_one_forward() {
        val result = expand(
            "FREQ=DAILY;COUNT=3",
            d(2026, 8, 1),
            d(2026, 8, 1),
            d(2026, 8, 31),
            exceptions = setOf(d(2026, 8, 2)),
        )
        assertEquals(listOf(d(2026, 8, 1), d(2026, 8, 3)), result)
        assertTrue(d(2026, 8, 4) !in result, "a fourth day would mean COUNT was re-filled")
    }

    // --- termination --------------------------------------------------------

    /**
     * ⚠⚠ An unsatisfiable rule — there is no 31st of February, ever — must
     * terminate on the candidate ceiling rather than walking to the end of time.
     */
    @Test
    fun an_unsatisfiable_rule_terminates_and_yields_nothing() {
        assertTrue(
            expand(
                "FREQ=MONTHLY;BYMONTH=2;BYMONTHDAY=31",
                start = d(2026, 1, 1),
                from = d(2026, 1, 2),
                to = d(2030, 12, 31),
            ).isEmpty(),
        )
    }

    /** A far-future window on an unbounded daily rule stays cheap and correct. */
    @Test
    fun a_narrow_window_far_from_the_anchor_returns_only_that_window() {
        val result = expand("FREQ=DAILY", d(2026, 1, 1), d(2026, 6, 1), d(2026, 6, 3))
        assertEquals(listOf(d(2026, 6, 1), d(2026, 6, 2), d(2026, 6, 3)), result)
    }
}
