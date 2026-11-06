package app.tenshu.timeline.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the one structural invariant of the model: trips contain trips. Builds a
 * Lisbon⊃Sintra shape (as in the handoff) and walks it recursively — proving the
 * [TripEntry] hierarchy supports arbitrary nesting, not just the single level the mock
 * happens to use.
 */
class TimelineModelTest {

    @Test
    fun trip_contents_areRecursive_andWalkable() {
        val lisbon = buildLisbonWithNestedSintra()

        // The nested Sintra trip sits in a day slot.
        val nested = lisbon.contents.filterIsInstance<TripEntry.NestedTrip>()
        assertEquals(1, nested.size)
        assertEquals("Sintra", nested.single().trip.title)

        // Recursive count of moments across the whole (nested) tree.
        assertEquals(6, countMoments(lisbon))

        // Nesting depth is genuinely 2 (Lisbon → Sintra), reachable by recursion.
        assertEquals(2, maxDepth(lisbon))
        assertTrue(lisbon.contents.any { it is TripEntry.Collapsed })
    }

    private fun countMoments(trip: Trip): Int = trip.contents.sumOf { entry ->
        when (entry) {
            is TripEntry.DayEntry -> entry.moments.size
            is TripEntry.MomentItem -> 1
            is TripEntry.NestedTrip -> countMoments(entry.trip)
            is TripEntry.Collapsed -> 0
        }
    }

    private fun maxDepth(trip: Trip): Int {
        val nestedDepths = trip.contents.filterIsInstance<TripEntry.NestedTrip>()
            .map { maxDepth(it.trip) }
        return 1 + (nestedDepths.maxOrNull() ?: 0)
    }

    private fun buildLisbonWithNestedSintra(): Trip {
        val cover = Photo(dominantColor = "#d8a878")
        fun moment(id: String) = Moment(
            id = id, time = "10:00", title = id, place = id, kind = MomentKind.Visit,
        )
        val sintra = Trip(
            id = "sintra", title = "Sintra", dateRange = "Apr 14", daysSpan = 1,
            placesCount = 3, photosCount = 14, cover = cover,
            contents = listOf(
                TripEntry.MomentItem(moment("train")),
                TripEntry.MomentItem(moment("pena")),
                TripEntry.MomentItem(moment("lunch")),
            ),
        )
        return Trip(
            id = "lisbon", title = "Lisbon", dateRange = "Apr 12 – 17", daysSpan = 5,
            placesCount = 14, photosCount = 47, cover = cover,
            contents = listOf(
                TripEntry.DayEntry(1, "Day 1", "Sat · Apr 12", listOf(moment("arrival"), moment("dinner"))),
                TripEntry.DayEntry(2, "Day 2", "Sun · Apr 13", listOf(moment("belem"))),
                TripEntry.NestedTrip(3, "Day 3", "Mon · Apr 14", sintra),
                TripEntry.Collapsed(2, "2 more days · Apr 16 – 17"),
            ),
        )
    }
}
