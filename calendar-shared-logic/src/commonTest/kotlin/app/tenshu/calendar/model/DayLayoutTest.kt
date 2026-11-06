package app.tenshu.calendar.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The overlapping-event layout (`calendar-app.md` §5.2 (2)).
 *
 * ⭐ Every case here is arithmetic, which is exactly why the algorithm lives in
 * `-shared-logic` rather than inside a `@Composable`: a column assignment
 * checked by looking at a screenshot is not checked.
 */
class DayLayoutTest {

    /** `"09:00"` → minutes from midnight. Keeps the cases readable. */
    private fun at(text: String): Int {
        val (h, m) = text.split(':').map(String::toInt)
        return h * 60 + m
    }

    private data class Ev(val name: String, val from: String, val to: String)

    /** ⚠ Minimum 0 by default here: these cases are about overlap, not heights. */
    private fun layout(vararg events: Ev, minimumMinutes: Int = 0) =
        DayLayout.arrange(events.toList(), minimumMinutes) { at(it.from)..at(it.to) }
            .associateBy { it.value.name }

    private fun PositionedEvent<Ev>.place() = Triple(column, columnSpan, columnCount)

    // --- the basics ---------------------------------------------------------

    @Test
    fun an_empty_day_lays_out_to_nothing() {
        assertTrue(DayLayout.arrange(emptyList<Ev>()) { 0..0 }.isEmpty())
    }

    @Test
    fun a_lone_event_takes_the_whole_width() {
        val a = layout(Ev("a", "09:00", "10:00")).getValue("a")

        assertEquals(Triple(0, 1, 1), a.place())
        assertEquals(540, a.topMinute)
        assertEquals(600, a.bottomMinute)
        assertEquals(1f, a.widthFraction)
        assertEquals(0f, a.offsetFraction)
    }

    /**
     * ⚠⚠ Half-open intervals. Back-to-back meetings are the common case, and
     * treating "ends at 10:00" as conflicting with "starts at 10:00" would halve
     * the width of nearly every real working day.
     */
    @Test
    fun back_to_back_events_do_not_overlap() {
        val out = layout(Ev("a", "09:00", "10:00"), Ev("b", "10:00", "11:00"))

        assertEquals(Triple(0, 1, 1), out.getValue("a").place())
        assertEquals(Triple(0, 1, 1), out.getValue("b").place())
    }

    /**
     * ⚠⚠ **The cluster boundary, pinned.** Greedy column assignment hides a
     * clustering mistake: `standup` still lands in column 0 either way, so only
     * its **column count** gives the error away — and a cluster that swallowed a
     * back-to-back neighbour renders it at half width with empty space beside
     * it. Found by mutating `>=` to `>` and watching every other test stay green.
     */
    @Test
    fun an_event_ending_where_the_next_cluster_begins_keeps_the_full_width() {
        val out = layout(
            Ev("standup", "09:00", "10:00"),
            Ev("review", "10:00", "11:00"),
            Ev("clashes-with-review", "10:30", "11:30"),
        )

        assertEquals(Triple(0, 1, 1), out.getValue("standup").place(), "its own cluster")
        assertEquals(2, out.getValue("review").columnCount)
        assertEquals(2, out.getValue("clashes-with-review").columnCount)
    }

    @Test
    fun two_overlapping_events_split_the_width() {
        val out = layout(Ev("a", "09:00", "10:00"), Ev("b", "09:30", "10:30"))

        assertEquals(Triple(0, 1, 2), out.getValue("a").place())
        assertEquals(Triple(1, 1, 2), out.getValue("b").place())
        assertEquals(0.5f, out.getValue("b").widthFraction)
        assertEquals(0.5f, out.getValue("b").offsetFraction)
    }

    @Test
    fun three_mutually_overlapping_events_take_a_third_each() {
        val out = layout(
            Ev("a", "09:00", "12:00"),
            Ev("b", "09:30", "12:00"),
            Ev("c", "10:00", "12:00"),
        )

        assertEquals(listOf(0, 1, 2), listOf("a", "b", "c").map { out.getValue(it).column })
        assertTrue(listOf("a", "b", "c").all { out.getValue(it).columnCount == 3 })
    }

    /**
     * ⚠⚠ A quiet morning must not be squeezed by a busy afternoon. Clustering is
     * what keeps the column count local — without it, one three-way conflict at
     * 15:00 would shrink the 09:00 standup to a third of the screen.
     */
    @Test
    fun separate_clusters_get_their_own_column_counts() {
        val out = layout(
            Ev("morning", "09:00", "10:00"),
            Ev("busy1", "15:00", "16:00"),
            Ev("busy2", "15:00", "16:00"),
            Ev("busy3", "15:00", "16:00"),
        )

        assertEquals(1, out.getValue("morning").columnCount)
        assertEquals(3, out.getValue("busy1").columnCount)
    }

    /**
     * ⭐ A transitive chain is one cluster even though its ends do not touch:
     * A overlaps B, B overlaps C, A and C do not. C reuses A's freed column.
     */
    @Test
    fun a_transitive_chain_is_one_cluster_and_reuses_freed_columns() {
        val out = layout(
            Ev("a", "09:00", "10:00"),
            Ev("b", "09:30", "10:30"),
            Ev("c", "10:15", "11:00"),
        )

        assertEquals(0, out.getValue("a").column)
        assertEquals(1, out.getValue("b").column)
        assertEquals(0, out.getValue("c").column, "column 0 is free again by 10:15")
        assertTrue(listOf("a", "b", "c").all { out.getValue(it).columnCount == 2 })
    }

    @Test
    fun an_event_fully_containing_another_still_splits() {
        val out = layout(Ev("all-morning", "09:00", "13:00"), Ev("standup", "10:00", "10:15"))

        assertEquals(0, out.getValue("all-morning").column)
        assertEquals(1, out.getValue("standup").column)
    }

    // --- expansion ----------------------------------------------------------

    /**
     * ⭐⭐ **The expansion pass.** Without it, `late` would be drawn at a third
     * of the width with nothing beside it — the cluster's column count is a
     * property of the *cluster*, and an event should only be as narrow as its
     * actual neighbours make it.
     */
    @Test
    fun an_event_widens_over_columns_nothing_else_is_using() {
        val out = layout(
            Ev("long", "09:00", "12:00"),   // column 0
            Ev("a", "09:00", "10:00"),      // column 1
            Ev("b", "09:00", "09:30"),      // column 2
            Ev("late", "11:00", "12:00"),   // column 1, and column 2 is free by then
        )

        assertEquals(3, out.getValue("late").columnCount)
        assertEquals(1, out.getValue("late").column)
        assertEquals(2, out.getValue("late").columnSpan, "nothing occupies column 2 at 11:00")

        assertEquals(1, out.getValue("long").columnSpan, "column 1 is busy from 09:00")
    }

    /** ⚠ Expansion never runs past the cluster's last column. */
    @Test
    fun expansion_stops_at_the_last_column() {
        val out = layout(Ev("a", "09:00", "10:00"), Ev("b", "09:00", "10:00"))

        assertTrue(out.values.all { it.column + it.columnSpan <= it.columnCount })
    }

    // --- degenerate input ---------------------------------------------------

    /**
     * ⚠⚠ A zero-length event has an *empty* half-open interval: it would overlap
     * nothing, stack invisibly under its neighbour, and be untappable. The
     * minimum height is what makes it both visible and part of the overlap
     * question.
     */
    @Test
    fun a_zero_length_event_is_given_the_minimum_height_and_still_conflicts() {
        val out = DayLayout.arrange(
            listOf(Ev("instant", "09:00", "09:00"), Ev("meeting", "09:05", "10:00")),
            minimumMinutes = 20,
        ) { at(it.from)..at(it.to) }.associateBy { it.value.name }

        assertEquals(at("09:20"), out.getValue("instant").bottomMinute)
        assertEquals(2, out.getValue("instant").columnCount, "20 minutes reaches past 09:05")
    }

    /** ⚠ The grid genuinely runs out of room at midnight; the block is clamped, not overflowed. */
    @Test
    fun an_event_at_the_very_end_of_the_day_is_clamped_to_midnight() {
        val out = DayLayout.arrange(listOf(Ev("last", "23:55", "23:59")), minimumMinutes = 30) {
            at(it.from)..at(it.to)
        }.single()

        assertEquals(MINUTES_PER_DAY, out.bottomMinute)
        assertTrue(out.topMinute < out.bottomMinute)
    }

    @Test
    fun an_inverted_interval_does_not_produce_a_negative_block() {
        val out = DayLayout.arrange(listOf(Ev("broken", "14:00", "09:00")), minimumMinutes = 0) {
            at(it.from)..at(it.to)
        }.single()

        assertTrue(out.bottomMinute >= out.topMinute, "got ${out.topMinute}..${out.bottomMinute}")
    }

    // --- contract -----------------------------------------------------------

    /** ⚠ Results come back in input order, so a caller can zip them against its own list. */
    @Test
    fun results_are_returned_in_input_order() {
        val input = listOf(
            Ev("third", "11:00", "12:00"),
            Ev("first", "09:00", "10:00"),
            Ev("second", "09:30", "10:30"),
        )

        val out = DayLayout.arrange(input) { at(it.from)..at(it.to) }

        assertEquals(input.map { it.name }, out.map { it.value.name })
    }

    /**
     * ⚠⚠ Determinism, and it is not cosmetic: two identical intervals that
     * swapped columns between runs would make the grid flicker on every
     * recomposition, since the renderer re-lays-out whatever it is handed.
     */
    @Test
    fun identical_intervals_are_placed_the_same_way_every_time() {
        val input = listOf(
            Ev("a", "09:00", "10:00"),
            Ev("b", "09:00", "10:00"),
            Ev("c", "09:00", "10:00"),
        )

        val first = DayLayout.arrange(input) { at(it.from)..at(it.to) }
        val second = DayLayout.arrange(input) { at(it.from)..at(it.to) }

        assertEquals(first, second)
        assertEquals(listOf(0, 1, 2), first.map { it.column }, "input order breaks the tie")
    }

    /** ⚠ The invariant a renderer relies on: nothing is drawn outside the day. */
    @Test
    fun every_block_stays_inside_the_day() {
        val out = DayLayout.arrange(
            listOf(
                Ev("early", "00:00", "00:30"),
                Ev("late", "23:30", "24:00"),
                Ev("all", "00:00", "24:00"),
            )
        ) { at(it.from)..at(it.to) }

        assertTrue(out.all { it.topMinute in 0..MINUTES_PER_DAY })
        assertTrue(out.all { it.bottomMinute in 0..MINUTES_PER_DAY })
    }

    /**
     * ⚠⚠ The property that actually matters, checked over a whole synthetic day:
     * **two overlapping events must never be drawn on top of each other.** Every
     * assertion above is a special case of this one.
     */
    @Test
    fun no_two_overlapping_events_are_ever_assigned_the_same_horizontal_space() {
        // A deliberately awkward day: nesting, chains, exact touches, duplicates.
        val input = listOf(
            Ev("a", "08:00", "18:00"),
            Ev("b", "09:00", "09:30"),
            Ev("c", "09:00", "09:30"),
            Ev("d", "09:30", "11:00"),
            Ev("e", "10:00", "10:15"),
            Ev("f", "10:15", "12:00"),
            Ev("g", "13:00", "14:00"),
            Ev("h", "13:30", "13:45"),
            Ev("i", "17:00", "19:00"),
            Ev("j", "18:00", "19:00"),
        )

        val out = DayLayout.arrange(input) { at(it.from)..at(it.to) }

        out.forEachIndexed { i, first ->
            out.drop(i + 1).forEach { second ->
                val overlapInTime = first.topMinute < second.bottomMinute &&
                    second.topMinute < first.bottomMinute
                if (!overlapInTime) return@forEach

                // Fractions rather than raw columns: two events in the same
                // cluster share a column count, and comparing spans is what
                // catches an over-eager expansion.
                val firstEnd = first.column + first.columnSpan
                val secondEnd = second.column + second.columnSpan
                val overlapInSpace = first.column < secondEnd && second.column < firstEnd

                assertTrue(
                    !overlapInSpace,
                    "${first.value.name} and ${second.value.name} overlap in time " +
                        "AND in space (cols ${first.column}+${first.columnSpan} vs " +
                        "${second.column}+${second.columnSpan})",
                )
            }
        }
    }
}
