package app.tenshu.timeline.data.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure coverage of the trace codec (M1, §4): simplification collapses collinear runs and caps the
 * point count, and encode/decode round-trips the simplified path bit-for-bit — **accuracy included**
 * since M6 (§12.1), which also moved the cap from index-stride to arc-length.
 */
class MovementTracePolylineTest {

    private val t0 = 1_800_000_000_000L
    private val originLat = 525_000_000L
    private val originLng = 134_000_000L

    @Test
    fun encodeDecodeRoundTripsPath() {
        val path = (0 until 40).map {
            CapturedFix(originLat + it * 3_000, originLng + (it % 5) * 2_000, t0 + it * 30_000L)
        }
        assertEquals(path, MovementTracePolyline.decode(MovementTracePolyline.encode(path)))
    }

    @Test
    fun emptyPathRoundTrips() {
        assertEquals(emptyList(), MovementTracePolyline.decode(MovementTracePolyline.encode(emptyList())))
    }

    @Test
    fun collinearRunCollapsesToEndpoints() {
        // 10 exactly-collinear points (constant lng, even lat steps) → simplify to just the endpoints.
        val straight = (0 until 10).map { CapturedFix(originLat + it * 5_000, originLng, t0 + it * 60_000L) }
        val simplified = MovementTracePolyline.simplify(straight, MovementDetectionConfig())
        assertEquals(2, simplified.size)
        assertEquals(straight.first(), simplified.first())
        assertEquals(straight.last(), simplified.last())
    }

    @Test
    fun overLongPathIsCappedToMaxPoints() {
        // A zig-zag (no three points collinear) with epsilon 0 keeps every point through DP, so the
        // uniform-stride cap is what bounds it.
        val config = MovementDetectionConfig(maxPolylinePoints = 10, simplifyEpsilonM = 0.0)
        val zigzag = (0 until 100).map {
            CapturedFix(originLat + it * 3_000, originLng + (if (it % 2 == 0) 4_000L else -4_000L), t0 + it * 10_000L)
        }
        val simplified = MovementTracePolyline.simplify(zigzag, config)
        assertTrue(simplified.size <= 10, "capped to ≤ maxPolylinePoints")
        assertEquals(zigzag.first(), simplified.first())
        assertEquals(zigzag.last(), simplified.last())
    }

    @Test
    fun encodingPacksDeltasCompactly() {
        // A slow walk (small, steady deltas) should pack far tighter than a naive 24 bytes/point.
        val walk = (0 until 60).map { CapturedFix(originLat + it * 900, originLng + it * 300, t0 + it * 60_000L) }
        val bytes = MovementTracePolyline.encode(walk)
        assertTrue(bytes.size < walk.size * 12, "delta+varint should pack well below 12 B/point, was ${bytes.size}")
    }

    // ── M6 §12.1: accuracy survives, and the cap spends its budget by distance ───────

    @Test
    fun roundTripPreservesAccuracy() {
        // A mixed GPS/network path — the whole point is that a ±5 m fix and a ±800 m fix stay
        // distinguishable after storage, so a later classifier can discount the coarse legs.
        val path = listOf(
            CapturedFix(originLat, originLng, t0, accuracyM = 5),
            CapturedFix(originLat + 4_000, originLng, t0 + 60_000L, accuracyM = 800),
            CapturedFix(originLat + 8_000, originLng, t0 + 120_000L, accuracyM = 12),
            CapturedFix(originLat + 12_000, originLng, t0 + 180_000L, accuracyM = 0),
        )
        val decoded = MovementTracePolyline.decode(MovementTracePolyline.encode(path))
        assertEquals(path, decoded)
        assertEquals(listOf(5, 800, 12, 0), decoded.map { it.accuracyM })
    }

    @Test
    fun accuracyStreamStaysCheapWhenItBarelyVaries() {
        // Accuracy is delta-encoded, so a steady sensor should cost ≈1 byte/point over the old layout.
        val steady = (0 until 60).map {
            CapturedFix(originLat + it * 900, originLng + it * 300, t0 + it * 60_000L, accuracyM = 10)
        }
        val bytes = MovementTracePolyline.encode(steady)
        assertTrue(bytes.size < steady.size * 12, "still well under 12 B/point, was ${bytes.size}")
    }

    @Test
    fun capSpendsItsBudgetByDistanceNotIndex() {
        // A path whose points are dense at the start and sparse (but far apart) at the end — the
        // shape an index-stride cap mishandles, keeping mostly the dense head and dropping the long
        // tail's geometry. Arc-length striding must keep points spread across the whole path.
        val dense = (0 until 90).map { CapturedFix(originLat + it * 100, originLng, t0 + it * 1_000L) }
        val sparse = (1..10).map { CapturedFix(originLat + 9_000 + it * 200_000, originLng, t0 + 90_000L + it * 60_000L) }
        val path = dense + sparse

        val capped = MovementTracePolyline.simplify(
            path,
            MovementDetectionConfig(maxPolylinePoints = 10, simplifyEpsilonM = 0.0),
        )

        assertTrue(capped.size <= 10, "capped to ≤ max, was ${capped.size}")
        assertEquals(path.first(), capped.first())
        assertEquals(path.last(), capped.last())
        // The sparse tail holds ~95% of the distance, so most of the budget belongs to it.
        val inTail = capped.count { it.latE7 > originLat + 9_000 }
        assertTrue(inTail >= capped.size / 2, "expected the long tail to get most points, got $inTail/${capped.size}")
    }

    @Test
    fun coincidentPathDoesNotDivideByZero() {
        // Degenerate: every point identical (a stalled sensor) → total arc length 0. Must still cap.
        val stalled = (0 until 50).map { CapturedFix(originLat, originLng, t0 + it * 1_000L) }
        val capped = MovementTracePolyline.simplify(
            stalled,
            MovementDetectionConfig(maxPolylinePoints = 5, simplifyEpsilonM = 0.0),
        )
        assertTrue(capped.size <= 5, "was ${capped.size}")
    }
}
