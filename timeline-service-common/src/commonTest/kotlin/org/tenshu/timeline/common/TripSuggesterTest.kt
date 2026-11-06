package org.tenshu.timeline.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pure, device-free coverage of the **trip-suggestion engine** (I5c, `timeline-services.md` §5.2): it
 * derives home as the most-frequented location, runs [TripDetector] to find away-from-home journeys,
 * and shapes each into a titled [TripSuggestion] with a stable id. No home is stored anywhere, so the
 * derivation is the thing under test alongside the shaping.
 */
class TripSuggesterTest {

    private val day = 24 * 60 * 60 * 1000L
    private val t0 = 1_800_000_000_000L

    // Home: Berlin. Away: Lisbon (~2300 km).
    private val homeLat = 525_200_000L
    private val homeLng = 134_050_000L
    private val lisbonLat = 387_200_000L
    private val lisbonLng = -91_400_000L

    private fun m(id: String, atMs: Long, lat: Long, lng: Long, label: String = "") =
        TripSuggestionInput(id, atMs, lat, lng, label)

    @Test
    fun anAwayJourneyBecomesASuggestionTitledByItsPlace() {
        val moments = buildList {
            // Home life: five moments around Berlin (the most-frequented cell → home).
            repeat(5) { i -> add(m("h$i", t0 + i * day, homeLat, homeLng, "Home")) }
            // A Lisbon trip: three consecutive days away.
            add(m("l1", t0 + 10 * day, lisbonLat, lisbonLng, "Lisbon"))
            add(m("l2", t0 + 11 * day, lisbonLat, lisbonLng, "Lisbon"))
            add(m("l3", t0 + 12 * day, lisbonLat, lisbonLng, "Lisbon"))
        }

        val suggestions = TripSuggester.suggest(moments)

        assertEquals(1, suggestions.size)
        val trip = suggestions.single()
        assertEquals("Lisbon", trip.title)
        assertEquals(listOf("l1", "l2", "l3"), trip.memberMomentIds)
        assertEquals(t0 + 10 * day, trip.startMs)
        assertEquals(t0 + 12 * day, trip.endMs)
        // Stable, content-addressed id — accepting twice would fold to one Trip.
        assertEquals("suggested-trip:${t0 + 10 * day}", trip.suggestedTripId)
    }

    @Test
    fun onlyHomeMomentsYieldNoSuggestion() {
        val moments = (0..4).map { m("h$it", t0 + it * day, homeLat, homeLng, "Home") }
        assertEquals(emptyList(), TripSuggester.suggest(moments))
    }

    @Test
    fun titleFallsBackToTripWhenNoLabelKnown() {
        val moments = buildList {
            repeat(5) { i -> add(m("h$i", t0 + i * day, homeLat, homeLng)) } // blank labels
            add(m("a1", t0 + 10 * day, lisbonLat, lisbonLng))
            add(m("a2", t0 + 11 * day, lisbonLat, lisbonLng))
        }
        assertEquals("Trip", TripSuggester.suggest(moments).single().title)
    }

    @Test
    fun deriveHomePicksTheMostFrequentedLocation() {
        val moments = listOf(
            m("a", t0, homeLat, homeLng),
            m("b", t0 + day, homeLat + 200, homeLng + 200), // same home cell
            m("c", t0 + 2 * day, homeLat - 100, homeLng - 100), // same home cell
            m("d", t0 + 3 * day, lisbonLat, lisbonLng), // a lone away moment
        )
        val home = TripSuggester.deriveHome(moments, gridE7 = 10_000L)!!
        // Home is the Berlin cluster (3 moments), not Lisbon (1).
        assertTrue(home.first in (homeLat - 1_000)..(homeLat + 1_000))
    }

    @Test
    fun aStoredHomeBeatsTheMostFrequentedCellHeuristic() {
        // The case the heuristic gets wrong: someone who spends most of their recorded life in
        // Lisbon but whose actual home is Berlin. Most-frequented-cell says Lisbon, so the two
        // Berlin days would read as "a trip" — exactly backwards.
        val moments = buildList {
            repeat(5) { i -> add(m("l$i", t0 + i * day, lisbonLat, lisbonLng, "Lisbon")) }
            add(m("b1", t0 + 10 * day, homeLat, homeLng, "Berlin"))
            add(m("b2", t0 + 11 * day, homeLat, homeLng, "Berlin"))
            add(m("b3", t0 + 12 * day, homeLat, homeLng, "Berlin"))
        }

        // The heuristic picks Lisbon and calls the Berlin days a trip.
        assertEquals("Berlin", TripSuggester.suggest(moments).single().title)

        // With Berlin confirmed as home, the Berlin days stop being away — and the Lisbon block
        // becomes the trip instead. A stated home does not just refine the answer; it inverts it.
        val stored = TripSuggester.suggest(moments, storedHome = homeLat to homeLng)
        assertEquals("Lisbon", stored.single().title)
    }

    @Test
    fun anAbsentStoredHomeStillFallsBackToTheHeuristic() {
        val moments = (0..9).map { TripSuggestionInput("m$it", 1_000L + it, 480_000_000L, 110_000_000L) }

        assertEquals(
            TripSuggester.suggest(moments),
            TripSuggester.suggest(moments, storedHome = null),
        )
    }
}
