package org.tenshu.calendar.common.recurrence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** `RRULE` parsing (`calendar-app.md` §3 D2 / Q2). */
class RecurrenceRuleParseTest {

    @Test
    fun parses_a_plain_weekly_rule() {
        val rule = RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=TU")!!
        assertEquals(Frequency.WEEKLY, rule.frequency)
        assertEquals(1, rule.interval)
        assertEquals(listOf(WeekDayNum(0, DayOfWeek.TUESDAY)), rule.byDay)
        assertTrue(rule.isFullyUnderstood)
    }

    /** Real-world strings carry the `RRULE:` prefix about half the time. */
    @Test
    fun tolerates_the_rrule_prefix() {
        assertEquals(Frequency.DAILY, RecurrenceRule.parse("RRULE:FREQ=DAILY")!!.frequency)
    }

    @Test
    fun parses_ordinal_byday_entries() {
        val rule = RecurrenceRule.parse("FREQ=MONTHLY;BYDAY=2MO,-1FR")!!
        assertEquals(
            listOf(WeekDayNum(2, DayOfWeek.MONDAY), WeekDayNum(-1, DayOfWeek.FRIDAY)),
            rule.byDay,
        )
    }

    @Test
    fun parses_until_as_a_date_from_either_form() {
        assertEquals(LocalDate(2026, 12, 31), RecurrenceRule.parse("FREQ=DAILY;UNTIL=20261231")!!.until)
        assertEquals(
            LocalDate(2026, 12, 31),
            RecurrenceRule.parse("FREQ=DAILY;UNTIL=20261231T235959Z")!!.until,
        )
    }

    @Test
    fun blank_input_is_not_a_recurrence() {
        assertNull(RecurrenceRule.parse(""))
        assertNull(RecurrenceRule.parse("   "))
    }

    /**
     * ⚠ A rule we cannot understand must not make the *event* unreadable — it
     * simply does not recur. Null is "does not recur", never an error channel.
     */
    @Test
    fun a_rule_without_a_usable_freq_is_null_rather_than_an_error() {
        assertNull(RecurrenceRule.parse("BYDAY=MO"))
        assertNull(RecurrenceRule.parse("FREQ=FORTNIGHTLY"))
        assertNull(RecurrenceRule.parse("garbage"))
    }

    /**
     * ⚠⚠ `INTERVAL=0` would make the expander step by zero days and loop until
     * its candidate ceiling. This string can come from a third party's exporter,
     * so it is coerced rather than trusted.
     */
    @Test
    fun a_zero_or_negative_interval_is_coerced_to_one() {
        assertEquals(1, RecurrenceRule.parse("FREQ=DAILY;INTERVAL=0")!!.interval)
        assertEquals(1, RecurrenceRule.parse("FREQ=DAILY;INTERVAL=-3")!!.interval)
        assertEquals(1, RecurrenceRule.parse("FREQ=DAILY;INTERVAL=x")!!.interval)
    }

    /**
     * Unsupported parts are *preserved and flagged*, not dropped: the round trip
     * through `EventSource` has to stay lossless, and CA-3's editor needs to know
     * it is looking at a rule it cannot fully model.
     */
    @Test
    fun unsupported_parts_are_recorded_rather_than_discarded() {
        val rule = RecurrenceRule.parse("FREQ=MONTHLY;BYSETPOS=-1;BYDAY=MO")!!
        assertFalse(rule.isFullyUnderstood)
        assertEquals(listOf("BYSETPOS=-1"), rule.unsupportedParts)
        // ...but the parts we *do* understand still parsed.
        assertEquals(Frequency.MONTHLY, rule.frequency)
        assertEquals(listOf(WeekDayNum(0, DayOfWeek.MONDAY)), rule.byDay)
    }

    @Test
    fun out_of_range_bymonth_and_bymonthday_entries_are_dropped() {
        val rule = RecurrenceRule.parse("FREQ=YEARLY;BYMONTH=13,2;BYMONTHDAY=0,40,-1")!!
        assertEquals(listOf(2), rule.byMonth)
        assertEquals(listOf(-1), rule.byMonthDay)
    }

    @Test
    fun wkst_defaults_to_monday_and_is_read_when_present() {
        assertEquals(DayOfWeek.MONDAY, RecurrenceRule.parse("FREQ=WEEKLY")!!.weekStart)
        assertEquals(DayOfWeek.SUNDAY, RecurrenceRule.parse("FREQ=WEEKLY;WKST=SU")!!.weekStart)
    }

    @Test
    fun part_names_are_case_insensitive() {
        val rule = RecurrenceRule.parse("freq=weekly;byday=we")!!
        assertEquals(Frequency.WEEKLY, rule.frequency)
        assertEquals(listOf(WeekDayNum(0, DayOfWeek.WEDNESDAY)), rule.byDay)
    }
}
