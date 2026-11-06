package app.tenshu.calendar.model

import app.tenshu.calendar.data.CalendarSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/**
 * The month grid's per-day markers (CA-8b).
 *
 * ⚠ Its only consumer is a `UICalendarView` delegate in Swift, which nothing here compiles — so
 * this file is the whole of the month view that can be checked.
 */
class CalendarMonthDotsTest {

    private val work = CalendarSummary("work", "Work", 0xFF2196F3.toInt(), sensitive = false, visible = true)
    private val home = CalendarSummary("home", "Home", 0xFF4CAF50.toInt(), sensitive = false, visible = true)

    private fun occurrence(
        title: String,
        calendarId: String = "work",
        date: LocalDate = LocalDate(2026, 8, 9),
        hour: Int = 9,
    ) = ResolvedOccurrence(
        eventId = title,
        calendarId = calendarId,
        recurrenceId = "$date/$title",
        title = title,
        location = "",
        notes = "",
        startLocal = LocalDateTime(date, LocalTime(hour, 0)),
        endLocal = LocalDateTime(date, LocalTime(hour + 1, 0)),
        zoneId = "Europe/Berlin",
        allDay = false,
        isOverridden = false,
        recurrenceRule = "",
    )

    @Test
    fun a_day_with_events_gets_one_dot_carrying_its_count() {
        val dots = CalendarMonthDots.of(
            listOf(occurrence("A"), occurrence("B", hour = 14)),
            listOf(work),
        )

        val dot = dots.single()
        assertEquals(2, dot.count)
        assertEquals(work.colorArgb, dot.colorArgb)
        assertEquals(2026, dot.year)
        assertEquals(8, dot.month)
        assertEquals(9, dot.day)
    }

    @Test
    fun a_day_with_nothing_on_it_gets_no_dot() {
        val dots = CalendarMonthDots.of(
            listOf(occurrence("A"), occurrence("C", date = LocalDate(2026, 8, 12))),
            listOf(work),
        )

        assertEquals(2, dots.size, "the two empty days between them must not appear")
    }

    @Test
    fun nothing_in_the_month_means_no_dots() {
        assertEquals(emptyList(), CalendarMonthDots.of(emptyList(), listOf(work)))
    }

    /**
     * ⚠⚠ A day mixing calendars shows only the first event's colour — the documented cost of
     * `UICalendarView` giving a cell exactly one decoration. The test pins *which* one it is, so
     * the loss stays deterministic rather than becoming order-dependent.
     */
    @Test
    fun a_mixed_day_takes_the_colour_of_its_earliest_event() {
        // ⚠⚠ The titles are chosen so alphabetical order DISAGREES with time order: "Afternoon"
        // sorts first but happens later. A fixture where the two agree passes even when the
        // comparator ignores the time entirely — which is exactly what mutation testing found
        // here the first time.
        val dots = CalendarMonthDots.of(
            listOf(occurrence("Afternoon", calendarId = "home", hour = 17), occurrence("Zebra", hour = 8)),
            listOf(work, home),
        )

        assertEquals(work.colorArgb, dots.single().colorArgb, "the 08:00 event's calendar, not the alphabetical first")
        assertEquals(2, dots.single().count, "and the count is what says there is more than one")
    }

    /** ⚠ Same data, either input order, same colour — the dot must not flicker between loads. */
    @Test
    fun the_colour_does_not_depend_on_the_input_order() {
        val rows = listOf(occurrence("Beta", calendarId = "home"), occurrence("Alpha"))

        assertEquals(
            CalendarMonthDots.of(rows, listOf(work, home)).single().colorArgb,
            CalendarMonthDots.of(rows.reversed(), listOf(work, home)).single().colorArgb,
        )
    }

    /**
     * ⚠ An occurrence whose calendar is not in the list — a hidden calendar arriving mid-refresh,
     * or a row racing a delete. A dot in a neutral grey is right; a crash or an invisible day is
     * not.
     */
    @Test
    fun an_unknown_calendar_falls_back_to_neutral_rather_than_vanishing() {
        val dots = CalendarMonthDots.of(listOf(occurrence("Orphan", calendarId = "gone")), listOf(work))

        assertEquals(CalendarMonthDots.NEUTRAL, dots.single().colorArgb)
        assertEquals(1, dots.single().count)
    }

    /**
     * ⚠ The key the UIKit delegate looks a `DateComponents` up by. It has to be a total order on
     * days, and — the part worth a test — it must not collide across month boundaries.
     */
    @Test
    fun the_lookup_key_is_unique_per_day_and_ordered() {
        val dots = CalendarMonthDots.of(
            listOf(
                occurrence("Sep", date = LocalDate(2026, 9, 1)),
                occurrence("Aug", date = LocalDate(2026, 8, 31)),
                occurrence("NextYear", date = LocalDate(2027, 1, 1)),
            ),
            listOf(work),
        )

        assertEquals(listOf(20260831, 20260901, 20270101), dots.map { it.key })
        assertTrue(dots.map { it.key }.distinct().size == dots.size)
    }

    // --- colour channels -----------------------------------------------------

    /**
     * ⚠⚠ ARGB in, four channels out. The renderer is UIKit, which wants the alpha last — doing the
     * shift in Swift would put untestable bit arithmetic in a file nothing here compiles, and a
     * swapped red and blue is invisible until someone looks at a screen.
     */
    @Test
    fun the_colour_channels_are_unpacked_from_argb() {
        val dot = CalendarMonthDots.of(listOf(occurrence("A")), listOf(work)).single()

        assertEquals(0xFF, dot.alpha)
        assertEquals(0x21, dot.red)
        assertEquals(0x96, dot.green)
        assertEquals(0xF3, dot.blue)
    }

    /** ⚠ A channel of 0 must come back as 0, not as "absent" — the sign bit is the trap here. */
    @Test
    fun a_black_unpacks_to_zero_channels() {
        val dot = CalendarDayDot(2026, 8, 9, count = 1, colorArgb = 0)

        assertEquals(listOf(0, 0, 0), listOf(dot.red, dot.green, dot.blue))
    }

    /**
     * ⚠⚠ The stored alpha byte is ignored, as Compose ignores it: rows seeded as `0x4285F4` (alpha
     * 0) exist in realms, and honouring the byte drew them invisible on iOS only.
     */
    @Test
    fun a_stored_alpha_of_zero_still_draws_opaque() {
        val dot = CalendarDayDot(2026, 8, 9, count = 1, colorArgb = 0x4285F4)

        assertEquals(listOf(0xFF, 0x42, 0x85, 0xF4), listOf(dot.alpha, dot.red, dot.green, dot.blue))
    }

    // --- the redraw diff -----------------------------------------------------

    /**
     * ⚠⚠ **Removals count as much as additions.** A day whose last event was deleted keeps its dot
     * until something asks the grid to redraw it, and "redraw what changed" is easy to write as
     * "redraw what is now there".
     */
    @Test
    fun a_day_that_lost_its_last_event_is_redrawn() {
        val before = CalendarMonthDots.of(listOf(occurrence("A")), listOf(work))

        assertEquals(listOf(20260809), CalendarMonthDots.changedKeys(before, emptyList()))
    }

    @Test
    fun a_new_day_is_redrawn() {
        val after = CalendarMonthDots.of(listOf(occurrence("A")), listOf(work))

        assertEquals(listOf(20260809), CalendarMonthDots.changedKeys(emptyList(), after))
    }

    @Test
    fun a_day_whose_count_changed_is_redrawn() {
        val before = CalendarMonthDots.of(listOf(occurrence("A")), listOf(work))
        val after = CalendarMonthDots.of(listOf(occurrence("A"), occurrence("B", hour = 14)), listOf(work))

        assertEquals(listOf(20260809), CalendarMonthDots.changedKeys(before, after))
    }

    @Test
    fun a_day_whose_colour_changed_is_redrawn() {
        val before = CalendarMonthDots.of(listOf(occurrence("A")), listOf(work))
        val after = CalendarMonthDots.of(listOf(occurrence("A", calendarId = "home")), listOf(work, home))

        assertEquals(listOf(20260809), CalendarMonthDots.changedKeys(before, after))
    }

    /**
     * ⚠ The common case, and the reason the diff exists: a reload that changed nothing must ask
     * the grid to redraw nothing. Passing the whole visible range on every state emission is what
     * makes a scroll stutter.
     */
    @Test
    fun an_unchanged_day_is_not_redrawn() {
        val dots = CalendarMonthDots.of(
            listOf(occurrence("A"), occurrence("C", date = LocalDate(2026, 8, 12))),
            listOf(work),
        )

        assertEquals(emptyList(), CalendarMonthDots.changedKeys(dots, dots))
    }

    /**
     * ⚠ The list is sorted, not merely deduplicated. Nothing downstream depends on the order —
     * `reloadDecorations` takes a set's worth of dates — but an unordered answer makes every test
     * of this function depend on hash/insertion order, which is how a diff test starts passing for
     * the wrong reason. The fixture is built so insertion order and sorted order differ.
     */
    @Test
    fun the_redraw_list_comes_back_in_day_order() {
        val before = CalendarMonthDots.of(listOf(occurrence("Late", date = LocalDate(2026, 8, 20))), listOf(work))
        val after = CalendarMonthDots.of(listOf(occurrence("Early", date = LocalDate(2026, 8, 12))), listOf(work))

        assertEquals(listOf(20260812, 20260820), CalendarMonthDots.changedKeys(before, after))
    }

    @Test
    fun only_the_days_that_moved_are_redrawn() {
        val before = CalendarMonthDots.of(
            listOf(occurrence("A"), occurrence("C", date = LocalDate(2026, 8, 12))),
            listOf(work),
        )
        val after = CalendarMonthDots.of(
            listOf(occurrence("A"), occurrence("D", date = LocalDate(2026, 8, 20))),
            listOf(work),
        )

        assertEquals(listOf(20260812, 20260820), CalendarMonthDots.changedKeys(before, after))
    }

    // --- the selected-day key ------------------------------------------------

    /** ⚠ Same packing as [CalendarDayDot.key] — the grid looks the selection up the same way. */
    @Test
    fun a_day_packs_to_the_same_key_the_dots_use() {
        assertEquals(20260809, CalendarMonthDots.keyOf(LocalDate(2026, 8, 9)))
        assertEquals(
            CalendarMonthDots.of(listOf(occurrence("A")), listOf(work)).single().key,
            CalendarMonthDots.keyOf(LocalDate(2026, 8, 9)),
        )
    }

    /**
     * ⚠ No selection is a real state — the grid must clear rather than land on some day. `0` is
     * safe as the sentinel because year 0 does not exist in the ISO proleptic calendar.
     */
    @Test
    fun no_selected_day_is_the_no_day_sentinel() {
        assertEquals(CalendarMonthDots.NO_DAY, CalendarMonthDots.keyOf(null))
        assertTrue(CalendarMonthDots.of(listOf(occurrence("A")), listOf(work)).none { it.key == CalendarMonthDots.NO_DAY })
    }
}
