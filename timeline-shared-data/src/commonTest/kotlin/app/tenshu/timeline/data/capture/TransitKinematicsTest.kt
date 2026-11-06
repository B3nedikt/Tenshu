package app.tenshu.timeline.data.capture

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coverage of the **T0 kinematic transit tier** (`timeline-movement-capture.md` §13.3).
 *
 * The asymmetry that shapes this suite: a **false** "Train" on someone's commute is a confident,
 * visible lie about their life, while a **missed** train just leaves the existing "Drive". So the
 * negative cases — urban driving, motorway driving, stop-and-go traffic, coarse fixes — carry more
 * weight here than the positive ones, and each is a shape that a naive "it's fast and straight"
 * rule would get wrong.
 *
 * Paths are synthesised in metres and converted to E7 so the geometry is legible: ~89.83 E7 of
 * latitude ≈ 1 m, and at 52°N ~146 E7 of longitude ≈ 1 m.
 */
class TransitKinematicsTest {

    private val t0 = 1_800_000_000_000L
    private val originLat = 525_000_000L
    private val originLng = 134_000_000L

    private val e7PerMetreLat = 89.83
    private val e7PerMetreLng = 146.0

    /** A fix [northM] metres north and [eastM] metres east of the origin, at [atMs]. */
    private fun fix(northM: Double, eastM: Double, atMs: Long, accuracyM: Int = 8) = CapturedFix(
        latE7 = originLat + (northM * e7PerMetreLat).toLong(),
        lngE7 = originLng + (eastM * e7PerMetreLng).toLong(),
        timestampMs = atMs,
        accuracyM = accuracyM,
    )

    /**
     * A straight run at [speedMps], one fix every [stepSec], for [legs] legs — the rail-like shape
     * before any stops are inserted.
     */
    private fun straightRun(
        speedMps: Double,
        legs: Int,
        stepSec: Long = 30,
        startMs: Long = t0,
        startNorthM: Double = 0.0,
        accuracyM: Int = 8,
    ): List<CapturedFix> = (0..legs).map { i ->
        fix(startNorthM + i * speedMps * stepSec, 0.0, startMs + i * stepSec * 1000, accuracyM)
    }

    // ── the positives ────────────────────────────────────────────────────────────

    @Test
    fun aFastStraightRunWithStationStopsReadsAsTransit() {
        // ~25 m/s (90 km/h) regional train: straight, with two full stops ~7.5 km apart.
        val fixes = ArrayList<CapturedFix>()
        var t = t0
        var north = 0.0
        repeat(3) { leg ->
            // Run for 10 legs of 30 s at 25 m/s → 7.5 km.
            repeat(10) {
                fixes += fix(north, 0.0, t)
                north += 25.0 * 30
                t += 30_000
            }
            // Station: two stationary fixes (a full stop episode).
            if (leg < 2) {
                fixes += fix(north, 0.0, t); t += 30_000
                fixes += fix(north, 0.0, t); t += 30_000
            }
        }

        val result = classifyMode(fixes)
        assertEquals(MovementMode.TRANSIT, result.mode)
        assertTrue(result.confidence > 0, "a promoted verdict should carry confidence")
        assertEquals(MOVEMENT_CLASSIFIER_VERSION, result.version)
    }

    @Test
    fun railOnlyCruiseSpeedIsConclusiveOnItsOwn() {
        // ~70 m/s (252 km/h) — an ICE. No stops at all, but no road sustains this.
        val result = classifyMode(straightRun(speedMps = 70.0, legs = 30))
        assertEquals(MovementMode.TRANSIT, result.mode)
        assertTrue(result.confidence >= 50, "the speed shortcut should be confident, was ${result.confidence}")
    }

    // ── the negatives that matter most ───────────────────────────────────────────

    @Test
    fun motorwayDrivingWithNoStopsStaysDrive() {
        // Straight and fast (33 m/s ≈ 120 km/h) — the shape rule's turn-density half passes, but
        // there are no station stops, so it must NOT be promoted. This is the case that would
        // mislabel every motorway commute if the stop requirement were dropped.
        val result = classifyMode(straightRun(speedMps = 33.0, legs = 30))
        assertEquals(MovementMode.DRIVE, result.mode)
    }

    @Test
    fun aVeryLongStraightFastRunReadsAsTransitWithoutStops() {
        // The blind-spot case: a fast, near-non-stop intercity express — below the 198 km/h speed
        // shortcut and with no station stops, so neither existing rule fires. ~158 km at 33 m/s
        // (119 km/h), arrow-straight, is over LONG_RAIL_TRIP_M → the straight-fast-long rule catches it.
        val result = classifyMode(straightRun(speedMps = 33.0, legs = 160))
        assertEquals(MovementMode.TRANSIT, result.mode)
        assertTrue(result.confidence > 0, "a promoted verdict should carry confidence")
    }

    @Test
    fun aStraightFastRunInTheAmbiguousDistanceBandStaysDrive() {
        // ~119 km at 33 m/s — the reported case's distance band. Genuinely ambiguous (car, coach,
        // train), so T0 must NOT guess: it stays `drive` and is left to the T1 rail map-match.
        val result = classifyMode(straightRun(speedMps = 33.0, legs = 120))
        assertEquals(MovementMode.DRIVE, result.mode)
    }

    @Test
    fun aLongStraightButSlowRunStaysDrive() {
        // ~156 km but at 20 m/s (72 km/h) — long and straight yet below FAST_RAIL_CRUISE_MPS, so the
        // cruise conjunct rejects it. A leisurely long drive is not promoted.
        val result = classifyMode(straightRun(speedMps = 20.0, legs = 260))
        assertEquals(MovementMode.DRIVE, result.mode)
    }

    @Test
    fun aRuralDriveWithJunctionStopsStaysDrive() {
        // ⚠ The genuine false-positive risk, and the reason MIN_STATION_SPACING_M was raised from
        // 800 m and SHAPE_RULE_MIN_CRUISE_MPS added: a straight country road with stop-sign
        // junctions ~1 km apart passes the turn-density half *and* has widely-spaced full stops.
        // Only the cruise-speed conjunct separates it from a stopping train.
        //
        // Baseline must be DRIVE for this test to mean anything — otherwise it would pass simply
        // because the transit gate was never reached (which is how an earlier version of this test
        // was quietly vacuous).
        val fixes = ArrayList<CapturedFix>()
        var t = t0
        var north = 0.0
        repeat(4) { leg ->
            // ~2.3 km at ~13 m/s (47 km/h) — a real country-road pace, well under a train's cruise.
            // Deliberately *past* MIN_STATION_SPACING_M so the spacing rule passes and the
            // cruise-speed conjunct is the thing under test. (Closer junctions are rejected earlier,
            // by spacing alone — a second line of defence, not the one being exercised here.)
            repeat(12) {
                fixes += fix(north, 0.0, t)
                north += 13.0 * 15
                t += 15_000
            }
            if (leg < 3) {
                // Junction: a full stop.
                fixes += fix(north, 0.0, t); t += 30_000
                fixes += fix(north, 0.0, t); t += 30_000
            }
        }

        val evidence = transitEvidence(fixes)
        assertNotNull(evidence)
        // Precondition: the shape signals a naive rule would fire on really are present.
        assertTrue(
            evidence.turnDensityDegPerKm <= RAIL_TURN_DENSITY_DEG_PER_KM,
            "precondition: the road is straight, was ${evidence.turnDensityDegPerKm} deg/km",
        )
        assertTrue(
            evidence.wellSpacedStopCount >= MIN_STATION_STOPS,
            "precondition: stops are station-spaced, was ${evidence.wellSpacedStopCount}",
        )

        val result = classifyMode(fixes)
        assertEquals(
            MovementMode.DRIVE, result.mode,
            "a rural drive must not be promoted; cruise was ${evidence.cruiseSpeedMps} m/s",
        )
    }

    @Test
    fun stopAndGoTrafficIsNeverTransit() {
        // Congestion: many full stops, metres apart. Note this crawls slowly enough that the
        // baseline is not even DRIVE — so this asserts the *outcome*, not the transit rule, and is
        // kept only as a regression guard on the overall verdict.
        val fixes = ArrayList<CapturedFix>()
        var t = t0
        var north = 0.0
        repeat(12) {
            repeat(2) {
                fixes += fix(north, 0.0, t)
                north += 30.0
                t += 30_000
            }
            fixes += fix(north, 0.0, t); t += 30_000
            fixes += fix(north, 0.0, t); t += 30_000
        }
        assertTrue(
            classifyMode(fixes).mode != MovementMode.TRANSIT,
            "congestion must not read as transit",
        )
    }

    @Test
    fun urbanDrivingWithTurnsStaysDrive() {
        // A grid route: 90° turns every few hundred metres, with stops at lights. High turn density
        // is what a road network looks like from above.
        val fixes = ArrayList<CapturedFix>()
        var t = t0
        var north = 0.0
        var east = 0.0
        repeat(6) { block ->
            repeat(4) {
                if (block % 2 == 0) north += 100.0 else east += 100.0
                fixes += fix(north, east, t)
                t += 10_000
            }
            // Traffic light.
            fixes += fix(north, east, t); t += 30_000
        }
        val result = classifyMode(fixes)
        assertTrue(
            result.mode != MovementMode.TRANSIT,
            "a turning urban route must not read as transit, was ${result.mode}",
        )
    }

    // ── the airborne band ────────────────────────────────────────────────────────

    @Test
    fun aCruisingAirlinerClassifiesAsFlight() {
        // ~240 m/s (864 km/h). Nothing on the ground comes close, so no shape analysis is needed —
        // and it must NOT be called transit, which would be a different kind of wrong.
        val result = classifyMode(straightRun(speedMps = 240.0, legs = 30))
        assertEquals(MovementMode.FLIGHT, result.mode)
        assertTrue(result.confidence >= 50, "the speed band is conclusive; was ${result.confidence}")
    }

    @Test
    fun theSlowEndOfTheAirborneBandStillBeatsGroundTransit() {
        // Just above the ground ceiling (~130 m/s / 468 km/h): faster than any train, so airborne.
        assertEquals(MovementMode.FLIGHT, classifyMode(straightRun(speedMps = 130.0, legs = 30)).mode)
        // Just below it, a rail-only cruise stays transit rather than being swept into flight.
        assertEquals(MovementMode.TRANSIT, classifyMode(straightRun(speedMps = 110.0, legs = 30)).mode)
    }

    @Test
    fun absurdSpeedsAreAGpsArtefactAndClaimNothing() {
        // ⚠ Cruise is the 85th-percentile leg speed, so on a corrupted path it can be dragged
        // arbitrarily high. "Flight" on a garbled walk would be worse than the baseline it replaces,
        // so the band is bounded above as well as below.
        val absurd = classifyMode(straightRun(speedMps = 900.0, legs = 30))
        assertTrue(
            absurd.mode != MovementMode.FLIGHT && absurd.mode != MovementMode.TRANSIT,
            "an impossible speed must not be promoted at all, was ${absurd.mode}",
        )
    }

    @Test
    fun coarseFixesDoNotEarnATransitVerdict() {
        // Same rail-only speed, but ±200 m network fixes: the geometry is untrustworthy, so the
        // promotion must lose its confidence rather than assert a train from noise.
        val precise = classifyMode(straightRun(speedMps = 70.0, legs = 30, accuracyM = 5))
        val coarse = classifyMode(straightRun(speedMps = 70.0, legs = 30, accuracyM = 200))
        assertEquals(MovementMode.TRANSIT, precise.mode)
        assertTrue(
            coarse.confidence < precise.confidence,
            "coarse ${coarse.confidence} should be < precise ${precise.confidence}",
        )
        assertEquals(0, coarse.confidence, "fixes this coarse carry no usable shape signal")
    }

    @Test
    fun tooFewLegsIsNeverPromoted() {
        // A short hop can look arbitrarily rail-like by chance; the sample guard rejects it.
        val result = classifyMode(straightRun(speedMps = 70.0, legs = 4))
        assertEquals(MovementMode.DRIVE, result.mode)
    }

    // ── the promotion contract ───────────────────────────────────────────────────

    @Test
    fun onlyDriveIsEligibleForPromotion() {
        // Walk / run / cycle are never trains, whatever their shape. A straight, stop-punctuated
        // walking path has exactly the geometry the shape rule looks for — and must be left alone.
        val walk = ArrayList<CapturedFix>()
        var t = t0
        var north = 0.0
        repeat(3) { leg ->
            repeat(10) {
                walk += fix(north, 0.0, t)
                north += 1.4 * 30 // ~1.4 m/s
                t += 30_000
            }
            if (leg < 2) {
                walk += fix(north, 0.0, t); t += 30_000
                walk += fix(north, 0.0, t); t += 30_000
            }
        }
        val result = classifyMode(walk)
        assertTrue(result.mode != MovementMode.TRANSIT, "a walk is never transit, was ${result.mode}")
    }

    @Test
    fun refinementNeverDemotesAnEstablishedBucket() {
        val fixes = straightRun(speedMps = 70.0, legs = 30)
        for (baselineMode in listOf(MovementMode.WALK, MovementMode.RUN, MovementMode.CYCLE, MovementMode.UNKNOWN)) {
            val baseline = ModeClassification(baselineMode, confidence = 70, medianSpeedMmPerS = 1_000)
            assertEquals(baseline, refineWithTransitKinematics(fixes, baseline))
        }
    }

    @Test
    fun promotionPreservesTheMeasuredSpeedAndVersion() {
        val fixes = straightRun(speedMps = 70.0, legs = 30)
        val baseline = classifyMode(fixes)
        // The median speed is a measurement, not a verdict — a promotion must not overwrite it.
        assertTrue(baseline.medianSpeedMmPerS > 60_000, "was ${baseline.medianSpeedMmPerS} mm/s")
        assertEquals(MOVEMENT_CLASSIFIER_VERSION, baseline.version)
    }

    // ── the evidence itself ──────────────────────────────────────────────────────

    @Test
    fun turnDensityDistinguishesAStraightRunFromAGrid() {
        val straight = transitEvidence(straightRun(speedMps = 30.0, legs = 20))
        assertNotNull(straight)
        assertTrue(
            straight.turnDensityDegPerKm < RAIL_TURN_DENSITY_DEG_PER_KM,
            "a straight run should be rail-like, was ${straight.turnDensityDegPerKm} deg/km",
        )

        // A circular arc of 500 m radius: 360° over ~3.14 km ≈ 115 deg/km — road-like.
        val circle = (0..40).map { i ->
            val angle = i * (2 * PI / 40)
            fix(500.0 * sin(angle), 500.0 * (1 - cos(angle)), t0 + i * 30_000L)
        }
        val curved = transitEvidence(circle)
        assertNotNull(curved)
        assertTrue(
            curved.turnDensityDegPerKm > RAIL_TURN_DENSITY_DEG_PER_KM,
            "a tight circle should not be rail-like, was ${curved.turnDensityDegPerKm} deg/km",
        )
    }

    @Test
    fun stopEpisodesCollapseRatherThanCountingEveryStationaryFix() {
        // Five consecutive stationary fixes are ONE stop, not five.
        val fixes = ArrayList<CapturedFix>()
        var t = t0
        var north = 0.0
        repeat(10) {
            fixes += fix(north, 0.0, t); north += 25.0 * 30; t += 30_000
        }
        repeat(5) { fixes += fix(north, 0.0, t); t += 30_000 }
        repeat(10) {
            fixes += fix(north, 0.0, t); north += 25.0 * 30; t += 30_000
        }
        val evidence = transitEvidence(fixes)
        assertNotNull(evidence)
        assertEquals(1, evidence.stopCount)
    }

    @Test
    fun degenerateInputYieldsNoEvidence() {
        assertEquals(null, transitEvidence(emptyList()))
        assertEquals(null, transitEvidence(listOf(fix(0.0, 0.0, t0))))
        // All fixes at the same instant → no usable legs at all.
        assertEquals(null, transitEvidence((0..5).map { fix(it * 100.0, 0.0, t0) }))
    }

    // ── §32.11: the verdict must not depend on where the clock ticked ──────────

    @Test
    fun theSameRideClassifiesTheSameWhateverThePhaseOfTheSampling() {
        // ⚠⚠ The defect, as an invariance. One journey — same route, same two 60 s station stops,
        // same 60 s cadence — sampled at six different offsets. Before §32.11 exactly one offset in
        // six promoted, because a "full stop" needs two consecutive fixes BOTH inside the stop and at
        // this cadence that is a coin flip. Nothing about the journey changes between these six runs,
        // so nothing about the verdict may either.
        val verdicts = (0 until 60 step 10).map { phase ->
            phase to classifyMode(rideSampledAt(phaseSec = phase)).mode
        }
        assertTrue(
            verdicts.all { it.second == MovementMode.TRANSIT },
            "the verdict must not depend on sampling phase: $verdicts",
        )
    }

    @Test
    fun aStopSmearedAcrossOneSamplingIntervalStillCounts() {
        // The mechanism, stated directly: at 60 s sampling a 60 s stop can never produce a leg at
        // ≤ STOP_SPEED_MPS, only one dragged toward half cruise. If this ever needs a standstill
        // again, the phase-invariance case above goes with it.
        val evidence = assertNotNull(transitEvidence(rideSampledAt(phaseSec = 30)))
        assertTrue(evidence.wellSpacedStopCount >= MIN_STATION_STOPS, "was ${evidence.wellSpacedStopCount}")
        assertTrue(
            evidence.cruiseSpeedMps >= SHAPE_RULE_MIN_CRUISE_MPS,
            "precondition: the fixture must still be a fast run, was ${evidence.cruiseSpeedMps}",
        )
    }

    /**
     * A 22 km run with two intermediate 60 s station stops, sampled every 60 s starting at
     * [phaseSec]. ⚠ The position is a function of *time*, so the fixes land where the clock puts
     * them — a fixture that generated a fix inside each stop would be assuming the thing under test.
     */
    private fun rideSampledAt(phaseSec: Int, everySec: Int = 60, stopSec: Int = 60): List<CapturedFix> {
        val runSec = 300
        val cruise = 25.0
        fun position(t: Int): Double {
            var pos = 0.0
            var rem = t
            repeat(3) { i ->
                val moving = minOf(rem, runSec)
                pos += moving * cruise
                rem -= moving
                if (rem <= 0) return pos
                if (i < 2) {
                    rem -= minOf(rem, stopSec)
                    if (rem <= 0) return pos
                }
            }
            return pos + rem * cruise
        }
        val total = 3 * runSec + 2 * stopSec
        return (phaseSec..total step everySec).map { t ->
            CapturedFix(
                latE7 = 482_478_000L + (position(t) / 0.011132).roundToLong(),
                lngE7 = 125_190_000L,
                timestampMs = 1_800_000_000_000L + t * 1000L,
                accuracyM = 60,
            )
        }
    }
}
