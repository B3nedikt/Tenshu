package app.tenshu.calendar.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/**
 * The editor's recurrence vocabulary (CA-3).
 *
 * ⚠⚠ The direction that matters is **classify ∘ toRrule == identity**: whatever
 * the editor writes, re-opening the event must show the same choice. Anything
 * else means opening an event and pressing Save silently changes its recurrence.
 */
class RecurrencePresetTest {

    private val monday = LocalDate(2026, 8, 3)
    private val jan31 = LocalDate(2026, 1, 31)

    @Test
    fun every_offerable_preset_survives_a_write_and_a_reopen() {
        RecurrencePreset.entries
            .filter { it != RecurrencePreset.CUSTOM }
            .forEach { preset ->
                val rrule = preset.toRrule(monday)!!
                assertEquals(preset, RecurrencePreset.classify(rrule, monday), "round trip broke for $preset")
            }
    }

    @Test
    fun never_writes_no_rule_at_all() {
        assertEquals("", RecurrencePreset.NEVER.toRrule(monday))
        assertEquals(RecurrencePreset.NEVER, RecurrencePreset.classify("", monday))
        assertEquals(RecurrencePreset.NEVER, RecurrencePreset.classify("   ", monday))
    }

    /**
     * ⭐ Weekly writes an *explicit* `BYDAY` from the anchor. The implicit form
     * expands identically today but would silently follow the event if the user
     * later moved it to another weekday — "every Tuesday" would become "every
     * Thursday" without anyone saying so.
     */
    @Test
    fun weekly_pins_the_anchors_weekday_explicitly() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO", RecurrencePreset.WEEKLY.toRrule(monday))
        assertEquals("FREQ=WEEKLY;BYDAY=SA", RecurrencePreset.WEEKLY.toRrule(LocalDate(2026, 8, 8)))
    }

    /** ⚠ The other three carry no BY-parts: the anchor is DTSTART's job, per RFC 5545. */
    @Test
    fun the_other_cadences_are_bare_frequencies() {
        assertEquals("FREQ=DAILY", RecurrencePreset.DAILY.toRrule(jan31))
        assertEquals("FREQ=MONTHLY", RecurrencePreset.MONTHLY.toRrule(jan31))
        assertEquals("FREQ=YEARLY", RecurrencePreset.YEARLY.toRrule(jan31))
    }

    /**
     * ⚠⚠ `CUSTOM` is not a choice, only a state. Returning `null` is what forces
     * the editor to keep the existing rule instead of asking this enum for one it
     * cannot supply.
     */
    @Test
    fun custom_refuses_to_produce_a_rule() {
        assertNull(RecurrencePreset.CUSTOM.toRrule(monday))
    }

    // --- classification is deliberately conservative -------------------------

    /**
     * ⚠⚠ The whole point of the conservative classifier: a rule richer than our
     * presets must land in `CUSTOM`, because classifying it as `WEEKLY` would let
     * a save that touched only the *title* quietly drop the interval or the end
     * date.
     */
    @Test
    fun a_rule_richer_than_our_presets_is_custom_not_the_nearest_match() {
        listOf(
            "FREQ=WEEKLY;INTERVAL=2",                  // every other week
            "FREQ=DAILY;COUNT=10",                     // bounded
            "FREQ=DAILY;UNTIL=20261231",               // bounded
            "FREQ=MONTHLY;BYDAY=-1FR",                 // last Friday
            "FREQ=MONTHLY;BYMONTHDAY=15",              // the 15th
            "FREQ=YEARLY;BYMONTH=3",                   // every March
            "FREQ=WEEKLY;BYDAY=MO,WE,FR",              // several weekdays
        ).forEach { rrule ->
            assertEquals(RecurrencePreset.CUSTOM, RecurrencePreset.classify(rrule, monday), rrule)
        }
    }

    /** A rule carrying a part we do not expand by is `CUSTOM` regardless of shape. */
    @Test
    fun a_rule_with_unsupported_parts_is_custom() {
        assertEquals(
            RecurrencePreset.CUSTOM,
            RecurrencePreset.classify("FREQ=DAILY;BYSETPOS=-1", monday),
        )
    }

    /** An unparseable rule is "does not recur", never an error — the parser's contract. */
    @Test
    fun garbage_classifies_as_never_rather_than_throwing() {
        assertEquals(RecurrencePreset.NEVER, RecurrencePreset.classify("FREQ=FORTNIGHTLY", monday))
        assertEquals(RecurrencePreset.NEVER, RecurrencePreset.classify("nonsense", monday))
    }

    /**
     * ⚠ A weekly rule whose `BYDAY` matches the anchor is ours; one that names a
     * *different* weekday is not, even though both are "weekly" — the mismatch
     * means the rule was authored elsewhere and re-writing it would move the
     * event.
     */
    @Test
    fun weekly_matches_only_when_its_byday_agrees_with_the_anchor() {
        assertEquals(RecurrencePreset.WEEKLY, RecurrencePreset.classify("FREQ=WEEKLY;BYDAY=MO", monday))
        assertEquals(RecurrencePreset.CUSTOM, RecurrencePreset.classify("FREQ=WEEKLY;BYDAY=TU", monday))
    }

    /** ⚠ With no anchor to compare against, an explicit `BYDAY` cannot be vouched for. */
    @Test
    fun weekly_without_an_anchor_accepts_only_the_implicit_form() {
        assertEquals(RecurrencePreset.WEEKLY, RecurrencePreset.classify("FREQ=WEEKLY", null))
        assertEquals(RecurrencePreset.CUSTOM, RecurrencePreset.classify("FREQ=WEEKLY;BYDAY=MO", null))
    }

    /**
     * ⚠⚠ A moved occurrence classifies against the SERIES' anchor (its `recurrenceId`), so the
     * detail screen and the editor both say "Weekly" for a Monday stand-up moved to Tuesday.
     */
    @Test
    fun a_moved_occurrence_classifies_against_the_series_anchor() {
        val tuesday = LocalDateTime(LocalDate(2026, 8, 4), LocalTime(9, 30))
        val moved = ResolvedOccurrence(
            eventId = "standup", calendarId = "work", recurrenceId = "2026-08-03T09:30",
            title = "Stand-up", location = "", notes = "",
            startLocal = tuesday, endLocal = tuesday, zoneId = "", allDay = false,
            isOverridden = true, recurrenceRule = "FREQ=WEEKLY;BYDAY=MO",
        )

        assertEquals(RecurrencePreset.WEEKLY, RecurrencePreset.of(moved))
        assertEquals(RecurrencePreset.CUSTOM, RecurrencePreset.classify(moved.recurrenceRule, tuesday.date))
    }
}
