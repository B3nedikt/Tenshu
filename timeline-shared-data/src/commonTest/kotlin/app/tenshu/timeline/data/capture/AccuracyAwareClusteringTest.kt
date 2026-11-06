package app.tenshu.timeline.data.capture

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure, device-free coverage of **S6** (`docs/proposals/timeline-movement-capture.md` §18.2/§18.3/§18.7):
 * the accuracy-aware dwell anchor radius (S6a) and the extent gate that kills the phantom walk at
 * source (S6b).
 *
 * ⚠⚠ **Every negative assertion here is twinned with the same fixture under the pre-S6 rule**, and
 * the twin has to *fail* the way the report described. A "no phantom move" assertion that would also
 * hold against an implementation that never emits a move proves nothing; the twin is what makes it
 * evidence. Both twins are expressed purely in *config* — `maxAnchorRadiusM = radiusM` collapses the
 * accuracy scaling back to the old bare constant, and `minExtentM = 0` / `extentAccuracyFactor = 0`
 * disables the extent gate — so the old behaviour is reachable without a test-only code path.
 */
class AccuracyAwareClusteringTest {

    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private val originLat = 525_000_000L
    private val originLng = 134_000_000L

    /** Metres → E7 at the fixture's latitude, so offsets can be written in the units the rule is in. */
    private fun latE7(metres: Double): Long = (metres / 0.011132).roundToLong()
    private fun lngE7(metres: Double): Long =
        (metres / (0.011132 * cos(52.5 * PI / 180.0))).roundToLong()

    /** A fix [north]/[east] metres from the fixture origin, at minute [minute], reporting [accuracy]. */
    private fun at(north: Double, east: Double, minute: Int, accuracy: Int) = CapturedFix(
        latE7 = originLat + latE7(north),
        lngE7 = originLng + lngE7(east),
        timestampMs = t0 + minute * min,
        accuracyM = accuracy,
    )

    /**
     * **The report's fixture** (§18.2): a phone that never moves, indoors, on the NETWORK provider at
     * ±150 m — a realistic indoor accuracy. ⚠ The scaling bites at ±100 m too, and deliberately so —
     * see [theReportedHundredMetreAccuracyIsNotANoOp], which pins the exact accuracy the report
     * described because that is the one value at which a factor-free rule quietly does nothing.
     *
     * Shape, deliberately, not random: a settled run near the origin, then six fixes scattered ~120 m
     * out — each of which is >100 m from the *previous* anchor, so the old rule restarts the anchor on
     * every one of them — then a settled run back near the origin. Nothing is ever more than ~150 m
     * from the first fix.
     */
    private fun jitterCloud(): List<CapturedFix> {
        val acc = 150
        val settledA = listOf(
            at(0.0, 0.0, 0, acc), at(30.0, 10.0, 1, acc), at(-20.0, 25.0, 2, acc),
            at(15.0, -30.0, 3, acc), at(-35.0, 5.0, 4, acc), at(20.0, 20.0, 5, acc),
            at(-10.0, -25.0, 6, acc), at(25.0, 15.0, 7, acc), at(-30.0, -10.0, 8, acc),
        )
        // ~120 m from the origin in six directions: >100 (the old radius) but <150 (this accuracy).
        val scatter = listOf(
            at(120.0, 0.0, 9, acc), at(0.0, 120.0, 10, acc), at(-120.0, 0.0, 11, acc),
            at(0.0, -120.0, 12, acc), at(85.0, 85.0, 13, acc), at(-85.0, -85.0, 14, acc),
        )
        val settledB = (15..23).map { m ->
            val n = listOf(0.0, 30.0, -25.0, 10.0, -35.0, 20.0, -15.0, 25.0, -20.0)[m - 15]
            val e = listOf(0.0, -20.0, 15.0, 30.0, -10.0, 25.0, -30.0, 5.0, 20.0)[m - 15]
            at(n, e, m, acc)
        }
        // A genuine departure, 600 m away, so the stay closes on a real exit rather than at onEnd.
        return settledA + scatter + settledB + at(600.0, 0.0, 30, acc)
    }

    /** The pre-S6 rule, expressed as config: a bare 100 m radius and no extent gate. */
    private val legacyVisitConfig = VisitDetectionConfig(radiusM = 100.0, maxAnchorRadiusM = 100.0)
    private val legacyMoveConfig =
        MovementDetectionConfig(minExtentM = 0.0, extentAccuracyFactor = 0.0)

    private fun moves(e: List<CaptureEvent>) = e.filterIsInstance<CaptureEvent.Move>().map { it.move }
    private fun arrivals(e: List<CaptureEvent>) =
        e.filterIsInstance<CaptureEvent.Dwell>().map { it.visit.arrivalMs }.distinct()

    @Test
    fun theOldRuleFragmentsTheStayAndMintsAPhantomWalk() = runTest {
        // ⚠ This is the *precondition* for the test below: it proves the fixture really does
        // reproduce the report, so "one dwell, no moves" afterwards is a fix and not a tautology.
        val events = jitterCloud().asFlow().segment(legacyVisitConfig, legacyMoveConfig).toList()

        assertTrue(
            arrivals(events).size >= 2,
            "expected the old bare-100 m radius to split one stay into fragments, got ${arrivals(events)}",
        )
        val phantom = moves(events)
        assertTrue(phantom.isNotEmpty(), "expected a phantom walk between the fragments")
        // And it is exactly the shape §18.3 describes: hundreds of metres of "path" from a phone that
        // never left a ~150 m ball — which is also what vetoes the read-time merge (ABSORB_MOVE_MAX_M).
        assertTrue(
            phantom.any { it.distanceM > 250.0 },
            "expected the phantom to clear minMoveDistanceM, got ${phantom.map { it.distanceM }}",
        )
        assertTrue(phantom.all { it.fixes.pathExtentMeters() < 200.0 }, "the phantom never went anywhere")
    }

    @Test
    fun underS6TheSameCloudIsOneDwellAndNoMoves() = runTest {
        val events = jitterCloud().asFlow().segment().toList()

        assertEquals(1, arrivals(events).size, "one stay must read as one dwell")
        assertEquals(emptyList(), moves(events), "a phone that never moved must record no movement")
    }

    @Test
    fun anchorRadiusIsTheFloorWhenNeitherFixReportsAccuracy() {
        val config = VisitDetectionConfig()
        val a = at(0.0, 0.0, 0, accuracy = 0)
        val b = at(10.0, 0.0, 1, accuracy = 0)
        // ⚠ 0 means *not reported*, never *perfect* — so it falls back to the floor, not the ceiling.
        assertEquals(config.radiusM, config.anchorRadiusM(a, b))
    }

    @Test
    fun anchorRadiusFollowsTheCoarserOfTheTwoFixes() {
        val config = VisitDetectionConfig()
        val precise = at(0.0, 0.0, 0, accuracy = 8)
        // ⚠ 90, not 180: the radius is `anchorAccuracyFactor` × the coarser accuracy, so a 180 m
        // fixture would be capped at maxAnchorRadiusM and this test would silently become a
        // ceiling assertion — proving nothing about *following* the coarser fix.
        val coarse = at(10.0, 0.0, 1, accuracy = 90)
        assertEquals(180.0, config.anchorRadiusM(precise, coarse))
        assertEquals(180.0, config.anchorRadiusM(coarse, precise), "the rule is symmetric")
        // A single unreported accuracy must not drag the pair back to the floor.
        val unknown = at(10.0, 0.0, 1, accuracy = 0)
        assertEquals(180.0, config.anchorRadiusM(coarse, unknown))
    }

    @Test
    fun theReportedHundredMetreAccuracyIsNotANoOp() = runTest {
        // ⚠⚠ The regression this test exists for. §18.2's report is a ±100 m indoor fix, and
        // `radiusM` is *also* 100 — so a factor-free `max(accuracy).coerceIn(radiusM, …)` returns
        // exactly the floor and the whole slice does nothing on its own motivating case. Nor would
        // lowering the floor help: coerceIn(60, 250) of 100 is still 100.
        val config = VisitDetectionConfig()
        val a = at(0.0, 0.0, 0, accuracy = 100)
        val b = at(10.0, 0.0, 1, accuracy = 100)

        assertEquals(200.0, config.anchorRadiusM(a, b))
        // Twin: at factor 1.0 — the shape the slice shipped with — the same pair collapses to the
        // floor, which is the bug. Expressed in config, so there is no test-only code path.
        assertEquals(
            config.radiusM,
            VisitDetectionConfig(anchorAccuracyFactor = 1.0).anchorRadiusM(a, b),
            "twin: without the factor the rule is a no-op at exactly the reported accuracy",
        )

        // …and end to end: the same stay, at the accuracy actually reported, must read as one dwell.
        val cloud = jitterCloud().map { it.copy(accuracyM = 100) }
        assertEquals(1, arrivals(cloud.asFlow().segment().toList()).size)
        assertTrue(
            arrivals(
                cloud.asFlow()
                    .segment(VisitDetectionConfig(anchorAccuracyFactor = 1.0), MovementDetectionConfig())
                    .toList(),
            ).size >= 2,
            "twin: at factor 1.0 the ±100 m stay still fragments",
        )
    }

    @Test
    fun anchorRadiusIsBoundedBothWays() {
        val config = VisitDetectionConfig()
        val clean = at(0.0, 0.0, 0, accuracy = 12)
        // A clean outdoor pair keeps the tight floor — widening must not be free.
        assertEquals(config.radiusM, config.anchorRadiusM(clean, at(5.0, 0.0, 1, accuracy = 15)))
        // And a hopeless cell-tower fix stops at the ceiling rather than chasing the reported value.
        val hopeless = at(5.0, 0.0, 1, accuracy = 4_000)
        assertEquals(config.maxAnchorRadiusM, config.anchorRadiusM(clean, hopeless))
    }

    @Test
    fun allThreeClusteringsStillAgreeOnAccuracyBearingFixes() = runTest {
        // ⚠⚠ The load-bearing invariant: MovementSegmenter.step, VisitDetector.detect and
        // detectVisits all read the radius through the same function, so they cannot diverge. This is
        // the same claim MovementSegmenterTest pins, re-asserted on fixes that actually exercise the
        // accuracy scaling (its fixtures report no accuracy at all, so they never would).
        val fixes = jitterCloud()

        val segmenterDwells = fixes.asFlow().segment().toList()
            .filterIsInstance<CaptureEvent.Dwell>().filter { !it.ongoing }.map { it.visit }
        val batch = VisitDetector.detect(fixes)
        val streaming = fixes.asFlow().detectVisits().toList()

        assertEquals(batch, segmenterDwells)
        assertEquals(batch, streaming)
        assertEquals(1, batch.size)
    }

    // ---- S6b, the extent gate on its own ------------------------------------------------------

    /** A clean outdoor out-and-back: 250 m north over 5 min, then back, one fix per minute. */
    private fun outAndBackWalk(): List<CapturedFix> {
        val out = (0..5).map { at(it * 50.0, 0.0, it, accuracy = 10) }
        val back = (1..5).map { at(250.0 - it * 50.0, 0.0, 5 + it, accuracy = 10) }
        return out + back
    }

    @Test
    fun aRealOutAndBackWalkIsStillRecorded() {
        val walk = outAndBackWalk()
        // ⚠⚠ The reason the gate measures extent and not net displacement: this walk ends where it
        // started, so a displacement gate would delete it.
        assertTrue(
            capturedDistanceMeters(walk.first(), walk.last()) < 5.0,
            "fixture must genuinely return to its start",
        )
        val move = walk.toCapturedMoveOrNull(MovementDetectionConfig())
        assertNotNull(move)
        assertTrue(move.distanceM > 450.0)
    }

    @Test
    fun aJitterCloudNeverBecomesAMove() {
        val cloud = jitterCloud().take(20)
        // Precondition: it is exactly the shape §18.3 warns about — long path, tiny extent — and it
        // clears every pre-S6 threshold, so only the new gate can be rejecting it.
        assertTrue(cloud.pathDistanceMeters() > MovementDetectionConfig().minMoveDistanceM)
        assertTrue(cloud.pathExtentMeters() < 200.0)
        assertNotNull(
            cloud.toCapturedMoveOrNull(legacyMoveConfig),
            "twin: with the extent gate disabled this same cloud DOES become a phantom move",
        )

        assertNull(cloud.toCapturedMoveOrNull(MovementDetectionConfig()))
    }

    @Test
    fun theExtentBarScalesWithTheFixesOwnAccuracy() {
        // 280 m of extent: comfortably real at ±10 m (bar 100 m), indistinguishable from noise at
        // ±150 m (bar 300 m). Same geometry, same timing — only the reported accuracy differs.
        val clean = (0..7).map { at(it * 40.0, 0.0, it, accuracy = 10) }
        val coarse = (0..7).map { at(it * 40.0, 0.0, it, accuracy = 150) }
        assertEquals(clean.pathExtentMeters().roundToLong(), coarse.pathExtentMeters().roundToLong())

        assertNotNull(clean.toCapturedMoveOrNull(MovementDetectionConfig()))
        assertNull(coarse.toCapturedMoveOrNull(MovementDetectionConfig()))
        // …and the *only* thing rejecting the coarse twin is the accuracy arm of the bar.
        assertNotNull(coarse.toCapturedMoveOrNull(MovementDetectionConfig(extentAccuracyFactor = 0.0)))
    }
}
