package org.tenshu.calendar.common.recurrence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/**
 * The window query's brain (CA-1b): expansion plus override application.
 *
 * ⭐ Tested here, in `-common`, rather than through the repository — this is the
 * part with the interesting logic, and CA-5's proxy-side reminder scheduler will
 * consume the same function. A second implementation behind the repository would
 * be the one that drifted.
 */
class OccurrenceResolverTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate(y, m, day)

    private fun series(
        eventId: String = "standup",
        title: String = "Standup",
        location: String = "Room A",
        notes: String = "",
        start: String = "2026-08-03T09:00",
        end: String = "2026-08-03T09:30",
        rule: String = "FREQ=WEEKLY;BYDAY=MO",
        exceptions: String = "",
        allDay: Boolean = false,
        zone: String = "Europe/Berlin",
    ) = SeriesInput(
        eventId = eventId,
        calendarId = "work",
        title = title,
        location = location,
        notes = notes,
        startLocal = start,
        endLocal = end,
        timeZoneId = zone,
        allDay = allDay,
        recurrenceRule = rule,
        exceptionDates = exceptions,
    )

    private fun resolve(
        series: List<SeriesInput>,
        overrides: List<OverrideInput> = emptyList(),
        from: LocalDate = d(2026, 8, 1),
        to: LocalDate = d(2026, 8, 31),
    ) = OccurrenceResolver.resolve(series, overrides, from, to)

    @Test
    fun expands_a_series_across_the_window() {
        val out = resolve(listOf(series()))
        assertEquals(
            listOf("2026-08-03T09:00", "2026-08-10T09:00", "2026-08-17T09:00", "2026-08-24T09:00", "2026-08-31T09:00"),
            out.map { it.recurrenceId },
        )
        assertTrue(out.all { it.isRecurring })
        assertTrue(out.none { it.isOverridden })
    }

    @Test
    fun a_one_off_event_yields_a_single_occurrence() {
        val out = resolve(listOf(series(rule = "", start = "2026-08-05T13:00", end = "2026-08-05T14:00")))
        assertEquals(1, out.size)
        assertFalse(out.single().isRecurring)
    }

    @Test
    fun exception_dates_remove_occurrences() {
        val out = resolve(listOf(series(exceptions = "2026-08-10,2026-08-24")))
        assertEquals(
            listOf("2026-08-03T09:00", "2026-08-17T09:00", "2026-08-31T09:00"),
            out.map { it.recurrenceId },
        )
    }

    // --- overrides ----------------------------------------------------------

    /**
     * ⚠ The moved occurrence keeps its **original** `recurrenceId` — that is the
     * key an override is filed under, and the handle for moving it again.
     */
    @Test
    fun an_override_moves_an_occurrence_but_not_its_identity() {
        val out = resolve(
            listOf(series()),
            listOf(
                OverrideInput(
                    eventId = "standup",
                    recurrenceId = "2026-08-10T09:00",
                    startLocal = "2026-08-10T11:00",
                    endLocal = null, title = null, location = null, notes = null,
                )
            ),
        )
        val moved = out.single { it.recurrenceId == "2026-08-10T09:00" }
        assertEquals("2026-08-10T11:00", moved.startLocal.toString())
        assertTrue(moved.isOverridden)
    }

    /** ⭐ A moved occurrence with no explicit end keeps the series' length. */
    @Test
    fun a_moved_occurrence_keeps_the_series_duration() {
        val out = resolve(
            listOf(series(start = "2026-08-03T09:00", end = "2026-08-03T09:30")),
            listOf(
                OverrideInput("standup", "2026-08-10T09:00", "2026-08-10T11:00", null, null, null, null)
            ),
        )
        val moved = out.single { it.recurrenceId == "2026-08-10T09:00" }
        assertEquals("2026-08-10T11:30", moved.endLocal.toString(), "the 30-minute length must follow")
    }

    /**
     * ⚠⚠ `null` inherits, `""` overrides with empty — the distinction the
     * schema's per-field optionality exists to carry, checked at the point it
     * would be easiest to lose.
     */
    @Test
    fun null_inherits_from_the_series_while_empty_string_overrides_it() {
        val out = resolve(
            listOf(series(title = "Standup", location = "Room A")),
            listOf(
                OverrideInput(
                    eventId = "standup",
                    recurrenceId = "2026-08-10T09:00",
                    startLocal = null, endLocal = null,
                    title = null,        // inherit
                    location = "",       // override with empty
                    notes = null,
                )
            ),
        )
        val overridden = out.single { it.recurrenceId == "2026-08-10T09:00" }
        assertEquals("Standup", overridden.title, "null must inherit the series' title")
        assertEquals("", overridden.location, "\"\" must override, not fall through")

        val untouched = out.single { it.recurrenceId == "2026-08-03T09:00" }
        assertEquals("Room A", untouched.location, "other occurrences are unaffected")
    }

    /** An override for an occurrence outside the window changes nothing in it. */
    @Test
    fun an_override_outside_the_window_is_inert() {
        val out = resolve(
            listOf(series()),
            listOf(OverrideInput("standup", "2026-09-07T09:00", "2026-09-07T11:00", null, null, null, null)),
        )
        assertTrue(out.none { it.isOverridden })
    }

    /**
     * ⚠ An occurrence moved *out* of the window is not reported. The window is
     * re-checked after the override is applied, so a day view does not show an
     * event that has been moved to next week.
     */
    @Test
    fun an_occurrence_moved_out_of_the_window_is_dropped() {
        val out = resolve(
            listOf(series()),
            listOf(OverrideInput("standup", "2026-08-10T09:00", "2026-09-15T09:00", null, null, null, null)),
            from = d(2026, 8, 10),
            to = d(2026, 8, 10),
        )
        assertTrue(out.isEmpty(), "the occurrence moved to September must not appear in an August day")
    }

    // --- ordering + robustness ----------------------------------------------

    /** ⚠ Sorted by **effective** start, so a moved occurrence sorts where it now is. */
    @Test
    fun results_are_sorted_by_effective_start() {
        val out = resolve(
            listOf(
                series(eventId = "a", title = "Late", start = "2026-08-05T16:00", end = "2026-08-05T17:00", rule = ""),
                series(eventId = "b", title = "Early", start = "2026-08-05T08:00", end = "2026-08-05T09:00", rule = ""),
            ),
            from = d(2026, 8, 5),
            to = d(2026, 8, 5),
        )
        assertEquals(listOf("Early", "Late"), out.map { it.title })
    }

    /**
     * ⚠ An unreadable row is skipped, never fatal: one corrupt event must not
     * blank the whole month.
     */
    @Test
    fun an_unparseable_row_is_skipped_rather_than_failing_the_window() {
        val out = resolve(
            listOf(
                series(eventId = "bad", start = "not-a-date", rule = ""),
                series(eventId = "good", title = "Fine", start = "2026-08-05T10:00", end = "2026-08-05T11:00", rule = ""),
            ),
        )
        assertEquals(listOf("Fine"), out.map { it.title })
    }

    @Test
    fun an_inverted_window_yields_nothing() {
        assertTrue(resolve(listOf(series()), from = d(2026, 8, 31), to = d(2026, 8, 1)).isEmpty())
    }

    /** All-day events carry their flag through, so a renderer can treat them as dates. */
    @Test
    fun all_day_events_keep_their_flag() {
        val out = resolve(
            listOf(series(start = "2026-08-15", end = "2026-08-16", rule = "", allDay = true)),
            from = d(2026, 8, 15),
            to = d(2026, 8, 15),
        )
        assertTrue(out.single().allDay)
    }
}
