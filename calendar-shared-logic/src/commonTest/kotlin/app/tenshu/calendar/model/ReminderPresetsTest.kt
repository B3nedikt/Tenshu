package app.tenshu.calendar.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The offsets an editor offers, and how they read (CA-5e).
 *
 * ⚠⚠ The all-day labels are where the arithmetic is, and where it is easy to be
 * confidently wrong: a signed offset from midnight has to come back out as a
 * *time on a day*, and an off-by-one-day here would put "on the day at 9 a.m."
 * on the wrong morning.
 */
class ReminderPresetsTest {

    @Test
    fun a_timed_event_is_offered_offsets_before_it_starts() {
        assertTrue(ReminderPresets.forEvent(allDay = false).all { it >= 0 })
        assertTrue(0 in ReminderPresets.TIMED, "\"at start\" is a real choice")
    }

    /**
     * ⚠⚠ An all-day event starts at 00:00, so a *positive* offset lands the
     * night before. The useful ones are negative — later on the day itself.
     */
    @Test
    fun an_all_day_event_is_offered_a_different_list_including_negatives() {
        val allDay = ReminderPresets.forEvent(allDay = true)

        assertTrue(allDay.any { it < 0 }, "\"on the day at 9 a.m.\" is negative")
        assertTrue(allDay != ReminderPresets.TIMED)
    }

    // --- timed labels --------------------------------------------------------

    @Test
    fun timed_labels_use_the_largest_natural_unit() {
        assertEquals("At start", ReminderPresets.timedLabel(0))
        assertEquals("10 min before", ReminderPresets.timedLabel(10))
        assertEquals("1 hour before", ReminderPresets.timedLabel(60))
        assertEquals("2 hours before", ReminderPresets.timedLabel(120))
        assertEquals("1 day before", ReminderPresets.timedLabel(1440))
        assertEquals("2 days before", ReminderPresets.timedLabel(2880))
    }

    @Test
    fun every_offered_timed_offset_has_a_readable_label() {
        ReminderPresets.TIMED.forEach { minutes ->
            val label = ReminderPresets.timedLabel(minutes)
            assertTrue(label.isNotBlank(), "$minutes")
            assertTrue(!label.contains("null"), label)
        }
    }

    // --- all-day labels ------------------------------------------------------

    /** ⭐ The default: 540 minutes *after* midnight is 09:00 on the day itself. */
    @Test
    fun a_negative_offset_reads_as_a_time_on_the_day_itself() {
        assertEquals("On the day at 09:00", ReminderPresets.allDayLabel(-540))
        assertEquals("On the day at 18:00", ReminderPresets.allDayLabel(-1080))
    }

    /**
     * ⚠⚠ A positive offset counts *back* from midnight, so it lands on the
     * previous day — and the label has to say so, or "540 minutes before" reads
     * as the morning of the event when it is the afternoon before.
     */
    @Test
    fun a_positive_offset_reads_as_a_time_on_an_earlier_day() {
        assertEquals("Day before at 15:00", ReminderPresets.allDayLabel(540))
        assertEquals("Day before at 09:00", ReminderPresets.allDayLabel(900))
    }

    /** ⚠ Two days back is a different day count, not "day before" again. */
    @Test
    fun an_offset_more_than_a_day_out_names_the_right_number_of_days() {
        assertEquals("2 days before at 09:00", ReminderPresets.allDayLabel(2340))
    }

    /** ⚠ Exactly midnight on the day is the day itself, not the day before. */
    @Test
    fun a_zero_offset_on_an_all_day_event_is_midnight_that_day() {
        assertEquals("On the day at 00:00", ReminderPresets.allDayLabel(0))
    }

    @Test
    fun every_offered_all_day_offset_has_a_readable_label() {
        ReminderPresets.ALL_DAY.forEach { minutes ->
            val label = ReminderPresets.allDayLabel(minutes)
            assertTrue(label.isNotBlank(), "$minutes")
            assertTrue(Regex("""\d\d:\d\d""").containsMatchIn(label), "$minutes -> $label")
        }
    }

    /** ⚠ The dispatcher picks the labeller, so the same number reads two ways. */
    @Test
    fun the_same_offset_reads_differently_for_the_two_kinds_of_event() {
        assertEquals("At start", ReminderPresets.label(0, allDay = false))
        assertEquals("On the day at 00:00", ReminderPresets.label(0, allDay = true))
    }
}
