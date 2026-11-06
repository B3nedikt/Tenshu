package app.tenshu.timeline.data.capture

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **§34.2 — the walk to the platform is not part of the ride.**
 *
 * The defect: a U5 hop rendered as `Walk · 4.9 km · 11 min` — 27 km/h on foot. [ModeSplitter] votes on
 * speed, and underground there is none to read, so the walk and the ride stayed one leg and every
 * transit-gated tier below was skipped.
 *
 * ⚠⚠ **Every fixture here is shaped to the measured 2026-08-14 ride**, whose proportions are what make
 * the negatives negatives: fixes arrive in GPS/NETWORK **pairs seconds apart at zero displacement**
 * (§21.3), the anchor is pinned for 7½ minutes, and the only movement it records is two cell handoffs
 * of 1 767 m and 944 m that read as 87 and 42 m/s. A fixture without the duplicate pairs cannot catch
 * the median failure, and one without the handoffs cannot catch the spurious speed boundary.
 *
 * ⚠ Twinned in **config** throughout (`splitOnPinnedAnchor = false` restores the pre-§34 splitter
 * exactly), so no assertion can pass by failing to reach the decision.
 */
class ModeSplitUndergroundTest {

    private val t0 = 1_800_000_000_000L
    private val originLat = 481_400_000L
    private val originLng = 115_800_000L

    private fun lngE7(m: Double): Long = (m / (0.011132 * cos(48.14 * PI / 180.0))).roundToLong()

    private fun fix(eastM: Double, sec: Int, accuracyM: Int) = CapturedFix(
        latE7 = originLat,
        lngE7 = originLng + lngE7(eastM),
        timestampMs = t0 + sec * 1000L,
        accuracyM = accuracyM,
    )

    /**
     * One sampling tick as the device really delivers it: **two** fixes 4 s apart at the same place,
     * one per provider. ⚠ The duplicate is the fixture's whole point — see the class KDoc.
     */
    private fun tick(eastM: Double, sec: Int, accuracyM: Int) =
        listOf(fix(eastM, sec, accuracyM), fix(eastM, sec + 4, accuracyM - 6))

    /** A GPS-visible walk: fine fixes stepping [stepM] every 20 s. */
    private fun walk(fromSec: Int, fromEast: Double, ticks: Int, stepM: Double = 26.0) =
        (0 until ticks).flatMap { tick(fromEast + it * stepM, fromSec + it * 20, accuracyM = 20) }

    /** The stairwell: coarse now, but the anchor is still marching as the walk continues. */
    private fun descend(fromSec: Int, fromEast: Double, ticks: Int, stepM: Double = 70.0) =
        (0 until ticks).flatMap { tick(fromEast + it * stepM, fromSec + it * 20, accuracyM = 100) }

    /** The tunnel: coarse, and the anchor does not move at all. */
    private fun pinned(fromSec: Int, atEast: Double, ticks: Int) =
        (0 until ticks).flatMap { tick(atEast, fromSec + it * 20, accuracyM = 100) }

    /**
     * walk (fine) → stairwell (coarse, marching) → tunnel (coarse, pinned) with one cell handoff
     * partway, then the arrival platform. The measured shape of the reported ride.
     */
    private fun walkThenHop(): List<CapturedFix> =
        walk(fromSec = 0, fromEast = 0.0, ticks = 6) +
            descend(fromSec = 120, fromEast = 160.0, ticks = 4) +
            pinned(fromSec = 200, atEast = 440.0, ticks = 12) +
            pinned(fromSec = 440, atEast = 2210.0, ticks = 6) +
            pinned(fromSec = 560, atEast = 4500.0, ticks = 5)

    private fun boundaryTimes(path: List<CapturedFix>, config: MovementDetectionConfig): List<Int> =
        ModeSplitter.split(path, config).drop(1).map { ((it.first().timestampMs - t0) / 1000).toInt() }

    /**
     * The rows the whole flush pipeline actually writes — split, then the §3 thresholds, then
     * classification, then §27's promotion, then §32.2's coalescing.
     *
     * ⚠⚠ Asserting on [ModeSplitter.split] alone is not enough and that is measured, not cautious: a
     * boundary that produces a sub-threshold run is folded away by `mergeUnviable`, and two runs that
     * classify alike are re-joined by [ModeCoalescer] — so a *count of runs* is blind to exactly the
     * failures §34 is about. The damage always shows up here, one stage later, as a row.
     */
    private fun rows(path: List<CapturedFix>, config: MovementDetectionConfig = MovementDetectionConfig()) =
        ModeCoalescer.coalesce(ModeSplitter.split(path, config)) { candidate ->
            candidate.toCapturedMoveOrNull(config)?.let { move ->
                if (move.classification.mode != MovementMode.TRANSIT && UndergroundHop.promotes(move.fixes, path)) {
                    move.copy(
                        classification = move.classification.copy(
                            mode = MovementMode.TRANSIT,
                            version = MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND,
                        ),
                    )
                } else {
                    move
                }
            }
        }

    @Test
    fun theWalkToThePlatformIsCutOffFromTheRide() {
        val path = walkThenHop()
        val rows = rows(path)

        assertEquals(2, rows.size, "the walk and the ride are two rows, not one")
        assertEquals(MovementMode.WALK, rows[0].classification.mode)
        assertEquals(MovementMode.TRANSIT, rows[1].classification.mode)
        assertEquals(
            MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND,
            rows[1].classification.version,
            "the ride must carry §27's stamp, or the tile tier can never demote it",
        )
        // ⚠ The cut lands in the tunnel's first minutes, not at the stairwell: the anchor is what is
        // being read, and in the stairwell it is still marching 70 m a tick.
        val cutSec = (rows[1].startMs - t0) / 1000
        assertTrue(cutSec in 200..320, "cut at ${cutSec}s — expected the pinning onset, ~200–320 s")
        // The walk is ~160 m of pavement plus the stairwell; if the cut slipped past the first handoff
        // this would carry kilometres of tunnel.
        assertTrue(rows[0].distanceM < 800.0, "the walk row carries ${rows[0].distanceM} m of tunnel")

        // ⚠⚠ The twin that makes this evidence rather than a shape: the same path under the pre-§34
        // rule is ONE row, mislabelled — which is exactly the reported defect.
        val before = rows(path, MovementDetectionConfig(splitOnPinnedAnchor = false))
        assertEquals(1, before.size, "pre-§34 the speed vote sees nothing here — that is the bug")
        assertTrue(before.single().distanceM > 4_000.0, "…and it is the whole 4.5 km as one row")
    }

    @Test
    fun aCellHandoffInsideTheTunnelDoesNotCutTheRideInHalf() {
        // ⚠⚠ The regression this guards. The handoff legs vote `drive` at tens of m/s, and three in a
        // row open a speed boundary mid-ride. What makes that fatal rather than untidy is what the far
        // half then holds: ZERO displacement, every fix the same pinned coordinate. It fails the §3
        // thresholds, `mergeUnviable` folds it back into the walk — which the tunnel fixes then make
        // read *frozen* — and the whole hop reassembles as one promoted row with the walk inside it.
        val path = walkThenHop() +
            pinned(fromSec = 660, atEast = 6800.0, 4) +
            pinned(fromSec = 760, atEast = 9100.0, 4) +
            pinned(fromSec = 860, atEast = 11_400.0, 4)

        val rows = rows(path)
        assertEquals(2, rows.size, "one journey is a walk and a ride, whatever the handoffs voted")
        assertEquals(MovementMode.WALK, rows[0].classification.mode)
        assertTrue(rows[0].distanceM < 800.0, "the walk row carries ${rows[0].distanceM} m of tunnel")
    }

    @Test
    fun aLongPlatformWaitIsNotATunnel() {
        // ⚠⚠ The decisive control, and the reason the anchor test alone cannot be the predicate: a
        // train waiting at a covered platform is blind AND pinned for as long as the wait lasts. On the
        // real 2026-08-12 Mühldorf train a stop of exactly `modeChangeHysteresisLegs` pinned legs cut a
        // 58 km journey into `Drive · 26.6 km` + `Transit · 32.9 km`, and on the 08-14 train the same
        // shape opened a boundary at 10:41:50.
        //
        // What refuses it is that the blackout **went nowhere**: its displacement is zero, so no COARSE
        // interval clears the 800 m bar and there is no ride to separate from anything.
        val path = road(fromSec = 0, fromEast = 0.0, ticks = 12) +
            pinned(fromSec = 240, atEast = 4_400.0, ticks = 18) +
            road(fromSec = 620, fromEast = 4_400.0, ticks = 12)

        assertEquals(
            emptyList(),
            boundaryTimes(path, MovementDetectionConfig()),
            "a wait is not a ride; the journey must stay one leg",
        )
    }

    /** A road leg with GPS: fine fixes marching [stepM] every 20 s. */
    private fun road(fromSec: Int, fromEast: Double, ticks: Int, stepM: Double = 400.0) =
        (0 until ticks).flatMap { tick(fromEast + it * stepM, fromSec + it * 20, accuracyM = 20) }

    @Test
    fun aTrainHeldOnOneCellMastIsNotAMetro() {
        // ⚠⚠ §34.5, a regression this file shipped. A surface train in open country holds ONE cell
        // mast for minutes at a time, so its coarse fixes repeat and the anchor reads pinned — exactly
        // like a tunnel. The blackout is not: measured on the 2026-08-15 Mühldorf → München Ost ride,
        // it covered 7 528 m in 339 s — 22.2 m/s, 80 km/h, which no metro does.
        //
        // ⚠⚠ What let it through is that `BlindInterval.impliedSpeedMps` describes the longest
        // sub-span inside 4–15 m/s, not the interval: `travellingSpan` reported 1 082 m @ 12.9 m/s and
        // the gate passed. The ride was cut, both halves were then too short for `TransitKinematics`
        // to read a train, both classified `drive`, and the journey rendered as
        // `Drive · 9.4 km` + `Transit · 69.6 km` — which the read-time fold cannot join across.
        //
        // The fixture reproduces that structure: an anchor that repeats (so the median step is 0) and
        // three cell handoffs that carry it 7.5 km in ~5½ minutes.
        val hop = { from: Int, east: Double -> pinned(fromSec = from, atEast = east, ticks = 6) }
        val path = road(fromSec = 0, fromEast = -4_000.0, ticks = 10) +
            hop(200, 0.0) + hop(320, 2_500.0) + hop(440, 5_000.0) + hop(560, 7_500.0)

        // The trap, pinned as a measurement so a future reader sees why the naive gate passes.
        val blind = BlindIntervalDetector.detect(path, minFineFixShare = 0.0)
            .single { it.kind == BlindIntervalDetector.Kind.COARSE }
        assertTrue(
            blind.impliedSpeedMps <= BlindIntervalDetector.MAX_METRO_SPEED_MPS,
            "precondition: the trimmed sub-span reads metro-paced (${blind.impliedSpeedMps} m/s) — " +
                "if it did not, this fixture would not exercise the guard at all",
        )

        assertEquals(
            emptyList(),
            boundaryTimes(path, MovementDetectionConfig()),
            "a train at 80 km/h is not an underground hop, whatever its anchor did",
        )
    }

    @Test
    fun aBlackoutThatBeginsWithTheFlushOpensNoBoundary() {
        // ⚠⚠ A hop that starts the moment the platform dwell closes has no surface travel in the path
        // to cut off — and [pinnedWindow] is truncated at the path start, so the ride's own first
        // minutes are judged on a window too short to be frozen and read UNPINNED. The first pinned
        // run is therefore the ride's TAIL, and cutting there is cutting the ride. Measured on 08-11
        // that turned a correctly promoted `Transit · 2.8 km` into `Drive · 3.0 km` + `Walk · 645 m`.
        //
        // ⚠ The prefix here is long enough to stand as its own run (840 m over four minutes), so the
        // assertion is not resting on the §3 thresholds folding it away — the boundary is refused
        // because there is no *surface* travel in it, not because it would be too small to keep.
        val path = descend(fromSec = 0, fromEast = 0.0, ticks = 12) +
            pinned(fromSec = 240, atEast = 840.0, ticks = 10) +
            pinned(fromSec = 440, atEast = 2900.0, ticks = 8) +
            pinned(fromSec = 600, atEast = 5000.0, ticks = 6)

        val rows = rows(path)
        assertEquals(1, rows.size, "the whole flush is the ride; there is nothing in it to cut off")
        assertEquals(MovementMode.TRANSIT, rows.single().classification.mode)
        assertEquals(
            MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND,
            rows.single().classification.version,
            "and §27 must still promote it whole, exactly as it did pre-§34",
        )
    }

    @Test
    fun theKillSwitchRestoresTheSpeedOnlySplitterExactly() {
        // Not just on the underground path: an ordinary journey must be byte-identical either way, or
        // the flag is not a kill switch.
        val road = walk(fromSec = 0, fromEast = 0.0, ticks = 12) +
            (0 until 12).flatMap { tick(300.0 + it * 300.0, 240 + it * 20, accuracyM = 20) }

        assertEquals(
            ModeSplitter.split(road, MovementDetectionConfig(splitOnPinnedAnchor = false)).map { it.size },
            ModeSplitter.split(road).map { it.size },
            "the pinned detector must not touch a path with no blackout in it",
        )
    }
}
