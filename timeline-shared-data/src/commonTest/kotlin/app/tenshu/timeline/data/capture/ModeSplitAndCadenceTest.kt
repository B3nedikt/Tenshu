package app.tenshu.timeline.data.capture

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Device-free coverage of **S2** (mode-change leg splitting) and **S3** (step cadence)
 * — `docs/proposals/timeline-movement-capture.md` §17.3/§17.4/§19.
 *
 * ⚠⚠ Every negative here is twinned against the rule it replaced, expressed purely in **config**
 * (`splitOnModeChange = false` restores pre-S2; `stepsSinceLastFix = null` restores pre-S3), so no
 * assertion can pass by failing to reach the decision and no twin needs a test-only code path.
 */
class ModeSplitAndCadenceTest {

    private val t0 = 1_800_000_000_000L
    private val originLat = 525_000_000L
    private val originLng = 134_000_000L

    private fun latE7(m: Double): Long = (m / 0.011132).roundToLong()
    private fun lngE7(m: Double): Long = (m / (0.011132 * cos(52.5 * PI / 180.0))).roundToLong()

    /** A fix [north] metres up-track at [sec] seconds, with [steps] counted since the previous one. */
    private fun at(north: Double, sec: Int, steps: Int?, accuracy: Int = 10) = CapturedFix(
        latE7 = originLat + latE7(north),
        lngE7 = originLng,
        timestampMs = t0 + sec * 1000L,
        accuracyM = accuracy,
        stepsSinceLastFix = steps,
    )

    /** A run of [n] fixes at [speed] m/s from [fromSec]/[fromNorth], each carrying [steps]. */
    private fun leg(n: Int, speed: Double, fromSec: Int, fromNorth: Double, steps: Int?, everySec: Int = 10) =
        (1..n).map { i ->
            at(fromNorth + speed * everySec * i, fromSec + everySec * i, steps)
        }

    // ── S3: cadence decides pedestrian vs wheeled ──────────────────────────────

    @Test
    fun zeroStepsAtRunningSpeedIsACycleNotARun() {
        // 5 m/s: squarely inside the band where GPS alone cannot tell a runner from a cyclist.
        val stepsPerTenSec = 30 // 180 spm — a real running cadence
        val runner = listOf(at(0.0, 0, null)) + leg(10, 5.0, 0, 0.0, steps = stepsPerTenSec)
        val cyclist = listOf(at(0.0, 0, null)) + leg(10, 5.0, 0, 0.0, steps = 0)

        assertEquals(MovementMode.RUN, classifyMode(runner).mode)
        assertEquals(MovementMode.CYCLE, classifyMode(cyclist).mode)

        // ⚠ The twin that makes this evidence: identical geometry with NO pedometer falls back to the
        // speed-only band, which reads both as the same thing. Cadence is doing the work, not speed.
        val unmeasured = listOf(at(0.0, 0, null)) + leg(10, 5.0, 0, 0.0, steps = null)
        assertEquals(classifyMode(unmeasured).mode, LegMode.speedOnly(5.0))
    }

    @Test
    fun withoutAPedometerClassificationIsExactlyThePreS3Behaviour() {
        // Every speed band, unmeasured — the no-sensor device must lose nothing.
        listOf(1.5 to MovementMode.WALK, 3.0 to MovementMode.RUN, 6.0 to MovementMode.CYCLE, 20.0 to MovementMode.DRIVE)
            .forEach { (speed, expected) ->
                val path = listOf(at(0.0, 0, null)) + leg(10, speed, 0, 0.0, steps = null)
                assertEquals(expected, classifyMode(path).mode, "speed $speed")
            }
    }

    @Test
    fun zeroStepsAtWalkingPaceIsStillAWalk() {
        // ⚠⚠ A phone in a bag misses steps. Zero steps must NOT promote a stroll to a vehicle — only
        // the speed band can promote, and below the cycle floor the honest answer stays "walk".
        val path = listOf(at(0.0, 0, null)) + leg(10, 1.2, 0, 0.0, steps = 0)
        assertEquals(MovementMode.WALK, classifyMode(path).mode)
    }

    @Test
    fun aSlowLegVotesForNothing() {
        // Below the floor, LegMode abstains rather than guessing — which is what keeps a red light
        // from manufacturing a boundary in the splitter.
        assertNull(LegMode.of(speedMps = 0.2, cadenceSpm = 0.0))
        assertNull(LegMode.of(speedMps = 0.2, cadenceSpm = 180.0))
    }

    // ── S3: the §18.6 still-here veto ──────────────────────────────────────────

    @Test
    fun aCountedZeroHoldsAJitteringFixInTheDwellButAnUnknownDoesNot() {
        val config = VisitDetectionConfig()
        val anchor = at(0.0, 0, null, accuracy = 100)
        // 260 m out: past the 200 m accuracy-scaled radius, inside the 2× still-here bound.
        val jitteredWithPedometer = at(260.0, 60, steps = 0, accuracy = 100)
        val jitteredNoPedometer = at(260.0, 60, steps = null, accuracy = 100)

        assertTrue(config.holdsInCluster(anchor, jitteredWithPedometer))
        // ⚠⚠ `null` is not stillness — a device with no pedometer must behave exactly as before.
        assertTrue(!config.holdsInCluster(anchor, jitteredNoPedometer))
    }

    @Test
    fun theStillHereVetoIsBoundedSoADriveStillClosesTheDwell() {
        // ⚠⚠ Zero steps is what a DRIVER reports too. Past the bound the dwell must close, or one
        // stay would swallow an entire journey.
        val config = VisitDetectionConfig()
        val anchor = at(0.0, 0, null, accuracy = 100)
        assertTrue(!config.holdsInCluster(anchor, at(5_000.0, 300, steps = 0, accuracy = 100)))
    }

    @Test
    fun allThreeClusteringsApplyTheVetoIdentically() = runTest {
        // ⚠⚠ The invariant a one-sided veto would have broken silently: the streaming and batch
        // detectors must agree on fixes that actually carry a step count (the pre-existing fixtures
        // all carry `null`, so they could never have caught a divergence here).
        val fixes = buildList {
            add(at(0.0, 0, null, accuracy = 100))
            (1..12).forEach { add(at(if (it % 2 == 0) 240.0 else 0.0, it * 60, steps = 0, accuracy = 100)) }
            add(at(9_000.0, 1_200, steps = 0, accuracy = 100)) // a real departure, by vehicle
        }

        val streaming = fixes.asFlow().segment().toList()
            .filterIsInstance<CaptureEvent.Dwell>().filter { !it.ongoing }.map { it.visit }
        assertEquals(VisitDetector.detect(fixes), streaming)
        assertEquals(fixes.asFlow().detectVisits().toList(), streaming)
        assertEquals(1, streaming.size, "the jitter must read as ONE stay, not six")
    }

    // ── S2: mode-change splitting ──────────────────────────────────────────────

    /** Ride at 20 m/s (no steps), then walk at 1.4 m/s (real cadence) — one continuous path. */
    private fun rideThenWalk(): List<CapturedFix> {
        val ride = listOf(at(0.0, 0, null)) + leg(30, 20.0, 0, 0.0, steps = 0)
        val last = ride.last()
        val startNorth = 20.0 * 300
        val walk = leg(30, 1.4, 300, startNorth, steps = 24) // 144 spm
        return ride + walk.also { require(it.first().timestampMs > last.timestampMs) }
    }

    @Test
    fun aRideFollowedByAWalkBecomesTwoLegs() {
        val runs = ModeSplitter.split(rideThenWalk())

        assertEquals(2, runs.size, "expected the mode change to cut the journey once")
        assertEquals(MovementMode.DRIVE, classifyMode(runs[0]).mode)
        assertEquals(MovementMode.WALK, classifyMode(runs[1]).mode)

        // ⚠ Runs share their boundary fix, so no distance is lost to the cut.
        val whole = rideThenWalk().pathDistanceMeters()
        val summed = runs.sumOf { it.pathDistanceMeters() }
        assertTrue(kotlin.math.abs(whole - summed) < 1.0, "split lost distance: $whole vs $summed")
    }

    @Test
    fun theSameJourneyIsOneCoarseLegUnderThePreS2Rule() {
        // The twin that makes the test above evidence — and the shape of the original report:
        // one row whose single verdict describes neither half.
        val runs = ModeSplitter.split(rideThenWalk(), MovementDetectionConfig(splitOnModeChange = false))
        assertEquals(1, runs.size)
    }

    @Test
    fun aBriefSlowdownDoesNotCutAJourney() {
        // ⚠ Hysteresis: a train braking into a station, or one mis-measured fix, must not split a
        // ride. Two slow legs in the middle of a long drive, then back to speed.
        val path = listOf(at(0.0, 0, null)) +
            leg(15, 20.0, 0, 0.0, steps = 0) +
            leg(2, 1.0, 150, 3_000.0, steps = 0) +
            leg(15, 20.0, 170, 3_020.0, steps = 0)

        assertEquals(1, ModeSplitter.split(path).size)
    }

    @Test
    fun aTooShortRunIsMergedBackRatherThanDropped() {
        // A 40 s walk tacked onto a long drive cannot stand alone (it fails minMoveDurationMs), so it
        // must fold back into the drive — ⚠ never be discarded, which would shorten the journey.
        val path = listOf(at(0.0, 0, null)) +
            leg(30, 20.0, 0, 0.0, steps = 0) +
            leg(4, 1.4, 300, 6_000.0, steps = 24)

        val runs = ModeSplitter.split(path)
        assertEquals(1, runs.size)
        assertEquals(path.size, runs.single().size, "no fix may be lost to a merge")
    }

    @Test
    fun splittingComposesWithTheSegmenterAndKeepsContentAddressingDistinct() = runTest {
        val events = rideThenWalk().asFlow().segment().toList()
        val moves = events.filterIsInstance<CaptureEvent.Move>().map { it.move }

        assertEquals(2, moves.size)
        // ⚠ Distinct startMs ⇒ distinct `cap-move:$startMs` ids ⇒ recovery and idempotence are
        // untouched by the split.
        assertEquals(2, moves.map { it.startMs }.distinct().size)
        assertEquals(listOf(MovementMode.DRIVE, MovementMode.WALK), moves.map { it.classification.mode })
    }
}
