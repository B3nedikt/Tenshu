package app.tenshu.timeline.data.capture

import app.tenshu.vectortile.GeoBounds
import app.tenshu.vectortile.LonLat
import app.tenshu.vectortile.RailMatcher
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The underground ride** — S4b's silent tunnel and S8's covered metro
 * (`timeline-movement-capture.md` §20/§25).
 *
 * ⚠⚠ Every negative here is twinned with the same fixture minus one guard, proving the fixture really
 * does reach the decision — a "does not fire" assertion over a detector that never fires would pass
 * against any implementation (the lesson `feedback_detector_test_negative_only_gap` records).
 */
class BlindIntervalDetectorTest {

    private val t0 = 1_800_000_000_000L
    private val lat0 = 48.13
    private val lon0 = 11.57

    /** Metres east → degrees of longitude at the fixture latitude. */
    private fun east(m: Double): Double = m / (111_320.0 * cos(lat0 * PI / 180.0))

    /**
     * A fix [eastM] along the line and [northM] **off** it, reporting [accuracyM].
     *
     * ⚠⚠ The default 100 m offset is the whole point of the fixture, not incidental. A metro rider's
     * surviving fixes are taken at *street level above a tunnel*, and with GPS error that puts them
     * tens to ~190 m from the alignment — **outside** `RailMatcher.DEFAULT_TOLERANCE_M` (50 m) and so
     * invisible to the coverage tiers, but inside `ENDPOINT_TOLERANCE_M`. That band is exactly the
     * gap this tier exists to fill: a fixture sitting *on* the line would be promoted by the ordinary
     * exclusive-coverage rule and would prove nothing about this tier at all.
     */
    private fun fix(eastM: Double, sec: Int, northM: Double = 100.0, accuracyM: Int = 10) = CapturedFix(
        latE7 = ((lat0 + northM / 111_320.0) * 1e7).roundToLong(),
        lngE7 = ((lon0 + east(eastM)) * 1e7).roundToLong(),
        timestampMs = t0 + sec * 1000L,
        accuracyM = accuracyM,
    )

    /** A straight subway line running east from the origin for [lengthM]. */
    private fun subway(lengthM: Double = 6_000.0, kind: String = "subway"): RailMatcher.RailLine {
        val points = listOf(LonLat(lon0, lat0), LonLat(lon0 + east(lengthM), lat0))
        return RailMatcher.RailLine(kind, points, GeoBounds.of(points)!!)
    }

    /** What the refinement tier actually consults: kinematics, then the map. */
    private fun anchored(fixes: List<CapturedFix>, lines: List<RailMatcher.RailLine>) =
        BlindIntervalDetector.detect(fixes)
            .filter { BlindIntervalDetector.anchoredToSubway(fixes, it, lines) }

    /** Enter at 0 m, go **silent** for 6 min, resurface 4 km east — the S4b shape. */
    private fun darkRide() = listOf(fix(0.0, 0), fix(50.0, 60), fix(4_000.0, 420), fix(4_050.0, 480))

    /**
     * The **measured** shape (§21.1), proportioned from the reported journey: the stream never goes
     * quiet, it stops being GPS. Fixes keep arriving every 90–150 s at 100–600 m accuracy, bracketed
     * by metre-accurate ones either side — and an 8-minute platform wait sits in front of the ride,
     * which is what makes the trimmed travelling span load-bearing rather than a refinement.
     *
     * Real figures it reproduces: ~2.6 km of travel, ~2.6 m/s across the whole blackout, ~5–7 m/s
     * once the wait is trimmed off, and no gap anywhere near the silent tier's three minutes.
     */
    private fun coveredMetroRide(rideSeconds: Int = 400): List<CapturedFix> {
        val approach = listOf(
            fix(-120.0, -180, accuracyM = 8),
            fix(-60.0, -120, accuracyM = 7),
            fix(0.0, -60, accuracyM = 6),
            fix(20.0, 0, accuracyM = 6), // the last GPS fix before descending
        )
        val platform = listOf(
            fix(30.0, 90, accuracyM = 100),
            fix(10.0, 220, accuracyM = 100),
            fix(40.0, 350, accuracyM = 100),
            fix(20.0, 500, accuracyM = 100),
        )
        val ride = (0..4).map { k ->
            fix(
                eastM = 40.0 + k * 650.0,
                sec = 560 + rideSeconds * k / 4,
                accuracyM = when (k) { 2 -> 600; 3 -> 400; else -> 100 },
            )
        }
        val exit = listOf(
            fix(2_680.0, 560 + rideSeconds + 60, accuracyM = 9), // GPS returns at street level
            fix(2_700.0, 560 + rideSeconds + 120, accuracyM = 7),
            fix(2_720.0, 560 + rideSeconds + 180, accuracyM = 8),
        )
        return approach + platform + ride + exit
    }

    /**
     * A blackout with **no wait in it**: [distanceM] covered steadily in [seconds], bracketed by GPS.
     *
     * Used for the ceiling, where the metro fixture cannot serve — trimming a wait out of it leaves a
     * sub-span that is in band by construction.
     */
    private fun straightBlackout(seconds: Int, distanceM: Double = 8_000.0): List<CapturedFix> {
        val approach = (0..3).map { fix(-180.0 + it * 60.0, -180 + it * 60, accuracyM = 8) }
        val dark = (1..4).map { fix(distanceM * it / 4.0, seconds * it / 4, accuracyM = 300) }
        // ⚠ GPS returns promptly rather than a minute later, so the bracket carries no stationary
        // tail. A tail would drag the average down and hand the trimming search an in-band sub-span —
        // which is a real softness of that estimator (see MAX_METRO_SPEED_MPS), not something this
        // fixture should smuggle in while claiming to test the ceiling.
        val exit = (0..2).map { fix(distanceM + it * 40.0, seconds + 20 + it * 60, accuracyM = 9) }
        return approach + dark + exit
    }

    @Test
    fun aCoveredMetroRideIsDetectedThoughTheStreamNeverGoesQuiet() {
        val fixes = coveredMetroRide()
        val intervals = anchored(fixes, listOf(subway()))

        assertEquals(1, intervals.size)
        assertEquals(BlindIntervalDetector.Kind.COARSE, intervals.single().kind)
        assertTrue(BlindIntervalDetector.dominates(fixes, intervals))

        // ⚠⚠ The premise, pinned: S4b's silent tier cannot see this ride, because no gap is long
        // enough. If this ever fails, the covered-metro tier has stopped being the thing that catches
        // it and this whole fixture is measuring something else.
        assertTrue(
            fixes.zipWithNext().all { (a, b) -> b.timestampMs - a.timestampMs < BlindIntervalDetector.MIN_DARK_GAP_MS },
            "precondition: no gap reaches the silent tier's threshold",
        )
    }

    @Test
    fun theTravellingSpanIsTrimmedOfTheWaitInsideIt() {
        // ⚠⚠ Measured on the real journey: across the whole blackout (platform wait included) the
        // ride implies 2.9 m/s — under the walk floor, so the tier would reject its own motivating
        // case. Trimming to the sub-span that actually moved puts it at ~7.5 m/s.
        val fixes = coveredMetroRide()
        val interval = anchored(fixes, listOf(subway())).single()

        val bracketSpeed = capturedDistanceMeters(fixes[interval.fromIndex], fixes[interval.toIndex]) /
            ((fixes[interval.toIndex].timestampMs - fixes[interval.fromIndex].timestampMs) / 1000.0)
        assertTrue(
            bracketSpeed < BlindIntervalDetector.MIN_IMPLIED_SPEED_MPS,
            "precondition: measuring across the wait would fail the floor ($bracketSpeed m/s)",
        )
        assertTrue(interval.impliedSpeedMps >= BlindIntervalDetector.MIN_IMPLIED_SPEED_MPS)
    }

    @Test
    fun aTraceThatNeverHadGpsProvesNothing() {
        // ⚠⚠ The load-bearing guard. Same ride, but the phone was on cell all along — indistinguishable
        // from a NETWORK-only motorway drive, so it must not promote.
        val neverFine = coveredMetroRide().map { it.copy(accuracyM = 100) }
        assertEquals(emptyList(), anchored(neverFine, listOf(subway())))

        // Twin: restore the GPS fixes either side and the identical geometry fires.
        assertEquals(1, anchored(coveredMetroRide(), listOf(subway())).size)
    }

    @Test
    fun anUnreportedAccuracyIsNotAGpsClaim() {
        // ⚠ `accuracyM == 0` means "not reported". A device that never reports quality has no provider
        // signal to read, so the tier stays silent rather than assuming the best.
        val unreported = coveredMetroRide().map { it.copy(accuracyM = 0) }
        assertEquals(0.0, BlindIntervalDetector.fineFixShare(unreported))
        assertEquals(emptyList(), anchored(unreported, listOf(subway())))
    }

    @Test
    fun aMotorwaySpeedAcrossACoarseStretchDoesNotFire() {
        // ⚠ A drive through a GPS-poor stretch beside a subway line: the same blackout shape, but
        // 8 km in 5 minutes ≈ 27 m/s and no metro averages that. The silent tier tolerates such a
        // speed deliberately (see MAX_IMPLIED_SPEED_MPS); the coarse one must not.
        val line = listOf(subway(lengthM = 10_000.0))
        assertEquals(emptyList(), anchored(straightBlackout(seconds = 300), line))
        // Twin: the identical path at metro pace (≈7 m/s) fires.
        assertEquals(1, anchored(straightBlackout(seconds = 1_200), line).size)
    }

    @Test
    fun aSilentJumpBetweenTwoPointsOnSubwayTrackIsDetected() {
        val intervals = anchored(darkRide(), listOf(subway()))

        assertEquals(1, intervals.size)
        val interval = intervals.single()
        assertEquals(BlindIntervalDetector.Kind.SILENT, interval.kind)
        assertEquals(6 * 60_000L, interval.durationMs)
        assertTrue(interval.impliedSpeedMps > BlindIntervalDetector.MIN_IMPLIED_SPEED_MPS)
        assertTrue(BlindIntervalDetector.dominates(darkRide(), intervals))
    }

    @Test
    fun aGapInsideACoarseBlackoutIsNotCountedTwice() {
        // ⚠ A long silence *within* a blackout is part of it, not a second interval — counting both
        // would let one ride pay twice into `dominates`. Here the cell fixes stop mid-ride too, so the
        // stretch qualifies under BOTH recognisers.
        val ride = coveredMetroRide()
        val withGap = ride.filterIndexed { i, _ -> i != 10 && i != 11 }
        assertTrue(
            withGap.zipWithNext()
                .any { (a, b) ->
                    b.timestampMs - a.timestampMs >= BlindIntervalDetector.MIN_DARK_GAP_MS &&
                        capturedDistanceMeters(a, b) >= BlindIntervalDetector.MIN_BLIND_DISTANCE_M
                },
            "precondition: there really is a silent-tier-shaped gap inside the blackout",
        )
        assertEquals(1, anchored(withGap, listOf(subway())).size)
    }

    @Test
    fun itPromotesTheTraceThroughRailRefinement() {
        val fixes = coveredMetroRide()
        val path = fixes.map { LonLat(it.lngE7 / 1e7, it.latE7 / 1e7) }
        val baseline = ModeClassification(MovementMode.DRIVE, confidence = 50, medianSpeedMmPerS = 9_000)

        val refined = RailRefinement.refine(
            baseline = baseline,
            path = path,
            evidence = RailEvidence.Surveyed(listOf(subway())),
            fixes = fixes,
        )
        assertEquals(MovementMode.TRANSIT, refined.mode)
        // §32.12: a covered metro ride is stamped with the *underground* refined version, not the
        // plain one — both are `transit`, and that stamp is the only thing that later tells this leg
        // apart from the surface train it connects to.
        assertEquals(MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND, refined.version)

        // ⚠⚠ The twin: the SAME geometry with the fixes withheld cannot fire the tier — which also
        // pins that every pre-S4 caller (they pass no fixes) keeps its exact behaviour.
        assertEquals(
            baseline.mode,
            RailRefinement.refine(baseline, path, RailEvidence.Surveyed(listOf(subway()))).mode,
        )
    }

    @Test
    fun aWalkWhoseMiddleWasBlindIsCorrected() {
        // ⚠⚠ The case the corpus actually contains: a short trip labelled `walk`/`run` because a
        // covered metro ride sat in its middle and the median leg speed came out low. This tier may
        // overrule a pedestrian verdict; no other one may.
        val fixes = coveredMetroRide()
        val path = fixes.map { LonLat(it.lngE7 / 1e7, it.latE7 / 1e7) }
        val baseline = ModeClassification(MovementMode.WALK, confidence = 40, medianSpeedMmPerS = 1_500)

        val refined = RailRefinement.refine(baseline, path, RailEvidence.Surveyed(listOf(subway())), fixes = fixes)
        assertEquals(MovementMode.TRANSIT, refined.mode)
        assertTrue(RailRefinement.needsRefinement(baseline, fixes), "the sweep must offer it in the first place")
    }

    @Test
    fun aWalkBesideARailwayIsStillAWalk() {
        // ⚠⚠ The twin that keeps the widening honest: proximity alone must never touch a pedestrian
        // verdict. Same subway line, a path right along it, no blind interval — unchanged AND
        // unstamped, so the sweep does not record a verdict it never examined.
        val fixes = (0..10).map { fix(it * 60.0, it * 60, northM = 5.0) }
        val path = fixes.map { LonLat(it.lngE7 / 1e7, it.latE7 / 1e7) }
        val baseline = ModeClassification(MovementMode.WALK, confidence = 40, medianSpeedMmPerS = 1_000)

        val refined = RailRefinement.refine(baseline, path, RailEvidence.Surveyed(listOf(subway())), fixes = fixes)
        assertEquals(baseline, refined)
        assertTrue(!RailRefinement.needsRefinement(baseline, fixes), "and it is never even a candidate")
    }

    @Test
    fun aGapOverHeavyRailDoesNotFire() {
        // ⚠ Subway specifically. Heavy rail is above ground and covered by the coverage tiers; letting
        // it anchor here would promote any drive that lost signal beside a mainline.
        assertEquals(emptyList(), anchored(darkRide(), listOf(subway(kind = "rail"))))
        // Twin: identical geometry tagged `subway` DOES fire.
        assertEquals(1, anchored(darkRide(), listOf(subway())).size)
    }

    @Test
    fun aGapWhoseFarEndIsNowhereNearTrackDoesNotFire() {
        // One end near a station and the other anywhere is a phone that lost signal, not a ride.
        val offTrack = listOf(fix(0.0, 0), fix(50.0, 60), fix(4_000.0, 420, northM = 5_000.0))
        assertEquals(emptyList(), anchored(offTrack, listOf(subway())))
        // Twin: pull the far end back onto the line and it fires.
        assertEquals(1, anchored(darkRide(), listOf(subway())).size)
    }

    @Test
    fun aWalkablePauseDoesNotFire() {
        // 900 m over 12 min ≈ 1.25 m/s — a stroll through a tunnel, not a metro.
        val stroll = listOf(fix(0.0, 0), fix(900.0, 720))
        assertEquals(emptyList(), anchored(stroll, listOf(subway())))
        // Twin: same distance, metro timing.
        assertEquals(1, anchored(listOf(fix(0.0, 0), fix(900.0, 200)), listOf(subway())).size)
    }

    @Test
    fun anImplausiblyFastGapDoesNotFire() {
        // ⚠ A coarse network fix or a clock jump can imply any speed. "The metro did 400 km/h" is a
        // worse answer than "unknown".
        val teleport = listOf(fix(0.0, 0), fix(5_000.0, 200 - 190))
        assertEquals(emptyList(), anchored(teleport, listOf(subway())))
    }

    @Test
    fun aShortHopInsideALongDriveDoesNotCharacteriseTheTrace() {
        // ⚠ `dominates` is a share of DISTANCE, not of time — the blind interval's time is precisely
        // what is missing. A 4 km underground hop inside a 60 km drive says nothing about the drive.
        // ⚠ The drive continues well past the 6 km subway line, so its own long gap is NOT anchored
        // and only the metro hop counts. (An earlier fixture ran the subway line the whole 70 km,
        // which made the drive itself detect as a second interval.)
        val fixes = darkRide() + fix(60_000.0, 3_600)
        val intervals = anchored(fixes, listOf(subway()))

        assertTrue(intervals.isNotEmpty(), "precondition: the hop itself is still detected")
        assertTrue(!BlindIntervalDetector.dominates(fixes, intervals), "but it must not promote the whole trace")
    }

    @Test
    fun noSubwayGeometryMeansNoVerdict() {
        assertEquals(emptyList(), anchored(darkRide(), emptyList()))
    }
}
