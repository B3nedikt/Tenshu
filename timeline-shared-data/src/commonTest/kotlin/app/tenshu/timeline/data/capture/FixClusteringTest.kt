package app.tenshu.timeline.data.capture

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure, device-free coverage of the **streaming stay-point operator** ([detectVisits], I3b): it
 * emits a visit on departure, ignores sub-threshold stops, flushes an open dwell when capture stops,
 * and — the load-bearing invariant — produces exactly the same visits as the batch [VisitDetector]
 * for the same time-ordered fixes. The Android sensor feeding it is device code (I3b-2); this stays
 * hardware-free.
 */
class FixClusteringTest {

    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private val cafeLat = 525_200_000L
    private val cafeLng = 134_050_000L

    @Test
    fun streamingMatchesBatchForOrderedFixes() = runTest {
        val homeLat = 525_300_000L
        val homeLng = 134_200_000L
        val fixes = listOf(
            CapturedFix(cafeLat, cafeLng, t0),
            CapturedFix(cafeLat + 500, cafeLng + 500, t0 + 3 * min),
            CapturedFix(cafeLat, cafeLng, t0 + 6 * min),
            CapturedFix(cafeLat + 60_000, cafeLng + 70_000, t0 + 15 * min), // travel
            CapturedFix(homeLat, homeLng, t0 + 30 * min),
            CapturedFix(homeLat + 500, homeLng + 500, t0 + 33 * min),
            CapturedFix(homeLat, homeLng, t0 + 36 * min),
        )

        val streamed = fixes.asFlow().detectVisits().toList()

        // Bit-for-bit identical to the batch detector — the two clusterings must never disagree.
        assertEquals(VisitDetector.detect(fixes), streamed)
        assertEquals(2, streamed.size)
    }

    @Test
    fun oneDwellEmitsOnDeparture() = runTest {
        val fixes = listOf(
            CapturedFix(cafeLat, cafeLng, t0),
            CapturedFix(cafeLat + 500, cafeLng + 500, t0 + 6 * min), // dwelled 6 min
            CapturedFix(cafeLat + 200_000, cafeLng + 200_000, t0 + 20 * min), // ~2 km away → left
        )

        val visits = fixes.asFlow().detectVisits().toList()

        assertEquals(1, visits.size)
        assertEquals(t0, visits.single().arrivalMs)
        assertEquals(t0 + 6 * min, visits.single().departureMs) // bounded by the last in-radius fix
    }

    @Test
    fun subThresholdStopEmitsNothing() = runTest {
        val fixes = listOf(
            CapturedFix(cafeLat, cafeLng, t0),
            CapturedFix(cafeLat + 500, cafeLng + 500, t0 + 2 * min), // 2 min < 5 min default
            CapturedFix(cafeLat + 200_000, cafeLng + 200_000, t0 + 10 * min),
        )
        assertEquals(emptyList(), fixes.asFlow().detectVisits().toList())
    }

    @Test
    fun openDwellFlushesWhenCaptureStops() = runTest {
        // No departure fix — the dwell is still open when the stream ends; the final flush emits it.
        val fixes = listOf(
            CapturedFix(cafeLat, cafeLng, t0),
            CapturedFix(cafeLat + 500, cafeLng + 500, t0 + 8 * min),
        )
        assertEquals(1, fixes.asFlow().detectVisits().toList().size)
    }
}
