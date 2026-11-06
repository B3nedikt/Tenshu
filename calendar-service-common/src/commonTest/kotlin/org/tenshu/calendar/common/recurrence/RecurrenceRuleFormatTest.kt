package org.tenshu.calendar.common.recurrence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/**
 * The render half of the `RRULE` codec (CA-3).
 *
 * ⚠⚠ The property that matters is not "produces a nice string" but **parse ∘
 * format == identity** for every rule we claim to understand. A renderer that
 * drifts from the parser turns every save into silent data loss, and the drift
 * would show up only as an event that recurs differently after being edited.
 */
class RecurrenceRuleFormatTest {

    private fun roundTrip(rule: RecurrenceRule) {
        val reparsed = RecurrenceRule.parse(rule.format())
        assertEquals(rule, reparsed, "round trip broke for ${rule.format()}")
    }

    @Test
    fun the_four_bare_frequencies_round_trip() {
        Frequency.entries.forEach { roundTrip(RecurrenceRule(it)) }
    }

    @Test
    fun a_bare_frequency_renders_as_just_freq() {
        assertEquals("FREQ=DAILY", RecurrenceRule(Frequency.DAILY).format())
    }

    /** ⚠ `INTERVAL=1` is the default; emitting it would be noise the parser then re-reads as 1. */
    @Test
    fun a_default_interval_is_omitted_but_a_real_one_survives() {
        assertEquals("FREQ=WEEKLY", RecurrenceRule(Frequency.WEEKLY, interval = 1).format())
        assertEquals("FREQ=WEEKLY;INTERVAL=2", RecurrenceRule(Frequency.WEEKLY, interval = 2).format())
        roundTrip(RecurrenceRule(Frequency.WEEKLY, interval = 3))
    }

    @Test
    fun weekdays_render_with_their_two_letter_codes() {
        val rule = RecurrenceRule(
            frequency = Frequency.WEEKLY,
            byDay = listOf(
                WeekDayNum(0, DayOfWeek.MONDAY),
                WeekDayNum(0, DayOfWeek.WEDNESDAY),
                WeekDayNum(0, DayOfWeek.FRIDAY),
            ),
        )
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", rule.format())
        roundTrip(rule)
    }

    /** ⚠ Both ordinal forms — "the second Monday" and "the last Friday". */
    @Test
    fun ordinal_weekdays_keep_their_sign() {
        val second = RecurrenceRule(Frequency.MONTHLY, byDay = listOf(WeekDayNum(2, DayOfWeek.MONDAY)))
        val last = RecurrenceRule(Frequency.MONTHLY, byDay = listOf(WeekDayNum(-1, DayOfWeek.FRIDAY)))

        assertEquals("FREQ=MONTHLY;BYDAY=2MO", second.format())
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", last.format())
        roundTrip(second)
        roundTrip(last)
    }

    @Test
    fun month_days_and_months_round_trip_including_negatives() {
        roundTrip(RecurrenceRule(Frequency.MONTHLY, byMonthDay = listOf(1, 15, -1)))
        roundTrip(RecurrenceRule(Frequency.YEARLY, byMonth = listOf(3, 6, 9, 12)))
    }

    @Test
    fun a_count_bound_round_trips() {
        roundTrip(RecurrenceRule(Frequency.DAILY, count = 10))
    }

    @Test
    fun an_until_bound_round_trips_as_a_basic_date() {
        val rule = RecurrenceRule(Frequency.DAILY, until = LocalDate(2026, 12, 31))
        assertEquals("FREQ=DAILY;UNTIL=20261231", rule.format())
        roundTrip(rule)
    }

    /**
     * ⚠⚠ RFC 5545 forbids `COUNT` and `UNTIL` in the same rule. The renderer has
     * to pick one rather than emit an invalid string, and it must pick the *same*
     * one the parser would keep — otherwise a rule holding both would round-trip
     * into a different rule.
     */
    @Test
    fun count_wins_when_a_rule_somehow_carries_both() {
        val rule = RecurrenceRule(Frequency.DAILY, count = 5, until = LocalDate(2026, 12, 31))
        val rendered = rule.format()

        assertTrue(rendered.contains("COUNT=5"))
        assertFalse(rendered.contains("UNTIL"), "an invalid rule must not be emitted")
    }

    @Test
    fun a_non_default_week_start_survives() {
        val rule = RecurrenceRule(Frequency.WEEKLY, weekStart = DayOfWeek.SUNDAY)
        assertEquals("FREQ=WEEKLY;WKST=SU", rule.format())
        roundTrip(rule)
    }

    /**
     * ⚠⚠ The documented lossiness, pinned. A rule carrying a part we do not
     * expand by loses that part when rendered — which is precisely why the editor
     * refuses to edit one (`isFullyUnderstood`). If this ever stops being true
     * the editor's refusal can be relaxed; until then the test states the cost.
     */
    @Test
    fun unsupported_parts_are_dropped_by_the_renderer_not_smuggled_through() {
        val parsed = RecurrenceRule.parse("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=-1")!!
        assertFalse(parsed.isFullyUnderstood)
        assertEquals(listOf("BYSETPOS=-1"), parsed.unsupportedParts)

        val rendered = parsed.format()
        assertFalse(rendered.contains("BYSETPOS"), "re-emitting it would claim we expand by it")
        assertEquals("FREQ=MONTHLY;BYDAY=MO", rendered)
    }
}
