package app.tenshu.timeline.data.capture

import app.tenshu.vectortile.LonLat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RailRefinementSweep.segmentPath] — the split that keeps a long journey's rail survey at full z14
 * precision (`timeline-recognition-and-place-naming.md` §3). A short route stays whole; a long one
 * splits into z14-sized pieces that overlap by a point.
 */
class RailRefinementSegmentTest {

    @Test
    fun aShortRouteStaysOneSegment() {
        val short = (0..5).map { LonLat(lon = 11.5 + it * 0.01, lat = 48.1) } // ~0.05° span
        assertEquals(1, RailRefinementSweep.segmentPath(short).size)
    }

    @Test
    fun aLongRouteSplitsIntoBoundedOverlappingSegments() {
        // ~1° lng × 0.2° lat diagonal — well past the 0.1° per-segment budget.
        val long = (0..40).map { LonLat(lon = 11.5 + it * 0.025, lat = 48.1 + it * 0.005) }
        val segs = RailRefinementSweep.segmentPath(long)

        assertTrue(segs.size > 1, "a long route must split, got ${segs.size}")
        segs.forEach { s ->
            val lonSpan = s.maxOf { it.lon } - s.minOf { it.lon }
            val latSpan = s.maxOf { it.lat } - s.minOf { it.lat }
            assertTrue(lonSpan <= 0.1 + 1e-9, "segment lon span too big: $lonSpan")
            assertTrue(latSpan <= 0.1 + 1e-9, "segment lat span too big: $latSpan")
        }
        // Consecutive segments overlap by one point so the unioned geometry has no seam.
        for (i in 1 until segs.size) assertEquals(segs[i - 1].last(), segs[i].first())
        // No points are lost (accounting for the shared overlap point at each cut).
        val distinct = segs.sumOf { it.size } - (segs.size - 1)
        assertEquals(long.size, distinct)
    }
}
