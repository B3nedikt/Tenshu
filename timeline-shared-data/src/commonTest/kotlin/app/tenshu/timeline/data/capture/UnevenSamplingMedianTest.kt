package app.tenshu.timeline.data.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Coverage of the **time-weighted reduction of per-leg speeds** (classifier v14,
 * `timeline-movement-capture.md` §37).
 *
 * ### What this suite is defending
 *
 * `classifyMode` reduces a path to one number and buckets it. Before v14 every leg was one vote
 * regardless of the span it stood for, so the number described *how often the location providers
 * delivered* rather than how the journey went. The shape it breaks on is the one the transit tiers
 * exist for: a stopping train's leg speeds are **bimodal** — standing at a platform, or running at
 * 60–160 km/h, with nothing in between — so the unweighted median lands in the *empty valley*
 * between the two modes, and the valley is the cycle band.
 *
 * ⚠⚠ **The whole cost of getting this wrong is downstream, not here.** `cycle` is neither `drive`
 * nor `transit`, so a trace labelled that way is refused by `refineWithTransitKinematics`, by
 * [RailRefinement.needsRefinement] and by `TimelineProjection.collapseTransitStationStops` alike —
 * one statistic, three closed doors, and the journey renders as several rows of the wrong mode.
 * [aStopAndGoTrainStaysEligibleForTheTransitTiers] therefore asserts the *door*, not just the
 * number; a version that only checked the median would pass while the feed stayed broken.
 *
 * ⚠ [stopAndGoTrain] reproduces the **measured** leg-speed histogram of the 2026-08-31
 * Mühldorf → München Ost capture — 33 legs: 12 under 0.6 m/s, 2 in 0.6–2.5, 1 in 2.5–4, **2** in
 * 4–8, 4 in 8–16, 12 at 16+ — rather than being tuned until green. The distribution *is* the
 * finding, and [theCycleVerdictIsAnArtefactOfCountingLegsRatherThanTime] pins that the fixture
 * still reproduces it, so this suite cannot quietly decay into a tautology.
 *
 * Geometry is synthesised along a straight north line in metres: ~89.83 E7 of latitude ≈ 1 m.
 */
class UnevenSamplingMedianTest {

    private val t0 = 1_800_000_000_000L
    private val originLat = 525_000_000L
    private val originLng = 134_000_000L
    private val e7PerMetreLat = 89.83

    private fun fix(northM: Double, atMs: Long, accuracyM: Int = 8) = CapturedFix(
        latE7 = originLat + (northM * e7PerMetreLat).toLong(),
        lngE7 = originLng,
        timestampMs = atMs,
        accuracyM = accuracyM,
    )

    /**
     * Emit fixes along a straight line from a `(durationSec, speedMps)` schedule — one fix per leg
     * boundary, so a leg's *weight* is explicit in the fixture rather than implied by a fix count.
     *
     * That explicitness is the point: these tests are about legs of unequal duration, and a fixture
     * that expressed duration as "how many fixes" could not state the case at all.
     */
    private fun path(schedule: List<Pair<Long, Double>>, accuracyM: Int = 8): List<CapturedFix> {
        val fixes = ArrayList<CapturedFix>(schedule.size + 1)
        var t = t0
        var north = 0.0
        fixes += fix(north, t, accuracyM)
        for ((durationSec, speedMps) in schedule) {
            north += speedMps * durationSec
            t += durationSec * 1000
            fixes += fix(north, t, accuracyM)
        }
        return fixes
    }

    private fun run(vararg durationSec: Long) = durationSec.map { it to 30.0 }

    /** brake → stand → accelerate: a platform stop, its deceleration spread across bands. */
    private fun station() = buildList {
        add(25L to 12.0)
        add(20L to 5.5)
        repeat(4) { add(20L to 0.0) }
        add(20L to 1.5)
        add(25L to 12.0)
    }

    /**
     * A stop the fixes only *describe* as one: four cached readings, then straight back to speed.
     *
     * ⚠ Not narrative colour. On the real capture three consecutive fixes were the byte-identical
     * cached network position, two minutes apart, while the train was running at 90 km/h — there is
     * no braking or acceleration leg because nothing was actually measured. Nothing downstream can
     * tell this from a genuine stop; it votes as stopped legs either way, which is exactly why the
     * weighting has to be what answers it.
     */
    private fun staleFixPlateau() = List(4) { 120L to 0.0 } + listOf(20L to 3.5)

    /**
     * A 38.5 km regional train over 34 minutes: four fast runs, two platform stops and one stale
     * plateau — 33 legs whose speed histogram is the measured one (12 / 2 / 1 / **2** / 4 / 12).
     */
    private fun stopAndGoTrain(): List<CapturedFix> = path(
        buildList {
            addAll(run(180, 180, 25))
            addAll(station())
            addAll(run(180, 25))
            addAll(station())
            addAll(run(180, 180, 25))
            addAll(staleFixPlateau())
            addAll(run(180, 25, 25, 25))
        },
    )

    // ── the defect this shipped for ──────────────────────────────────────────────

    @Test
    fun aStopAndGoTrainStaysEligibleForTheTransitTiers() {
        val result = classifyMode(stopAndGoTrain())

        // The statistic. Unweighted, the middle of this sample is a 5.5 m/s braking leg — squarely
        // `cycle`, and only 2 of the 33 legs are anywhere near it. Weighted by duration the answer
        // is the 30 m/s cruise the train actually spent its time doing.
        assertTrue(
            result.medianSpeedMmPerS >= 8_000,
            "expected the drive band (>= 8 m/s); got ${result.medianSpeedMmPerS} mm/s — the empty " +
                "valley between standing still and running, see §37",
        )
        assertNotEquals(MovementMode.CYCLE, result.mode)

        // The door. This is what the median was actually costing: a `cycle` verdict is not a
        // candidate for the rail map-match at all, so the one tier that could settle the question
        // from the map never looks. On the reported journey the device log read
        // "rail sweep: 0 candidate(s) of 15 trace(s)".
        assertTrue(
            RailRefinement.needsRefinement(result),
            "a stop-and-go train must reach the rail sweep; ${result.mode} never does",
        )
        assertFalse(
            RailRefinement.needsRefinement(result.copy(mode = MovementMode.CYCLE)),
            "guard check: `cycle` really is excluded, so the assertion above is load-bearing",
        )
    }

    @Test
    fun theCycleVerdictIsAnArtefactOfCountingLegsRatherThanTime() {
        // Pins that the fixture still reproduces the measured defect. Without this, a later edit
        // could flatten the distribution and the test above would pass for the wrong reason.
        val legs = LegMode.legsOf(stopAndGoTrain())
        val unweighted = legs.map { it.speedMps }.sorted()
        val middle = unweighted[unweighted.size / 2]

        assertEquals(
            MovementMode.CYCLE,
            LegMode.speedOnly(middle),
            "one-vote-per-leg should still land in the cycle band; middle was $middle m/s",
        )
        val inCycleBand = legs.count { it.speedMps >= 2.5 && it.speedMps < 8.0 }
        assertTrue(
            inCycleBand * 8 < legs.size,
            "the band the median lands in should be nearly unoccupied — that is the whole finding; " +
                "$inCycleBand of ${legs.size} legs",
        )
    }

    // ── the properties that keep it safe ─────────────────────────────────────────

    @Test
    fun equalWeightsReduceToTheOrdinaryMedian() {
        // A uniformly sampled path must classify exactly as it always did, or every calibration
        // behind MODE_BOUNDARIES silently moved. Even leg count on purpose: the ordinary median
        // averages the two middle values, and a weighted median that merely picked the lower one
        // would pass an odd-count test and drift here.
        val speeds = listOf(1.0, 2.0, 9.0, 30.0)
        val expected = (speeds.sorted()[1] + speeds.sorted()[2]) / 2.0 // 2.0 and 9.0 → 5.5

        val median = classifyMode(path(speeds.map { 60L to it })).medianSpeedMmPerS
        assertTrue(
            median in ((expected * 1000).toLong() - 60)..((expected * 1000).toLong() + 60),
            "expected ~${expected * 1000} mm/s, got $median",
        )
    }

    @Test
    fun aShortLegSpikeCannotMoveTheVerdict() {
        // The original reason the reduction is a median at all (M1 §5), and the realistic shape of
        // a GPS spike: a wild *displacement* delivered between two closely-spaced fixes. Weighting
        // strengthens this rather than weakening it — the spike's own brevity is now what discounts
        // it, on top of it being a single observation.
        val walkWithSpike = path(
            listOf(
                60L to 1.4,
                60L to 1.5,
                9L to 850.0, // ~7.6 km between two fixes nine seconds apart
                60L to 1.3,
                60L to 1.5,
            ),
        )
        assertEquals(MovementMode.WALK, classifyMode(walkWithSpike).mode)
    }

    @Test
    fun theNoisierShortLegLosesToTheQuieterLongOne() {
        // ⚠⚠ The justification for weighting by duration, stated as a test. A leg's speed error is
        // the fix position error over its span, so it scales as 1/dt: the *same* 400 m of position
        // error reads as 44 m/s across 9 s and 0.7 m/s across 600 s. Duration weighting is
        // therefore approximately inverse-variance weighting — the long legs are not merely longer,
        // they are the more precise measurements, and the reduction must prefer them.
        val truth = 1.4
        val errorM = 400.0
        val noisy = path(
            listOf(
                9L to truth + errorM / 9,     // ~46 m/s — the same error, over nine seconds
                600L to truth + errorM / 600, // ~2.1 m/s — the same error, over ten minutes
                9L to truth + errorM / 9,
                600L to truth,
                9L to truth + errorM / 9,
            ),
        )
        assertEquals(
            MovementMode.WALK,
            classifyMode(noisy).mode,
            "three of five legs read as a vehicle, but they are the three that measured nothing",
        )
    }

    @Test
    fun aCyclistWaitingAtTrafficLightsIsStillACyclist() {
        // The mirror of the train, and the case that says this is a fix rather than a thumb on the
        // scale toward "fast". A cyclist stops too; weighting by time simply stops the stops from
        // outvoting the riding, and riding is what the row should say.
        val ride = path(
            buildList {
                repeat(4) {
                    add(120L to 5.5) // two minutes of riding at ~20 km/h
                    add(40L to 0.0) // a red light
                }
                add(120L to 5.5)
            },
        )
        assertEquals(MovementMode.CYCLE, classifyMode(ride).mode)
    }

    @Test
    fun aWalkWithAStarvedGapIsStillAWalk() {
        // The direction that would be a regression. Duration weighting gives a long leg more say,
        // and a long leg is exactly where a Doze-starved gap sits — so the gap must not be able to
        // carry a walk out of its band. It cannot, because a straight line across a gap *under*
        // reports a wandering path: the bias runs toward the slower verdict, which is the safe one.
        val walk = path(
            listOf(
                30L to 1.4,
                30L to 1.5,
                600L to 1.1, // ten minutes of Doze, measured end to end
                30L to 1.3,
                30L to 1.5,
            ),
        )
        assertEquals(MovementMode.WALK, classifyMode(walk).mode)
    }
}
