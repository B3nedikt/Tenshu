package app.tenshu.timeline.data.capture

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **§32.15 — turn density's bearing bar scales with the trace's own fix accuracy.**
 *
 * ### ⚠⚠ The property under test, stated as a property rather than a threshold
 *
 * Turn density sums the **absolute** heading change between consecutive legs, so noise in the
 * endpoint fixes can only ever *increase* it — there is no cancelling term. The estimator is
 * one-sided, and a bar below the noise floor does not add scatter, it adds **bias**, in the one
 * direction that reads as "road-like". [aStationaryPlatformJitterLegOnlyEverAddsTurn] pins that
 * claim directly; everything else here is its consequence.
 *
 * The journey this comes from: a real 79 km regional train, median reported accuracy **64 m** against
 * a fixed 30 m bar. It measured 54.7 °/km against a ≤ 25 bar and stayed `drive` — while every other
 * conjunct passed (105 km/h cruise, 10 well-spaced stops).
 */
class TransitBearingBarTest {

    private val t0 = 1_800_000_000_000L
    private val originLat = 525_000_000L
    private val originLng = 134_000_000L
    private val e7PerMetreLat = 89.83
    private val e7PerMetreLng = 146.0

    private fun fix(northM: Double, eastM: Double, atMs: Long, accuracyM: Int) = CapturedFix(
        latE7 = originLat + (northM * e7PerMetreLat).toLong(),
        lngE7 = originLng + (eastM * e7PerMetreLng).toLong(),
        timestampMs = atMs,
        accuracyM = accuracyM,
    )

    /**
     * Deterministic pseudo-noise in `[-1, 1)`.
     *
     * ⚠ A fixed LCG, not a platform RNG: a fixture whose verdict depends on the seed of the day is
     * not evidence about anything, and `kotlin.random` is not reproducible across KMP targets.
     */
    private class Jitter(private var state: Long = 0x5DEECE66DL) {
        fun next(): Double {
            state = (state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L)
            return ((state ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
        }
    }

    /**
     * A stopping train with **realistic fix quality**: straight track, stations ~4 km apart, and the
     * platform jitter that a ±[accuracyM] fix produces while the train is standing still.
     *
     * ⚠⚠ The jitter at the *stations* is the whole point, not decoration. A standing train produces
     * consecutive fixes a few tens of metres apart — legs that clear a 30 m bar while carrying no
     * heading information at all. Those are what inflated the real journey's turn density; the moving
     * legs (600 m at 30 m/s) were never the problem.
     */
    private fun stoppingTrain(accuracyM: Int, stations: Int = 8): List<CapturedFix> {
        val jitter = Jitter()
        val fixes = ArrayList<CapturedFix>()
        var t = t0
        var north = 0.0
        val wobble = accuracyM * 0.7
        repeat(stations) { leg ->
            // ~4 km between stations at 30 m/s, sampled every 20 s → 600 m legs.
            repeat(7) {
                fixes += fix(north + jitter.next() * wobble, jitter.next() * wobble, t, accuracyM)
                north += 30.0 * 20
                t += 20_000
            }
            if (leg < stations - 1) {
                // Standing at the platform: position frozen, only the fix noise moves.
                repeat(3) {
                    fixes += fix(north + jitter.next() * wobble, jitter.next() * wobble, t, accuracyM)
                    t += 20_000
                }
            }
        }
        return fixes
    }

    /** Turn density under a caller-chosen bar — the pre-§32.15 rule when handed 30.0. */
    private fun turnDensityUnderBar(fixes: List<CapturedFix>, barM: Double): Double {
        val bearings = ArrayList<Double>()
        var span = 0.0
        for (i in 1 until fixes.size) {
            val a = fixes[i - 1]
            val b = fixes[i]
            if (b.timestampMs <= a.timestampMs) continue
            val legM = capturedDistanceMeters(a, b)
            if (legM >= barM) {
                bearings += bearingDegrees(a, b)
                span += legM
            }
        }
        var turn = 0.0
        for (i in 1 until bearings.size) turn += abs(bearingDeltaDegrees(bearings[i - 1], bearings[i]))
        return if (span <= 0.0) Double.MAX_VALUE else turn / (span / 1000.0)
    }

    // ── the property ───────────────────────────────────────────────────────────

    @Test
    fun aStationaryPlatformJitterLegOnlyEverAddsTurn() {
        // ⚠⚠ The claim the whole fix rests on, isolated from any journey: take a perfectly straight
        // run and splice ONE stationary jitter pair into it. Turn density can only go up, because the
        // sum is over |Δbearing| and a meaningless bearing still contributes its full magnitude.
        val straight = (0..20).map { fix(it * 600.0, 0.0, t0 + it * 20_000L, 64) }
        val withPause = straight.take(10) +
            listOf(
                fix(10 * 600.0 + 40.0, 35.0, t0 + 10 * 20_000L + 5_000, 64),
                fix(10 * 600.0 - 30.0, -45.0, t0 + 10 * 20_000L + 10_000, 64),
            ) +
            straight.drop(10).map { it.copy(timestampMs = it.timestampMs + 20_000) }

        val clean = turnDensityUnderBar(straight, 30.0)
        val noisy = turnDensityUnderBar(withPause, 30.0)
        assertTrue(clean < 1.0, "precondition: the straight run has essentially no turn, was $clean")
        assertTrue(
            noisy > clean + 5.0,
            "one jitter pair must measurably inflate turn density: clean=$clean noisy=$noisy",
        )
        // …and raising the bar above the jitter removes it again, without touching the real legs.
        assertTrue(
            turnDensityUnderBar(withPause, 320.0) < 1.0,
            "a bar above the jitter recovers the straight reading, was ${turnDensityUnderBar(withPause, 320.0)}",
        )
    }

    // ── the journey ────────────────────────────────────────────────────────────

    @Test
    fun aTrainWithRealisticFixQualityIsNoLongerHeldAtDriveByItsOwnNoise() {
        val fixes = stoppingTrain(accuracyM = 64)
        val evidence = assertNotNull(transitEvidence(fixes))

        // Preconditions — every other conjunct passes, exactly as on the reported journey. Without
        // these the test could pass for the wrong reason (a fixture that was never a candidate).
        assertTrue(
            evidence.cruiseSpeedMps >= SHAPE_RULE_MIN_CRUISE_MPS,
            "precondition: train cruise, was ${evidence.cruiseSpeedMps}",
        )
        assertTrue(
            evidence.wellSpacedStopCount >= MIN_STATION_STOPS,
            "precondition: station-spaced stops, was ${evidence.wellSpacedStopCount}",
        )
        // …and the old bar really did fail on this shape, so the fix is what moves the verdict.
        assertTrue(
            turnDensityUnderBar(fixes, TRANSIT_MIN_BEARING_LEG_M) > RAIL_TURN_DENSITY_DEG_PER_KM,
            "precondition: under the old fixed bar this reads road-like, was " +
                "${turnDensityUnderBar(fixes, TRANSIT_MIN_BEARING_LEG_M)} deg/km",
        )

        assertTrue(
            evidence.turnDensityDegPerKm <= RAIL_TURN_DENSITY_DEG_PER_KM,
            "the scaled bar should read this as rail-like, was ${evidence.turnDensityDegPerKm} deg/km",
        )
        assertEquals(
            MovementMode.TRANSIT,
            classifyMode(fixes).mode,
            "turn density was the only failing conjunct",
        )
    }

    @Test
    fun theSameTrainWithGoodFixesWasNeverBrokenAndStaysTransit() {
        // ⚠ The fix must not be load-bearing for the case that already worked — otherwise it is a
        // behaviour swap rather than a repair.
        assertEquals(MovementMode.TRANSIT, classifyMode(stoppingTrain(accuracyM = 6)).mode)
    }

    // ── the adversary the raised bar could plausibly let through ────────────────

    @Test
    fun aRuralDriveWithEQUALLYpoorFixesStillStaysDrive() {
        // ⚠⚠ The real risk of raising the bar: a road's genuine curvature is also measured over longer
        // legs, so a drive gets straighter-looking too. This is the rural-junction adversary from
        // `TransitKinematicsTest` given the SAME ±64 m fixes as the train above — the one shape that
        // has station-like stop spacing and could now clear the turn-density gate.
        val jitter = Jitter()
        val fixes = ArrayList<CapturedFix>()
        var t = t0
        var north = 0.0
        var east = 0.0
        var heading = 0.0
        repeat(4) { leg ->
            repeat(12) {
                fixes += fix(north + jitter.next() * 45.0, east + jitter.next() * 45.0, t, 64)
                // A country road bends continuously — ~1.5° per 200 m segment.
                heading += 1.5
                val step = 13.0 * 15
                north += step * kotlin.math.cos(heading * kotlin.math.PI / 180.0)
                east += step * kotlin.math.sin(heading * kotlin.math.PI / 180.0)
                t += 15_000
            }
            if (leg < 3) {
                repeat(2) {
                    fixes += fix(north + jitter.next() * 45.0, east + jitter.next() * 45.0, t, 64)
                    t += 30_000
                }
            }
        }

        val evidence = assertNotNull(transitEvidence(fixes))
        assertTrue(
            evidence.wellSpacedStopCount >= MIN_STATION_STOPS,
            "precondition: this drive DOES have station-like stop spacing, was " +
                "${evidence.wellSpacedStopCount} — that is what makes it the adversary",
        )
        assertEquals(
            MovementMode.DRIVE,
            classifyMode(fixes).mode,
            "cruise ${evidence.cruiseSpeedMps} m/s, turn ${evidence.turnDensityDegPerKm} deg/km",
        )
    }

    // ── the bar itself ─────────────────────────────────────────────────────────

    @Test
    fun theBarIsTheFloorWhenFixesAreGoodAndScalesWhenTheyAreNot() {
        val good = (0..10).map { fix(it * 100.0, 0.0, t0 + it * 10_000L, 5) }
        val poor = (0..10).map { fix(it * 100.0, 0.0, t0 + it * 10_000L, 64) }
        assertEquals(TRANSIT_MIN_BEARING_LEG_M, bearingLegBarFor(good))
        assertEquals(BEARING_LEG_ACCURACY_FACTOR * 64.0, bearingLegBarFor(poor))
    }

    @Test
    fun unreportedAccuracyKeepsTheFloorRatherThanReadingAsPerfect() {
        // ⚠ 0 means NOT REPORTED, never "0 m" — the convention `FixHygiene.geometryPath` and
        // `confidenceOf` both follow. Treating it as perfect would silently restore the old bar.
        val unknown = (0..10).map { fix(it * 100.0, 0.0, t0 + it * 10_000L, 0) }
        assertEquals(TRANSIT_MIN_BEARING_LEG_M, bearingLegBarFor(unknown))
    }

    @Test
    fun aFewCoarseFixesDoNotDragTheBarUpForTheWholeJourney() {
        // Median, not mean: a tunnel mouth or a cutting produces a handful of 200 m fixes, and those
        // must not raise the bar for a journey that was otherwise well fixed.
        val mostlyGood = (0..20).map { fix(it * 100.0, 0.0, t0 + it * 10_000L, if (it < 3) 200 else 6) }
        assertEquals(TRANSIT_MIN_BEARING_LEG_M, bearingLegBarFor(mostlyGood))
    }
}
