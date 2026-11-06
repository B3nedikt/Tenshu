package app.tenshu.timeline.data.capture

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **§22 — fix-stream hygiene**, pinned against the shapes measured in real captured data (§21).
 *
 * ⚠⚠ The two load-bearing claims here are both counter-intuitive and both came from measurement, not
 * reasoning: **sorting an out-of-order stream is worse than dropping from it**, and **ordering is not
 * what inflates distance — coarse fixes are.** Each has a test that would fail if someone "fixed" the
 * code the intuitive way.
 */
class FixHygieneTest {

    private val t0 = 1_800_000_000_000L
    private val lat0 = 48.13
    private val lon0 = 11.57

    private fun east(m: Double): Double = m / (111_320.0 * cos(lat0 * PI / 180.0))

    /** A fix [eastM] along the route, [northM] off it, at [sec], reporting [acc] metres. */
    private fun fix(eastM: Double, sec: Int, acc: Int = 10, northM: Double = 0.0) = CapturedFix(
        latE7 = ((lat0 + northM / 111_320.0) * 1e7).roundToLong(),
        lngE7 = ((lon0 + east(eastM)) * 1e7).roundToLong(),
        timestampMs = t0 + sec * 1000L,
        accuracyM = acc,
    )

    // ── time order ─────────────────────────────────────────────────────────────

    @Test
    fun anOutOfOrderFixIsDroppedNotReordered() {
        // The real shape: a late-arriving NETWORK fix stamped BEFORE the GPS fix already emitted.
        val stream = listOf(fix(0.0, 0), fix(1_000.0, 60), fix(500.0, 52), fix(2_000.0, 120))

        val ordered = FixHygiene.inTimeOrder(stream)

        assertEquals(listOf(0L, 60L, 120L), ordered.map { (it.timestampMs - t0) / 1000 })
        assertTrue(
            ordered.zipWithNext().all { (a, b) -> b.timestampMs > a.timestampMs },
            "the result must be strictly increasing",
        )
    }

    @Test
    fun droppingBeatsSortingOnTheShapeThatMotivatedThis() {
        // ⚠⚠ Measured on the real journey: as-stored 129.8 km, SORTED 132.9 km, dropped 128.6 km.
        // Sorting relocates the stale fix into the middle of the path, turning one there-and-back
        // excursion into two long legs. This fixture reproduces that in miniature, so anyone who
        // "improves" inTimeOrder into a sort has to delete a test that explains why not.
        val stream = listOf(fix(0.0, 0), fix(2_000.0, 60), fix(1_000.0, 30, northM = 900.0), fix(4_000.0, 120))

        val dropped = FixHygiene.inTimeOrder(stream).pathDistanceMeters()
        val sorted = stream.sortedBy { it.timestampMs }.pathDistanceMeters()

        assertTrue(sorted > dropped, "sorted=$sorted should exceed dropped=$dropped")
        assertEquals(4_000.0, dropped, 50.0, "dropping leaves the straight route intact")
    }

    @Test
    fun anAlreadyOrderedStreamIsUntouched() {
        val stream = (0..5).map { fix(it * 500.0, it * 60) }
        assertEquals(stream, FixHygiene.inTimeOrder(stream))
    }

    // ── geometry credibility ───────────────────────────────────────────────────

    /**
     * A straight 4 km run with one cell-tower fix displaced 900 m off the route.
     *
     * ⚠ The displacement, not the accuracy figure, is what costs distance: the path pays to go out
     * to the bad fix and come back, so a 900 m excursion adds ~690 m to a 4 km route. That is the
     * shape §21 measured at scale (a 600 m-accuracy fix mid-journey), in miniature.
     */
    private fun routeWithExcursion() = listOf(
        fix(0.0, 0), fix(1_000.0, 60), fix(2_000.0, 120, acc = 600, northM = 900.0),
        fix(3_000.0, 180), fix(4_000.0, 240),
    )

    @Test
    fun aCoarseFixIsNotARouteVertex() {
        val raw = routeWithExcursion()
        val geo = FixHygiene.geometryPath(raw, maxAccuracyM = 200)

        assertEquals(4, geo.size)
        assertTrue(geo.none { it.accuracyM == 600 })
        // The excursion is what inflates: the raw path pays for going 700 m off-route and back.
        assertTrue(
            raw.pathDistanceMeters() - geo.pathDistanceMeters() > 500,
            "expected the excursion to be removed, raw=${raw.pathDistanceMeters()} geo=${geo.pathDistanceMeters()}",
        )
        assertEquals(4_000.0, geo.pathDistanceMeters(), 50.0)
    }

    @Test
    fun aPathRecordedENTIRELYOnCoarseFixesIsKept() {
        // ⚠⚠ The measured case that forbids a hard filter: over 65 real traces a blunt drop pushed
        // THREE below the 3-fix floor and halved several genuine journeys. A route with known
        // uncertainty beats no route.
        val allCoarse = (0..5).map { fix(it * 900.0, it * 60, acc = 500) }

        assertEquals(allCoarse, FixHygiene.geometryPath(allCoarse, maxAccuracyM = 200))
        assertNotNull(allCoarse.toCapturedMoveOrNull(MovementDetectionConfig()))
    }

    // ── §32.6: the ends are presence, not geometry ─────────────────────────────

    @Test
    fun aCoarseTerminalFixIsKeptWhileACoarseInteriorOneIsNot() {
        // ⚠⚠ The whole rule in one path: two 600 m fixes, one in the middle and one at the end. The
        // middle one is an excursion (drop it); the last one is *where the leg ended* (keep it).
        // Asserting both on one input is what makes this evidence rather than two agreeable halves.
        val raw = listOf(
            fix(0.0, 0),
            fix(1_000.0, 60),
            fix(2_000.0, 120, acc = 600, northM = 900.0), // interior excursion
            fix(3_000.0, 180),
            fix(5_000.0, 240, acc = 600),                 // arrival, coarse *because* of where it is
        )
        val geo = FixHygiene.geometryPath(raw, maxAccuracyM = 200)

        assertEquals(4, geo.size)
        assertTrue(geo.none { it.latE7 != raw.first().latE7 }, "the off-route excursion is gone")
        assertEquals(raw.last(), geo.last(), "the arrival must survive — nothing else says where we got to")
        assertEquals(5_000.0, geo.pathDistanceMeters(), 50.0)
    }

    @Test
    fun aCoarseDepartureFixIsKeptToo() {
        // Symmetric, and for the same reason: the first fix of a move buffer is the dwell we left.
        val raw = listOf(fix(0.0, 0, acc = 600)) + (1..4).map { fix(it * 1_000.0, it * 60) }
        val geo = FixHygiene.geometryPath(raw, maxAccuracyM = 200)
        assertEquals(raw.first(), geo.first(), "the departure anchor must survive")
        assertEquals(5, geo.size)
    }

    @Test
    fun theUndergroundArrivalIsNoLongerDecidedByItsAccuracy() {
        // ⭐ The reported U5 ride, end to end through the segmenter: the entire underground portion is
        // one jump between the last fix before descending and the first after emerging — and that
        // arrival fix is coarse *because* the rider was underground. Under §22 the leg stopped where
        // GPS stopped (1223 m, ending at Isartor) rather than where the journey did.
        //
        // ⚠⚠ The assertion is an *equivalence*, not a distance: the same journey must record the same
        // way whether or not the arrival happened to get a good fix. A threshold tweak that merely
        // moved the cliff would still fail this.
        fun ride(arrivalAccuracyM: Int): CapturedMove? {
            val leg = (0 until 16).map { fix(it * 60.0, 60 + it * 30) }
            val emerge = fix(2_100.0, 600, acc = arrivalAccuracyM)
            val dwell = (0 until 12).map { fix(2_100.0, 660 + it * 60, acc = arrivalAccuracyM) }
            val segmenter = MovementSegmenter()
            val events = (leg + emerge + dwell).flatMap { segmenter.onFix(it) } + segmenter.onEnd()
            return events.filterIsInstance<CaptureEvent.Move>().map { it.move }.maxByOrNull { it.distanceM }
        }

        val coarse = assertNotNull(ride(arrivalAccuracyM = 300), "the ride must be recorded at all")
        val fine = assertNotNull(ride(arrivalAccuracyM = 40))
        assertEquals(fine.distanceM, coarse.distanceM, 1.0, "a coarse arrival must not truncate the ride")
        assertEquals(
            fine.fixes.last().lngE7,
            coarse.fixes.last().lngE7,
            "both must reach the place the rider actually emerged at",
        )
        assertTrue(coarse.distanceM > 2_000.0, "the underground jump is real travel, was ${coarse.distanceM}")
    }

    @Test
    fun unreportedAccuracyIsKept() {
        // 0 means NOT REPORTED, never "poor" — the convention anchorRadiusM and confidenceOf follow.
        val unknown = (0..4).map { fix(it * 800.0, it * 60, acc = 0) }
        assertEquals(unknown, FixHygiene.geometryPath(unknown, maxAccuracyM = 200))
    }

    @Test
    fun theMoveBuilderAppliesBothAndReportsTheCleanedDistance() {
        val raw = routeWithExcursion()
        val move = assertNotNull(raw.toCapturedMoveOrNull(MovementDetectionConfig()))

        assertEquals(4_000.0, move.distanceM, 60.0)
        assertTrue(move.fixes.none { it.accuracyM == 600 }, "the coarse vertex must not reach the trace")

        // ⚠ The twin, in config: with the geometry bar effectively disabled the SAME path reports the
        // inflated distance — so this test cannot pass by the fixture simply being short.
        val unfiltered = assertNotNull(
            raw.toCapturedMoveOrNull(MovementDetectionConfig(maxGeometryAccuracyM = 10_000)),
        )
        assertTrue(
            unfiltered.distanceM > move.distanceM + 500,
            "unfiltered=${unfiltered.distanceM} filtered=${move.distanceM}",
        )
    }

    @Test
    fun aJitterCloudOfCoarseFixesStillDoesNotBecomeAMove() {
        // S6b must survive §22: dropping coarse vertices must not accidentally turn a stationary
        // cloud into a credible short line.
        val cloud = (0..9).map {
            fix(if (it % 2 == 0) 0.0 else 60.0, it * 60, acc = 150, northM = if (it % 3 == 0) 50.0 else -40.0)
        }
        assertNull(cloud.toCapturedMoveOrNull(MovementDetectionConfig()))
    }

    @Test
    fun dwellClusteringStillSeesCoarseFixes() {
        // ⚠⚠ §11.1: indoors GPS often never fixes and NETWORK does — geometry filtering must not
        // reach the presence path, or an indoor place stops recording entirely.
        val config = VisitDetectionConfig()
        val anchor = fix(0.0, 0, acc = 100)
        val coarseNearby = fix(120.0, 60, acc = 100)
        assertTrue(config.holdsInCluster(anchor, coarseNearby), "a coarse fix must still hold a dwell")
    }
}
