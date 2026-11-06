package org.tenshu.timeline.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure, device-free coverage of the **trip detector** (I5, `timeline-services.md` §5.2): away-from-home
 * moments cluster into a trip; local moments don't; a time gap splits two trips; too-few/too-short runs
 * are rejected; and the merge-time [TripDetector.membersWithinSpan] helper returns the away members
 * inside a given span (how a receiving device unions its own moments into an incoming trip). Home is a
 * parameter, so the detector stays a pure function — the property that lets it run inside a merger.
 */
class TripDetectorTest {

    private val day = 24 * 60 * 60 * 1000L
    private val hour = 60 * 60 * 1000L
    private val t0 = 1_800_000_000_000L

    // Home: Berlin. Away: Lisbon (~2300 km) and Rome (~1180 km). Near-home: ~13 km out.
    private val homeLat = 525_200_000L
    private val homeLng = 134_050_000L
    private val lisbonLat = 387_200_000L
    private val lisbonLng = -91_400_000L
    private val romeLat = 418_900_000L
    private val romeLng = 124_900_000L
    private val nearHomeLat = 526_000_000L
    private val nearHomeLng = 135_000_000L

    private fun moment(id: String, atMs: Long, latE7: Long, lngE7: Long) =
        TripMomentInput(id, atMs, latE7, lngE7)

    @Test
    fun awayMomentsOverSeveralDaysClusterIntoOneTrip() {
        val moments = listOf(
            moment("m1", t0, lisbonLat, lisbonLng),
            moment("m2", t0 + day, lisbonLat, lisbonLng),
            moment("m3", t0 + 2 * day, lisbonLat, lisbonLng),
            moment("m4", t0 + 3 * day, lisbonLat, lisbonLng),
        )
        val trips = TripDetector.detect(moments, homeLat, homeLng)

        assertEquals(1, trips.size)
        assertEquals(listOf("m1", "m2", "m3", "m4"), trips.single().memberMomentIds)
        assertEquals(t0, trips.single().startMs)
        assertEquals(t0 + 3 * day, trips.single().endMs)
    }

    @Test
    fun momentsNearHomeAreNotATrip() {
        val moments = listOf(
            moment("h1", t0, nearHomeLat, nearHomeLng),
            moment("h2", t0 + day, nearHomeLat, nearHomeLng),
            moment("h3", t0 + 2 * day, nearHomeLat, nearHomeLng),
        )
        assertEquals(emptyList(), TripDetector.detect(moments, homeLat, homeLng))
    }

    @Test
    fun aTimeGapSplitsIntoTwoTrips() {
        val moments = listOf(
            moment("l1", t0, lisbonLat, lisbonLng),
            moment("l2", t0 + day, lisbonLat, lisbonLng),
            // 40 h > 36 h gap → a new trip
            moment("r1", t0 + day + 40 * hour, romeLat, romeLng),
            moment("r2", t0 + 2 * day + 40 * hour, romeLat, romeLng),
        )
        val trips = TripDetector.detect(moments, homeLat, homeLng)

        assertEquals(2, trips.size)
        assertEquals(listOf("l1", "l2"), trips[0].memberMomentIds)
        assertEquals(listOf("r1", "r2"), trips[1].memberMomentIds)
    }

    @Test
    fun tooFewOrTooShortIsNotATrip() {
        // A single away moment.
        assertEquals(emptyList(), TripDetector.detect(listOf(moment("s1", t0, lisbonLat, lisbonLng)), homeLat, homeLng))
        // Two away moments only 6 h apart (< 12 h min duration).
        val brief = listOf(
            moment("b1", t0, lisbonLat, lisbonLng),
            moment("b2", t0 + 6 * hour, lisbonLat, lisbonLng),
        )
        assertEquals(emptyList(), TripDetector.detect(brief, homeLat, homeLng))
    }

    @Test
    fun membersWithinSpanReturnsAwayMomentsInsideTheSpan() {
        val moments = listOf(
            moment("l1", t0, lisbonLat, lisbonLng),
            moment("home", t0 + hour, nearHomeLat, nearHomeLng), // in span but not away → excluded
            moment("l2", t0 + 2 * day, lisbonLat, lisbonLng),
            moment("later", t0 + 10 * day, romeLat, romeLng), // away but outside the span → excluded
        )
        val members = TripDetector.membersWithinSpan(moments, startMs = t0, endMs = t0 + 3 * day, homeLat, homeLng)
        assertEquals(listOf("l1", "l2"), members)
    }

    @Test
    fun detectionIsIndependentOfInputOrder() {
        val ordered = listOf(
            moment("m1", t0, lisbonLat, lisbonLng),
            moment("m2", t0 + day, lisbonLat, lisbonLng),
            moment("m3", t0 + 2 * day, lisbonLat, lisbonLng),
        )
        val shuffled = listOf(ordered[2], ordered[0], ordered[1])
        assertTrue(TripDetector.detect(shuffled, homeLat, homeLng) == TripDetector.detect(ordered, homeLat, homeLng))
    }
}
