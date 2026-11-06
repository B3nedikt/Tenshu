package app.tenshu.timeline.data.capture

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure, device-free coverage of the **movement segmenter** (M1,
 * `docs/proposals/timeline-movement-capture.md` §3): it flushes the transit leading up to a dwell,
 * keeps a continuous walk whole across a sub-threshold pause, drops jitter below the thresholds,
 * force-closes an over-long open move, records a pure walk on capture stop (the trigger report), and
 * — the load-bearing invariant — emits the *same dwells* as the batch [VisitDetector] for the same
 * ordered fixes. The Android sensor feeding it is device code (M3); this stays hardware-free.
 */
class MovementSegmenterTest {

    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private val originLat = 525_000_000L
    private val originLng = 134_000_000L

    /** A straight run of fixes, one per [dMs], stepping [dLatE7] north each time. */
    private fun line(count: Int, startLat: Long, startMs: Long, dLatE7: Long, dMs: Long) =
        (0 until count).map { CapturedFix(startLat + it * dLatE7, originLng, startMs + it * dMs) }

    private fun moves(events: List<CaptureEvent>) = events.filterIsInstance<CaptureEvent.Move>().map { it.move }
    private fun dwells(events: List<CaptureEvent>) = events.filterIsInstance<CaptureEvent.Dwell>().map { it.visit }

    @Test
    fun walkThenDwellFlushesMoveThenEmitsVisit() = runTest {
        // ~134 m/min → each fix leaves the 100 m radius (clean singletons) and reads as a walk.
        val walk = line(count = 15, startLat = originLat, startMs = t0, dLatE7 = 12_000, dMs = min)
        val placeLat = originLat + 14 * 12_000 + 13_000
        val dwell = listOf(
            CapturedFix(placeLat, originLng, t0 + 15 * min),
            CapturedFix(placeLat + 2_000, originLng + 2_000, t0 + 17 * min),
            CapturedFix(placeLat + 1_000, originLng, t0 + 19 * min),
            CapturedFix(placeLat, originLng + 1_000, t0 + 21 * min), // dwelled 6 min ≥ 5
        )
        val depart = CapturedFix(placeLat + 300_000, originLng, t0 + 30 * min)
        val fixes = walk + dwell + depart

        val events = fixes.asFlow().segment().toList()

        // One move (the walk), flushed before the dwell it led into.
        assertEquals(1, moves(events).size)
        assertTrue(events.indexOfFirst { it is CaptureEvent.Move } <
            events.indexOfFirst { it is CaptureEvent.Dwell })

        // The dwell emits twice: once on confirmation (`ongoing`, while still there) and once on
        // departure (final). Both are the same content-addressed visit → fold to one place.
        val dwellEvents = events.filterIsInstance<CaptureEvent.Dwell>()
        assertEquals(2, dwellEvents.size)
        assertTrue(dwellEvents.first().ongoing)  // confirmation first
        assertFalse(dwellEvents.last().ongoing)  // departure last
        assertEquals(dwellEvents.first().visit.arrivalMs, dwellEvents.last().visit.arrivalMs)
        // The final (departure) visit matches the batch detector's single dwell.
        assertEquals(VisitDetector.detect(fixes), listOf(dwellEvents.last().visit))

        val move = moves(events).single()
        assertEquals(MovementMode.WALK, move.mode)
        assertEquals(t0, move.startMs)
        assertTrue(move.distanceM > 250.0)
    }

    @Test
    fun stationaryDwellEmitsOnConfirmationBeforeAnyDeparture() = runTest {
        // The core on-device bug: sitting at one spot, fixes never leave the radius, so the detector
        // used to emit nothing until departure/stop → a place you were still at recorded nothing.
        // Now it emits on confirmation (ongoing) the moment it crosses minDwell, still present.
        val spot = (0 until 10).map { CapturedFix(originLat, originLng, t0 + it * min) } // 10 min, one spot
        val events = spot.asFlow().segment().toList() // no departure fix; the flush is onEnd (capture stop)

        val dwellEvents = events.filterIsInstance<CaptureEvent.Dwell>()
        val confirm = dwellEvents.firstOrNull { it.ongoing }
        assertTrue(confirm != null, "a stationary stay must emit an ongoing dwell before departure")
        assertEquals(t0, confirm.visit.arrivalMs) // arrival is the first fix, not mis-dated
        assertEquals(0, moves(events).size)        // sitting still is not a move
    }

    @Test
    fun subThresholdPauseDoesNotSplitTheMove() = runTest {
        val walkA = line(count = 8, startLat = originLat, startMs = t0, dLatE7 = 12_000, dMs = min)
        val pauseLat = originLat + 7 * 12_000
        val pause = listOf( // 2 min stop < 5 min dwell → folds into the move, never splits it
            CapturedFix(pauseLat + 200, originLng, t0 + 8 * min),
            CapturedFix(pauseLat + 400, originLng, t0 + 10 * min),
        )
        val walkB = line(count = 8, startLat = pauseLat + 12_000, startMs = t0 + 11 * min, dLatE7 = 12_000, dMs = min)
        val events = (walkA + pause + walkB).asFlow().segment().toList()

        assertEquals(1, moves(events).size) // one continuous move, not two
        assertEquals(0, dwells(events).size)
    }

    @Test
    fun jitterBelowThresholdsIsDropped() = runTest {
        // Small wander: within ~30 m over 2 min → under both the distance and duration floors.
        val fixes = listOf(
            CapturedFix(originLat, originLng, t0),
            CapturedFix(originLat + 1_000, originLng, t0 + 1 * min),
            CapturedFix(originLat, originLng + 1_000, t0 + 2 * min),
        )
        assertEquals(emptyList(), fixes.asFlow().segment().toList())
    }

    @Test
    fun pureWalkWithNoDwellEmitsOneMoveOnCaptureStop() = runTest {
        // The trigger report: a 30-min walk that never dwells now produces exactly one move.
        val walk = line(count = 30, startLat = originLat, startMs = t0, dLatE7 = 12_000, dMs = min)
        val events = walk.asFlow().segment().toList()

        assertEquals(1, moves(events).size)
        assertEquals(0, dwells(events).size)
        assertEquals(MovementMode.WALK, moves(events).single().mode)
    }

    @Test
    fun overLongOpenMoveIsForceClosedByTheCap() = runTest {
        // A fast drive with no dwell, capped at 20 min so a long journey surfaces in legs.
        val config = MovementDetectionConfig(maxOpenMoveMs = 20 * min)
        val drive = line(count = 45, startLat = originLat, startMs = t0, dLatE7 = 200_000, dMs = min)

        val events = drive.asFlow().segment(moveConfig = config).toList()

        assertTrue(moves(events).size >= 2, "expected the cap to split the drive into ≥2 legs")
        assertEquals(0, dwells(events).size)
        assertTrue(moves(events).all { it.mode == MovementMode.DRIVE })
    }
}
