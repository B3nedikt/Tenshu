package app.tenshu.calendar.data.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/** What a windowed read may conclude from absence — and the margin that keeps it conservative. */
class SnapshotWindowTest {

    private fun utc(y: Int, m: Int, d: Int) = LocalDate(y, m, d).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    /** July 2026, as an instant window. */
    private val july = SnapshotWindow(utc(2026, 7, 1), utc(2026, 8, 1))

    /** ⚠ A day of margin each side: the edges count as outside, so the error is to keep. */
    @Test
    fun the_edge_days_count_as_outside() {
        assertFalse(july.covers(LocalDate(2026, 7, 1)))
        assertTrue(july.covers(LocalDate(2026, 7, 2)))
        assertTrue(july.covers(LocalDate(2026, 7, 30)))
        assertFalse(july.covers(LocalDate(2026, 7, 31)))
        assertTrue(july.coversLocal("2026-07-15T09:00"))
        assertFalse(july.coversLocal("not a date"))
    }

    @Test
    fun a_series_touches_the_window_only_with_an_occurrence_inside_it() {
        assertTrue(july.touchesSeries("2026-07-10T09:00", "2026-07-10T10:00", "", ""))
        assertFalse(july.touchesSeries("2026-06-10T09:00", "2026-06-10T10:00", "", ""), "aged out")
        assertTrue(july.touchesSeries("2026-06-03T09:00", "", "FREQ=WEEKLY;BYDAY=WE", ""), "recurs into it")
        assertFalse(july.touchesSeries("2026-06-03T09:00", "", "FREQ=WEEKLY;BYDAY=WE;COUNT=3", ""), "ended before it")
        assertTrue(july.touchesSeries("2026-06-20T09:00", "2026-07-10T10:00", "", ""), "spans into it")
        assertFalse(
            july.touchesSeries("2026-07-08T09:00", "", "FREQ=WEEKLY;COUNT=2", "", setOf("2026-07-08T09:00", "2026-07-15T09:00")),
            "every in-window occurrence moved out",
        )
    }

    @Test
    fun a_series_reaches_beyond_the_window_when_it_recurs_outside_it() {
        assertTrue(july.reachesBeyond("2026-07-08T09:00", "FREQ=WEEKLY", ""), "unbounded")
        assertTrue(july.reachesBeyond("2026-06-03T09:00", "FREQ=WEEKLY;COUNT=8", ""), "starts before")
        assertFalse(july.reachesBeyond("2026-07-08T09:00", "FREQ=WEEKLY;COUNT=2", ""), "all inside")
        assertTrue(july.reachesBeyond("2026-07-08T09:00", "garbage", ""), "unknown is kept")
    }

    @Test
    fun covered_slots_are_the_certain_ones_minus_cancellations_and_moves() {
        assertEquals(
            listOf("2026-07-08", "2026-07-22"),
            july.coveredSlots("2026-06-03T09:00", "FREQ=WEEKLY;BYDAY=WE", "2026-07-15", setOf("2026-07-29T09:00")),
        )
    }

    /** ⚠ Unknown is not deleted. */
    @Test
    fun an_unreadable_series_never_touches_the_window() {
        assertFalse(july.touchesSeries("garbage", "", "", ""))
        assertFalse(july.touchesSeries("2026-07-10T09:00", "", "NOT A RULE", ""))
    }
}
