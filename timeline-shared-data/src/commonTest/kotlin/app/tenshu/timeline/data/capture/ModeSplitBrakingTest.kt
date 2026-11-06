package app.tenshu.timeline.data.capture

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §35.7.3 — **a train slowing into an intermediate station must not cut the ride.**
 *
 * Measured over six real commuting days (§35): a Mühldorf → München Ost train came back as
 * `drive 31 km | transit 26 km | walk 0.44 km @ 8.4 km/h | drive 23 km`. Every stage behaved as
 * designed — [LegMode] honestly calls walking pace with no steps a `WALK`, and [ModeCoalescer]
 * honestly refuses to re-join runs whose refined verdicts differ — and the journey still became
 * four rows.
 *
 * ⚠⚠ **The fix is a boundary rule, not a vote rule, and the difference was measured.** Making the
 * braking leg *abstain* in [LegMode.of] was tried first: it improved three days and split the one
 * day the pipeline already read perfectly, because abstention re-groups the hysteresis runs rather
 * than suppressing a single boundary (§35.7.2). Suppressing the boundary is **monotone** — a day
 * with nothing to absorb is byte-identical — and that property is what these tests pin.
 */
class ModeSplitBrakingTest {

    private val t0 = 1_700_000_000_000L
    private val originLat = 481_400_000L
    private val originLng = 115_800_000L

    private fun lngE7(m: Double): Long = (m / (0.011132 * cos(48.14 * PI / 180.0))).roundToLong()

    private fun fix(eastM: Double, sec: Int, steps: Int?) = CapturedFix(
        latE7 = originLat,
        lngE7 = originLng + lngE7(eastM),
        timestampMs = t0 + sec * 1000L,
        accuracyM = 20,
        stepsSinceLastFix = steps,
    )

    /** A stretch at [speedMps], one fix every 20 s, with [stepsPerFix] counted on each. */
    private fun stretch(
        fromSec: Int,
        fromEast: Double,
        ticks: Int,
        speedMps: Double,
        stepsPerFix: Int?,
    ) = (0 until ticks).map { i ->
        fix(fromEast + i * speedMps * 20.0, fromSec + i * 20, stepsPerFix)
    }

    /** 22 m/s ≈ 80 km/h — a regional train, and no steps counted. */
    private fun cruise(fromSec: Int, fromEast: Double, ticks: Int) =
        stretch(fromSec, fromEast, ticks, speedMps = 22.0, stepsPerFix = 0)

    /** 2.3 m/s ≈ 8 km/h for ~3 minutes, and the pedometer counts **zero**. */
    private fun brakeIntoStation(fromSec: Int, fromEast: Double) =
        stretch(fromSec, fromEast, ticks = 9, speedMps = 2.3, stepsPerFix = 0)

    private fun modesOf(path: List<CapturedFix>): List<String> =
        ModeSplitter.split(path).mapNotNull { it.toCapturedMoveOrNull(MovementDetectionConfig())?.mode }

    @Test
    fun aTrainBrakingIntoAStationStaysOneLeg() {
        val cruiseA = cruise(fromSec = 0, fromEast = 0.0, ticks = 40)
        val brake = brakeIntoStation(fromSec = 800, fromEast = 17_600.0)
        val cruiseB = cruise(fromSec = 980, fromEast = 18_014.0, ticks = 40)

        val runs = ModeSplitter.split(cruiseA + brake + cruiseB)

        // ⚠ The whole point: one ride, one run. Before this rule the brake opened a boundary either
        // side of itself and the ride came back in three pieces.
        assertEquals(1, runs.size, "modes were ${modesOf(cruiseA + brake + cruiseB)}")
    }

    @Test
    fun aRealWalkBetweenTwoDrivesSurvives() {
        // ⚠⚠ The conjunct that keeps the rule honest. Same geometry, same speeds — but the
        // pedometer COUNTED, so this is a person crossing a car park between two drives and the
        // boundary must stand. `steps == 0` is the claim "nobody walked"; anything above it is not.
        val walked = stretch(fromSec = 800, fromEast = 17_600.0, ticks = 9, speedMps = 2.3, stepsPerFix = 40)
        val path = cruise(0, 0.0, 40) + walked + cruise(980, 18_014.0, 40)

        assertTrue(ModeSplitter.split(path).size > 1, "a counted walk between two drives is a leg")
    }

    @Test
    fun aStretchWithNoPedometerAtAllIsUntouched() {
        // ⚠⚠ `null` is not `0`. No pedometer means the speed-only fallback ran and this evidence
        // does not exist — absorbing on its absence would silently widen the rule to every device
        // without a step sensor, and to every corpus recorded before S3.
        val blind = stretch(fromSec = 800, fromEast = 17_600.0, ticks = 9, speedMps = 2.3, stepsPerFix = null)
        val path = cruise(0, 0.0, 40) + blind + cruise(980, 18_014.0, 40)

        assertTrue(ModeSplitter.split(path).size > 1, "no pedometer ⇒ no absorption")
    }

    @Test
    fun aBrakeAtTheStartOfAFlushIsNotAbsorbed() {
        // ⚠ Interior only. The information that resolves a braking leg is what surrounds it; the
        // same leg leading a flush is someone leaving a building, and there is no second neighbour
        // to vouch for it.
        val path = brakeIntoStation(fromSec = 0, fromEast = 0.0) + cruise(180, 414.0, 40)

        assertTrue(ModeSplitter.split(path).size > 1, "a leading slow leg must stand on its own")
    }

    // ── §36.8: the station stop — fast on average, and stationary in the middle ──────────────

    /**
     * One journey, segment by segment: `(ticks, speed m/s, steps per fix)`, one fix every 20 s.
     *
     * ⚠ Built by accumulation rather than by hand-computed offsets, because this shape's whole
     * point is where the *splitter* chooses to cut, and an arithmetic slip in a start position would
     * quietly move the boundary and make the test pass for a reason nobody intended.
     */
    private fun journey(vararg segments: Triple<Int, Double, Int?>): List<CapturedFix> {
        var sec = 0
        var east = 0.0
        val out = ArrayList<CapturedFix>()
        for ((ticks, speedMps, steps) in segments) {
            repeat(ticks) {
                out += fix(east, sec, steps)
                east += speedMps * 20.0
                sec += 20
            }
        }
        return out
    }

    /** 22 m/s ≈ 80 km/h, no steps — the same regional train as [cruise]. */
    private fun cruising(steps: Int? = 0) = Triple(40, 22.0, steps)

    /**
     * Decelerating **through** the station area at 5 m/s ≈ 18 km/h. Above [LegMode.RUN_MIN_SPEED_MPS]
     * so it votes `CYCLE` rather than `WALK` — the vote only has to *differ* from `drive` to open the
     * boundary — and fast enough that the run it lands in carries a mean the brake rule cannot claim.
     */
    private fun decelerating(ticks: Int = 6, steps: Int? = 0) = Triple(ticks, 5.0, steps)

    /** Standing at the platform: 0.15 m/s, i.e. fix noise. */
    private fun standing(ticks: Int = 4, steps: Int? = 0) = Triple(ticks, 0.15, steps)

    /**
     * ⚠⚠ The run this produces has a mean of **3.1 m/s — above [LegMode.RUN_MIN_SPEED_MPS]** — so
     * `isBraking`'s own conjunct positively refuses it, and it is absorbed only because it
     * [ModeSplitter.holdsAStandstill]. Verified by deleting the disjunct: this path comes back as
     * **three** runs without it (§36.8). That is the difference between this test and
     * [aTrainBrakingIntoAStationStaysOneLeg], which the brake rule alone already covered.
     */
    @Test
    fun aTrainThatStandsAtAStationStaysOneLeg() {
        val path = journey(cruising(), decelerating(), standing(), cruising())

        assertEquals(1, ModeSplitter.split(path).size, "modes were ${modesOf(path)}")
    }

    @Test
    fun aStationStopWithCountedStepsIsNotAbsorbed() {
        // ⚠⚠ The same conjunct that keeps the brake rule honest, on the new shape: a hard zero is the
        // claim "nobody walked". Someone who got off and back on took steps, and their stop is real.
        val path = journey(
            cruising(), decelerating(steps = 40), standing(steps = 40), cruising(),
        )

        assertTrue(ModeSplitter.split(path).size > 1, "a counted stop is a leg of its own")
    }

    @Test
    fun aStationStopPastTheBrakeCapIsNotAbsorbed() {
        // ⚠ [MAX_BRAKING_MS] bounds BOTH shapes. Standing for six minutes is not a train pausing at a
        // platform; it is a stop, and a stop is a row.
        val path = journey(cruising(), decelerating(), standing(ticks = 12), cruising())

        assertTrue(ModeSplitter.split(path).size > 1, "a six-minute stand is not a station stop")
    }

    @Test
    fun aStandstillIsMeasuredOnDisplacementNotPathDistance() {
        // ⚠⚠ The property the whole rule rests on. A stationary phone's fixes jitter, so the SUMMED
        // path of a standstill is not zero — here it is over a kilometre — while the displacement
        // between the window's ends is metres. Reading path distance would refuse every real
        // standstill on coarse fixes, which is exactly the case §36 is about.
        val jitter = (0 until 10).map { i ->
            fix(eastM = if (i % 2 == 0) 0.0 else 140.0, sec = 800 + i * 20, steps = 0)
        }
        assertTrue(ModeSplitter.holdsAStandstill(jitter), "a jittering phone is standing still")
    }

    @Test
    fun aWindowShorterThanTheMinimumIsNotAStandstill() {
        // ⚠ 40 s of standing is a red light within a leg, not evidence about the leg. The floor is
        // what stops this rule reading every momentary pause as a station.
        val brief = (0 until 3).map { i -> fix(eastM = 0.0, sec = 800 + i * 20, steps = 0) }
        assertTrue(!ModeSplitter.holdsAStandstill(brief), "40 s is under the floor")
    }

    @Test
    fun aStretchThatKeepsMovingIsNeverAStandstill() {
        assertTrue(
            !ModeSplitter.holdsAStandstill(cruise(fromSec = 800, fromEast = 0.0, ticks = 10)),
            "80 km/h is not standing still",
        )
    }

    @Test
    fun aLongSlowStretchIsNotABrake() {
        // ⚠ Ten minutes below the run floor is not a train easing into a platform, whatever the
        // pedometer says — it is a stop, a jam, or a different journey. The cap is what stops this
        // rule swallowing one.
        val long = stretch(fromSec = 800, fromEast = 17_600.0, ticks = 31, speedMps = 2.3, stepsPerFix = 0)
        val path = cruise(0, 0.0, 40) + long + cruise(1_420, 19_000.0, 40)

        assertTrue(ModeSplitter.split(path).size > 1, "a 10-minute crawl is not a brake")
    }
}
