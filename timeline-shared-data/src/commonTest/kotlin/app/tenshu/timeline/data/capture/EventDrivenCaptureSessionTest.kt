package app.tenshu.timeline.data.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure, device-free coverage of the **event-model movement session** (M4,
 * `docs/proposals/timeline-movement-capture.md` §6) — the iOS shape where the platform (`CLVisit`)
 * is the dwell authority and this session shapes the sparse significant-change fix stream into
 * moves: a dwell boundary flushes the transit that led there (terminated at the visit's centroid),
 * the segmenter is reset so a later trip cannot extend the flushed move, a retroactive arrival
 * timestamp is coerced forward, and sub-threshold jitter never becomes a move.
 */
class EventDrivenCaptureSessionTest {

    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private val originLat = 525_000_000L
    private val originLng = 134_000_000L

    /** SLC-shaped fixes: ~550 m apart (5 min cadence) — sparse, never clustering into a dwell. */
    private fun slcLeg(count: Int, startLat: Long, startMs: Long) =
        (0 until count).map { CapturedFix(startLat + it * 50_000, originLng, startMs + it * 5 * min) }

    @Test
    fun dwellBoundaryFlushesTheLedUpToMoveEndingAtTheCentroid() {
        val session = EventDrivenCaptureSession()
        val leg = slcLeg(count = 4, startLat = originLat, startMs = t0)
        val inFlight = leg.flatMap { session.onFix(it) }
        assertTrue(
            inFlight.filterIsInstance<CaptureEvent.Move>().isEmpty(),
            "sparse fixes alone should not close a move",
        )
        // …but they do describe the leg as *open* (§12.6): iOS holds no capture process, so this is
        // the only signal the app has that a journey is under way before it flushes.
        assertTrue(
            inFlight.filterIsInstance<CaptureEvent.MoveProgress>().isNotEmpty(),
            "the open leg is surfaced as progress",
        )

        val centroid = CapturedFix(originLat + 4 * 50_000, originLng, t0 + 20 * min)
        val flushed = session.onDwellBoundary(centroid)

        assertEquals(1, flushed.size)
        val move = flushed.single().move
        assertEquals(t0, move.startMs)
        assertEquals(centroid.timestampMs, move.endMs)
        assertEquals(centroid.latE7, move.fixes.last().latE7, "path should terminate at the visit")
    }

    @Test
    fun segmenterDwellsAreDroppedThePlatformIsTheAuthority() {
        // Dense same-place fixes WOULD form a dwell in the segmenter — the session must not
        // surface it (CLVisit owns dwells); and clustered fixes are not movement either.
        val session = EventDrivenCaptureSession()
        val stationary = (0 until 10).map {
            CapturedFix(originLat + it * 100, originLng, t0 + it * min) // ~1 m steps, 10 min
        }
        val events = stationary.flatMap { session.onFix(it) }
        assertTrue(events.isEmpty(), "no Dwell may surface and no move exists")
    }

    @Test
    fun boundaryResetsSoTheNextTripIsItsOwnMove() {
        val session = EventDrivenCaptureSession()
        slcLeg(count = 4, startLat = originLat, startMs = t0).forEach { session.onFix(it) }
        val first = session.onDwellBoundary(CapturedFix(originLat + 4 * 50_000, originLng, t0 + 20 * min))
        assertEquals(1, first.size)

        // Second trip, an hour later, from the same place northward.
        val trip2Start = t0 + 80 * min
        slcLeg(count = 4, startLat = originLat + 4 * 50_000, startMs = trip2Start).forEach { session.onFix(it) }
        val second = session.onDwellBoundary(
            CapturedFix(originLat + 8 * 50_000, originLng, trip2Start + 20 * min),
        )

        assertEquals(1, second.size)
        val move = second.single().move
        assertEquals(trip2Start, move.startMs, "a reused buffer would drag startMs back to trip 1")
    }

    @Test
    fun retroactiveArrivalTimestampIsCoercedForward() {
        // CLVisit delivers the arrival AFTER the stay is confirmed, with arrivalDate in the past —
        // possibly older than the last SLC fix. The endpoint must not go backwards in time.
        val session = EventDrivenCaptureSession()
        val leg = slcLeg(count = 4, startLat = originLat, startMs = t0)
        leg.forEach { session.onFix(it) }
        val lastFixMs = leg.last().timestampMs

        val staleArrival = CapturedFix(originLat + 4 * 50_000, originLng, lastFixMs - 10 * min)
        val flushed = session.onDwellBoundary(staleArrival)

        assertEquals(1, flushed.size)
        assertEquals(lastFixMs, flushed.single().move.endMs)
    }

    @Test
    fun subThresholdWanderNeverBecomesAMove() {
        val session = EventDrivenCaptureSession()
        // Two fixes 200 m apart — under the 250 m minimum and only 2 points.
        session.onFix(CapturedFix(originLat, originLng, t0))
        session.onFix(CapturedFix(originLat + 18_000, originLng, t0 + 5 * min))
        val flushed = session.onDwellBoundary(CapturedFix(originLat + 18_500, originLng, t0 + 6 * min))
        assertTrue(flushed.isEmpty())
    }

    @Test
    fun onEndFlushesTheOpenTail() {
        val session = EventDrivenCaptureSession()
        slcLeg(count = 5, startLat = originLat, startMs = t0).forEach { session.onFix(it) }
        val flushed = session.onEnd()
        assertEquals(1, flushed.size)
        assertTrue(flushed.single().move.distanceM > 250.0)
    }
}
