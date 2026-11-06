package app.tenshu.timeline.data.capture

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **S9 — a journey the splitter cut and classification then agreed about is one leg again**
 * (`timeline-movement-capture.md` §32.2).
 *
 * The reported shape, 2026-08-12: an underground hop recorded as `Transit · 1.8 km` immediately
 * followed by `Transit · 1.2 km`, because [ModeSplitter] cut on raw [LegMode] bands (2.54 vs
 * 0.18 m/s) that `UndergroundHop` then overruled on both sides.
 *
 * ⚠⚠ Every join case here is twinned with the disagreement that must still cut, because a coalescer
 * that joins unconditionally would pass a join-only suite while destroying S2 — and S2's whole
 * purpose is that train → walk is two rows.
 */
class ModeCoalescerTest {

    private val t0 = 1_800_000_000_000L
    private val originLat = 481_400_000L
    private val originLng = 115_800_000L

    private fun lngE7(m: Double): Long = (m / (0.011132 * cos(48.14 * PI / 180.0))).roundToLong()

    private fun fix(eastM: Double, sec: Int, accuracyM: Int = 10) = CapturedFix(
        latE7 = originLat,
        lngE7 = originLng + lngE7(eastM),
        timestampMs = t0 + sec * 1000L,
        accuracyM = accuracyM,
    )

    /** A run of [n] fixes, so runs can be built without caring about their geometry. */
    private fun run(n: Int, fromSec: Int, fromEast: Double, speed: Double, everySec: Int = 60) =
        (0 until n).map { fix(fromEast + speed * everySec * it, fromSec + everySec * it) }

    /**
     * A stand-in for the flush pipeline: any path of ≥3 fixes becomes a move labelled [mode], shorter
     * ones are unrecordable. Substituting it is what keeps these cases about the *coalescing rule*
     * rather than about which fixtures happen to trip the real classifier — the end-to-end case at
     * the bottom covers the real pipeline.
     */
    private fun moveOf(path: List<CapturedFix>, mode: String): CapturedMove? {
        if (path.size < 3) return null
        return CapturedMove(
            fixes = path,
            startMs = path.first().timestampMs,
            endMs = path.last().timestampMs,
            distanceM = path.pathDistanceMeters(),
            classification = ModeClassification(mode = mode, confidence = 50, medianSpeedMmPerS = 0),
        )
    }

    /** Every path classifies the same way — the "the split bought nothing" shape. */
    private fun always(mode: String): (List<CapturedFix>) -> CapturedMove? = { moveOf(it, mode) }

    // ── the rule ───────────────────────────────────────────────────────────────

    @Test
    fun adjacentRunsThatClassifyTheSameBecomeOneLeg() {
        val a = run(4, 0, 0.0, 5.0)
        val b = run(4, 180, 900.0, 5.0)
        val out = ModeCoalescer.coalesce(listOf(a, b), always(MovementMode.TRANSIT))
        assertEquals(1, out.size, "one ride, one row")
        assertEquals(a.first().timestampMs, out.single().startMs)
        assertEquals(b.last().timestampMs, out.single().endMs)
    }

    @Test
    fun adjacentRunsThatClassifyDifferentlyStayCut() {
        // ⚠⚠ The twin. S2 exists so train → walk is two rows; joining here would delete it.
        val a = run(4, 0, 0.0, 20.0)
        val b = run(4, 180, 3600.0, 1.2)
        var call = 0
        val out = ModeCoalescer.coalesce(listOf(a, b)) { path ->
            moveOf(path, if (call++ == 0) MovementMode.TRANSIT else MovementMode.WALK)
        }
        assertEquals(2, out.size, "a real mode change must survive")
    }

    @Test
    fun aJoinThatWouldReclassifyIsRefused() {
        // Both halves agree, but the union says something else — evidence they were different
        // movement after all. Fail toward the status quo: keep the split.
        val a = run(4, 0, 0.0, 5.0)
        val b = run(4, 180, 900.0, 5.0)
        var call = 0
        val out = ModeCoalescer.coalesce(listOf(a, b)) { path ->
            // calls, in order: a → TRANSIT, b → TRANSIT, joined → CYCLE
            moveOf(path, if (call++ < 2) MovementMode.TRANSIT else MovementMode.CYCLE)
        }
        assertEquals(2, out.size, "a join is a new measurement, not a relabelling")
    }

    @Test
    fun theSharedBoundaryFixIsNotRepeated() {
        // Runs share their boundary fix (ModeSplitter.sliceAt). Concatenating raw would repeat it and
        // inject a zero-length leg — which drags the median down and can flip the joined verdict.
        val a = run(4, 0, 0.0, 5.0)
        val b = listOf(a.last()) + run(3, 240, 1200.0, 5.0)
        val out = ModeCoalescer.coalesce(listOf(a, b), always(MovementMode.TRANSIT))
        assertEquals(1, out.size)
        val joined = out.single().fixes
        assertEquals(joined.size, joined.distinctBy { it.timestampMs }.size, "no fix appears twice")
    }

    @Test
    fun threeAgreeingRunsBecomeOne() {
        val runs = listOf(run(4, 0, 0.0, 5.0), run(4, 180, 900.0, 5.0), run(4, 360, 1800.0, 5.0))
        val out = ModeCoalescer.coalesce(runs, always(MovementMode.TRANSIT))
        assertEquals(1, out.size, "a chain folds, not just a pair")
    }

    @Test
    fun anUnrecordableRunBreaksTheChain() {
        // ⚠ Not "skipped over": joining across a dropped run would attribute its distance to a
        // neighbour it was never contiguous with.
        val a = run(4, 0, 0.0, 5.0)
        val tiny = run(2, 180, 900.0, 5.0) // < 3 fixes ⇒ finalise returns null
        val c = run(4, 600, 3000.0, 5.0)
        val out = ModeCoalescer.coalesce(listOf(a, tiny, c), always(MovementMode.TRANSIT))
        assertEquals(2, out.size, "the hole must not be joined across")
    }

    @Test
    fun aSingleRunIsUnchanged() {
        // The pre-S9 path, byte for byte: no split ⇒ nothing to coalesce.
        val only = run(6, 0, 0.0, 5.0)
        val out = ModeCoalescer.coalesce(listOf(only), always(MovementMode.WALK))
        assertEquals(1, out.size)
        assertEquals(only, out.single().fixes)
    }

    @Test
    fun noRunsYieldNoMoves() {
        assertEquals(emptyList(), ModeCoalescer.coalesce(emptyList(), always(MovementMode.WALK)))
    }

    // ── end to end, through the real pipeline ──────────────────────────────────

    @Test
    fun aRideCutByOneBrakingStopIsStillOneRow() {
        // ⚠ Deliberately NOT the underground path. The reported metro pair turned out to be two rows
        // only because §32.7's over-broad promotion labelled both halves transit, so building S9's
        // end-to-end case on it would tie this rule to the bug that produced it — and it broke the
        // moment that bug was fixed. This is the shape ModeSplitter's own hysteresis KDoc names
        // instead ("a train slowing into a station is not a cycle"): a slow patch long enough to trip
        // the boundary, in a ride that is transit either side of it, refined by TransitKinematics.
        val path = brakingRide()

        // ⚠⚠ The evidence that this fixture *exercises* the rule. Without it the case passes whenever
        // the splitter happens not to cut — which is exactly how an earlier version passed against a
        // deliberately reverted coalescer.
        val runs = ModeSplitter.split(path, MovementDetectionConfig())
        assertEquals(2, runs.size, "fixture no longer splits — the coalescer is not being reached")
        val modes = runs.mapNotNull { it.toCapturedMoveOrNull(MovementDetectionConfig())?.classification?.mode }
        assertEquals(
            listOf(MovementMode.TRANSIT, MovementMode.TRANSIT),
            modes,
            "fixture no longer classifies both halves the same — nothing for the coalescer to agree about",
        )

        val segmenter = MovementSegmenter()
        val events = path.flatMap { segmenter.onFix(it) } + segmenter.onEnd()
        val transit = events.filterIsInstance<CaptureEvent.Move>()
            .map { it.move }
            .filter { it.classification.mode == MovementMode.TRANSIT }

        assertEquals(1, transit.size, "one ride is one row, was ${transit.map { it.distanceM.toInt() }}")
        assertTrue(
            transit.single().distanceM > 40_000.0,
            "and it must carry the whole journey, was ${transit.single().distanceM}",
        )
    }

    /**
     * A fast ride, three slow legs (braking into a station), then fast again. The slow patch trips
     * [MovementDetectionConfig.modeChangeHysteresisLegs] and is itself too short to be recordable, so
     * it folds into the run before it — leaving two viable runs that both classify `transit` on
     * `TransitKinematics`' rail-only speed shortcut. Under S2 alone that is two rows.
     */
    private fun brakingRide(): List<CapturedFix> {
        val path = ArrayList<CapturedFix>()
        var north = 0.0
        var sec = 0
        path += north(north, sec)
        fun leg(count: Int, mps: Double) = repeat(count) {
            north += mps * 30; sec += 30; path += north(north, sec)
        }
        leg(12, 60.0)  // ~216 km/h, comfortably over RAIL_ONLY_SPEED_MPS
        leg(3, 3.0)    // the braking stop
        leg(12, 60.0)
        return path
    }

    /** A fix [northM] up-track at [sec] seconds. */
    private fun north(northM: Double, sec: Int) = CapturedFix(
        latE7 = 525_000_000L + (northM / 0.011132).roundToLong(),
        lngE7 = 134_000_000L,
        timestampMs = t0 + sec * 1000L,
        accuracyM = 8,
    )
}
