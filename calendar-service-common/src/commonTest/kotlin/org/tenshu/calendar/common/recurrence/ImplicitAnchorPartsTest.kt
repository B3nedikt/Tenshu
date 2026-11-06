package org.tenshu.calendar.common.recurrence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/**
 * RFC 5545's "absent means take it from `DTSTART`" rule — the parts a bare rule
 * inherits from its anchor.
 *
 * ⭐ **Why this file exists.** These cases were missing, and their absence was
 * invisible: mutating `addMonthsClamped` to drop its month-length clamp left
 * every one of the 45 original tests green, because the clamp is only reachable
 * for rules with **no `BY*` parts** and every recurrence test had one. The
 * probe that followed showed a bare `FREQ=MONTHLY` anchored on the 31st
 * happily emitting 28 February.
 *
 * ⚠⚠ The lesson worth keeping: *absent is not "any"*. A rule that omits
 * `BYMONTHDAY` is not unconstrained on day-of-month — it is constrained to the
 * anchor's.
 */
class ImplicitAnchorPartsTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate(y, m, day)

    private fun expand(rrule: String, start: LocalDate, from: LocalDate, to: LocalDate) =
        RecurrenceExpander.expand(
            rule = RecurrenceRule.parse(rrule),
            start = start,
            windowStart = from,
            windowEnd = to,
        )

    /**
     * ⚠⚠ The regression. A bare monthly rule anchored on the 31st behaves
     * exactly as if `BYMONTHDAY=31` had been written: the short months are
     * **skipped**, not clamped onto their last day.
     */
    @Test
    fun bare_monthly_from_the_31st_skips_short_months_rather_than_clamping_onto_them() {
        val result = expand("FREQ=MONTHLY", d(2026, 1, 31), d(2026, 1, 1), d(2026, 6, 30))
        assertEquals(listOf(d(2026, 1, 31), d(2026, 3, 31), d(2026, 5, 31)), result)
        assertTrue(
            result.none { it.day != 31 },
            "a clamped candidate (28 Feb, 30 Apr) must not be emitted",
        )
    }

    /** The same rule anchored on a day every month has, for contrast. */
    @Test
    fun bare_monthly_from_a_safe_day_lands_every_month() {
        assertEquals(
            listOf(d(2026, 1, 15), d(2026, 2, 15), d(2026, 3, 15)),
            expand("FREQ=MONTHLY", d(2026, 1, 15), d(2026, 1, 1), d(2026, 3, 31)),
        )
    }

    /** ⚠ A bare yearly rule inherits **both** month and day from the anchor. */
    @Test
    fun bare_yearly_from_february_29_only_lands_in_leap_years() {
        assertEquals(
            listOf(d(2024, 2, 29), d(2028, 2, 29)),
            expand("FREQ=YEARLY", d(2024, 2, 29), d(2024, 1, 1), d(2029, 12, 31)),
        )
    }

    @Test
    fun bare_yearly_from_a_safe_date_repeats_annually() {
        assertEquals(
            listOf(d(2026, 3, 14), d(2027, 3, 14)),
            expand("FREQ=YEARLY", d(2026, 3, 14), d(2026, 1, 1), d(2027, 12, 31)),
        )
    }

    /**
     * ⭐ An explicit `BYDAY` **displaces** the anchor's day-of-month — otherwise
     * "the second Monday, monthly" anchored on the 12th would only ever match
     * other 12ths, which is the opposite of what the rule says.
     */
    @Test
    fun an_explicit_byday_displaces_the_anchors_day_of_month() {
        assertEquals(
            listOf(d(2026, 1, 12), d(2026, 2, 9), d(2026, 3, 9)),
            expand("FREQ=MONTHLY;BYDAY=2MO", d(2026, 1, 12), d(2026, 1, 1), d(2026, 3, 31)),
        )
    }

    /** ...and so does an explicit `BYMONTHDAY`. */
    @Test
    fun an_explicit_bymonthday_displaces_the_anchors_day_of_month() {
        assertEquals(
            listOf(d(2026, 1, 15), d(2026, 2, 1), d(2026, 3, 1)),
            expand("FREQ=MONTHLY;BYMONTHDAY=1", d(2026, 1, 15), d(2026, 1, 1), d(2026, 3, 31)),
        )
    }

    /** An explicit `BYMONTH` displaces the anchor's month for yearly rules. */
    @Test
    fun an_explicit_bymonth_displaces_the_anchors_month() {
        assertEquals(
            listOf(d(2026, 3, 10), d(2026, 9, 10), d(2027, 3, 10)),
            expand("FREQ=YEARLY;BYMONTH=3,9", d(2026, 3, 10), d(2026, 1, 1), d(2027, 6, 30)),
        )
    }

    /**
     * ⚠ Daily and weekly rules take **no** implicit day-of-month: "every day"
     * anchored on the 31st means every day, not every 31st.
     */
    @Test
    fun daily_and_weekly_rules_inherit_no_day_of_month() {
        assertEquals(
            listOf(d(2026, 1, 31), d(2026, 2, 1), d(2026, 2, 2)),
            expand("FREQ=DAILY", d(2026, 1, 31), d(2026, 1, 31), d(2026, 2, 2)),
        )
    }
}
