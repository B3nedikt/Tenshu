package app.tenshu.timeline.data.capture

import app.tenshu.vectortile.GeoBounds
import app.tenshu.vectortile.LonLat
import app.tenshu.vectortile.RailMatcher
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TR-1 — **candidacy for the rail sweep is decided on kinematics, not on the mode**
 * (`timeline-movement-capture.md` §38.3).
 *
 * ### What is being defended
 *
 * The map is the only tier that can settle *train vs not*, and the only one allowed to demote. It
 * was reachable only through `mode ∈ {drive, transit}` — through the speed-band verdict — so a
 * median that landed in the cycle band did not merely mislabel a train, it removed the train from
 * the map's view entirely. Measured twice on a real commute; the device log read *"rail sweep: 0
 * candidate(s) of 15 trace(s)"*.
 *
 * ⚠⚠ **The dangerous half of this change is the negative cases, and that is where the weight of
 * this suite sits.** `RailMatcher.DEFAULT_TOLERANCE_M` is 50 m, so a cycle path beside a railway
 * genuinely matches track — proximity has never been evidence about a cyclist. TR-1 is safe only
 * because admission requires a *speed* no body produces, and because the map still has to agree
 * afterwards. Every test below that asserts `false` is protecting that, and
 * [admissionAndPedestrianAgree] protects the structural invariant that makes it coherent.
 *
 * Geometry runs north along a meridian, where 1e-7° of latitude ≈ 0.011132 m.
 */
class VehicularKinematicsTest {

    private val originLat = 520_000_000L
    private val originLng = 130_000_000L
    private val t0 = 1_800_000_000_000L

    private fun latE7(metres: Double): Long = (metres / 0.011132).roundToLong()

    /**
     * A straight run of [n] fixes covering [distanceM] in [durationSec].
     *
     * ⚠ Evenly spaced on purpose: this predicate reads only the two ends, so any shape between them
     * is irrelevant by construction — and a fixture that varied it would imply otherwise.
     */
    private fun run(
        distanceM: Double,
        durationSec: Long,
        n: Int = 12,
        accuracyM: Int = 10,
    ): List<CapturedFix> = (0 until n).map { i ->
        val f = i.toDouble() / (n - 1)
        CapturedFix(
            latE7 = originLat + latE7(distanceM * f),
            lngE7 = originLng,
            timestampMs = t0 + (durationSec * 1000 * f).toLong(),
            accuracyM = accuracyM,
        )
    }

    // ── the case TR-1 shipped for ────────────────────────────────────────────

    @Test
    fun aTrainMisreadAsACycleStillReachesTheMap() {
        // The measured shape: 8.0 km made good in 12.1 min (≈40 km/h) — the Hörlkofen → Markt
        // Schwaben leg, which the device recorded as `Cycle · 8.7 km` and never offered to the sweep.
        val leg = run(distanceM = 8_000.0, durationSec = 726)
        val misread = ModeClassification(MovementMode.CYCLE, confidence = 14, medianSpeedMmPerS = 5_300)

        assertTrue(VehicularKinematics.isVehicular(leg))
        assertTrue(
            RailRefinement.needsRefinement(misread, leg),
            "a trace this fast must reach the map whatever the speed bands called it",
        )
    }

    @Test
    fun theModalGateAloneWouldStillRefuseIt() {
        // Pins that the test above is not passing for some incidental reason: the fixes-free form —
        // the rule TR-1 replaces — still says no. If this ever starts passing, the case above has
        // stopped being evidence of anything.
        val misread = ModeClassification(MovementMode.CYCLE, confidence = 14, medianSpeedMmPerS = 5_300)
        assertFalse(RailRefinement.needsRefinement(misread))
    }

    // ── the negative cases, which are the point ──────────────────────────────

    @Test
    fun aCommutingCyclistIsNotAdmitted() {
        // 8 km in 24 min — 20 km/h made good, a brisk commute. Distance alone must never admit.
        assertFalse(VehicularKinematics.isVehicular(run(distanceM = 8_000.0, durationSec = 1_440)))
    }

    @Test
    fun aRacingCyclistIsNotAdmitted() {
        // 10 km in 20 min — 30 km/h made good, point to point, which almost no one sustains on real
        // roads. Still refused: the bar is 36 km/h precisely so the fast end of cycling sits under it.
        assertFalse(VehicularKinematics.isVehicular(run(distanceM = 10_000.0, durationSec = 1_200)))
    }

    @Test
    fun aShortFastHopIsNotAdmitted() {
        // 2 km in 3 min — 40 km/h, over the speed bar but under the displacement one. At this range
        // the endpoint errors are a large fraction of the separation, so the ratio is not a
        // measurement yet.
        assertFalse(VehicularKinematics.isVehicular(run(distanceM = 2_000.0, durationSec = 180)))
    }

    @Test
    fun aWalkBesideARailwayIsStillAWalkToBothHalvesOfTheRule() {
        // ⚠⚠ The case the pedestrian branch exists for, and the one TR-1 must not weaken. Two hours
        // of walking covers real ground, and every fix of it can sit within RailMatcher's 50 m
        // tolerance of the track — so if candidacy leaked, coverage would promote it.
        val walk = run(distanceM = 7_000.0, durationSec = 7_200) // ~3.5 km/h
        val baseline = ModeClassification(MovementMode.WALK, confidence = 40, medianSpeedMmPerS = 1_400)

        assertFalse(VehicularKinematics.isVehicular(walk))
        assertFalse(RailRefinement.needsRefinement(baseline, walk), "never offered to the map")

        // …and even if something did hand it to refine, on track it stays a walk.
        val onTrack = (0 until 12).map {
            LonLat(lon = 13.0, lat = 52.0 + it * 0.0001)
        }
        val after = RailRefinement.refine(
            baseline = baseline,
            path = onTrack,
            evidence = RailEvidence.Surveyed(
                listOf(RailMatcher.RailLine("rail", onTrack, GeoBounds.of(onTrack)!!)),
            ),
            fixes = walk,
        )
        assertEquals(MovementMode.WALK, after.mode, "proximity is not evidence about a walker")
    }

    @Test
    fun endpointNoiseCannotManufactureAdmission() {
        // The failure direction that would matter: a stationary phone whose two ends are coarse.
        // 300 m of accuracy at each end cannot separate them by 3 km, which is why the displacement
        // floor is expressed in kilometres rather than in multiples of accuracy.
        val jitter = listOf(
            CapturedFix(originLat - latE7(300.0), originLng, t0, accuracyM = 300),
            CapturedFix(originLat, originLng, t0 + 60_000, accuracyM = 300),
            CapturedFix(originLat + latE7(300.0), originLng, t0 + 120_000, accuracyM = 300),
        )
        assertFalse(VehicularKinematics.isVehicular(jitter))
    }

    @Test
    fun aLoopIsNotATrainJourney() {
        // Out and back at speed: the summed path is long, the separation is zero. Speed made good is
        // the measure precisely because it answers "did you get somewhere", and a loop did not.
        val out = run(distanceM = 20_000.0, durationSec = 900, n = 10)
        val back = out.reversed().mapIndexed { i, f ->
            f.copy(timestampMs = out.last().timestampMs + (i + 1) * 90_000L)
        }
        assertFalse(VehicularKinematics.isVehicular(out + back))
    }

    // ── the structural invariant ─────────────────────────────────────────────

    @Test
    fun admissionAndPedestrianAgree() {
        // ⚠⚠ Admitted by `needsRefinement` but still treated as pedestrian by `refine` would be the
        // worst outcome available: the sweep spends a tile fetch on every such trace and can never
        // conclude anything from it. One predicate feeds both, and this is what says so.
        val fast = run(distanceM = 8_000.0, durationSec = 726)
        val slow = run(distanceM = 8_000.0, durationSec = 7_200)
        val onTrack = (0 until 12).map { LonLat(lon = 13.0, lat = 52.0 + it * 0.0001) }
        val evidence = RailEvidence.Surveyed(
            listOf(RailMatcher.RailLine("rail", onTrack, GeoBounds.of(onTrack)!!)),
        )

        for (mode in listOf(MovementMode.WALK, MovementMode.RUN, MovementMode.CYCLE)) {
            val baseline = ModeClassification(mode, confidence = 20, medianSpeedMmPerS = 5_000)
            for (fixes in listOf(fast, slow)) {
                val admitted = RailRefinement.needsRefinement(baseline, fixes)
                val reached = RailRefinement.refine(baseline, onTrack, evidence, fixes = fixes).mode !=
                    baseline.mode
                assertEquals(
                    admitted, reached,
                    "$mode: admitted=$admitted but the coverage tiers " +
                        (if (reached) "did" else "did NOT") + " reach it",
                )
            }
        }
    }

    @Test
    fun refineWithoutFixesBehavesExactlyAsBefore() {
        // ⚠ `refine`'s `fixes` parameter defaults to empty, so every caller that does not supply them
        // must keep its old behaviour rather than silently acquire a new one. Empty ⇒ not vehicular
        // ⇒ pedestrian ⇒ untouched, which is the pre-TR-1 rule verbatim.
        val onTrack = (0 until 12).map { LonLat(lon = 13.0, lat = 52.0 + it * 0.0001) }
        val baseline = ModeClassification(MovementMode.CYCLE, confidence = 20, medianSpeedMmPerS = 5_000)
        val after = RailRefinement.refine(
            baseline = baseline,
            path = onTrack,
            evidence = RailEvidence.Surveyed(
                listOf(RailMatcher.RailLine("rail", onTrack, GeoBounds.of(onTrack)!!)),
            ),
        )
        assertEquals(baseline, after)
    }
}
