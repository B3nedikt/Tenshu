package app.tenshu.timeline.data.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S1's classifier-version bump (`docs/proposals/timeline-movement-capture.md` §17.2) and, more
 * importantly, **what the bump must not break**.
 *
 * S1 changes the *sampling regime*, not [classifyMode]'s rule: `MODE_BOUNDARIES` are applied to the
 * median of per-leg speeds, and a leg's speed is straight-line displacement over the interval, which
 * under-reports in proportion to that interval. Shortening it shifts every measured median upward, so
 * traces stamped under the old regime have to stay distinguishable — which is the whole point of the
 * constant.
 */
class MovementClassifierVersionTest {

    @Test
    fun theCaptureClassifierWasBumpedForTheNewSamplingRegime() {
        assertTrue(
            MOVEMENT_CLASSIFIER_VERSION >= 4,
            "S1 changed the sampling regime; traces from the old one must remain distinguishable",
        )
    }

    @Test
    fun theBumpDoesNotReOfferAlreadyRailRefinedTraces() {
        // ⚠⚠ The trap: RailRefinement.needsRefinement keys off *both* constants. A capture-side bump
        // that reached or passed MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED would either re-offer every
        // already-refined trace to the sweep, or (worse) make freshly captured traces look refined.
        assertTrue(
            MOVEMENT_CLASSIFIER_VERSION < MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED,
            "a capture-time verdict must still sort below a rail-refined one",
        )
        assertTrue(MOVEMENT_CLASSIFIER_VERSION != MOVEMENT_CLASSIFIER_EXTERNAL)

        val freshlyCaptured = ModeClassification(MovementMode.DRIVE, confidence = 60, medianSpeedMmPerS = 20_000)
        assertEquals(MOVEMENT_CLASSIFIER_VERSION, freshlyCaptured.version)
        assertTrue(RailRefinement.needsRefinement(freshlyCaptured), "a new trace still wants the rail pass")

        val alreadyRefined = freshlyCaptured.copy(version = MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED)
        assertFalse(RailRefinement.needsRefinement(alreadyRefined), "a refined trace must not be re-offered")
    }

    @Test
    fun theTiersSortInTheOrderTheyApplyIn() {
        // ⚠⚠ Nothing compares the capture and underground constants numerically today — which is
        // exactly why they were free to drift into the wrong order (underground 5 *below* a bumped
        // capture 6, so a promotion looked older than the verdict it refined). Pinned here so the
        // next bump has to keep them coherent rather than discovering this later.
        assertTrue(MOVEMENT_CLASSIFIER_EXTERNAL < MOVEMENT_CLASSIFIER_VERSION, "external sorts first")
        assertTrue(
            MOVEMENT_CLASSIFIER_VERSION < MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND,
            "the §27 promotion refines a capture verdict, so it must sort above one",
        )
        assertTrue(
            MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND < MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED,
            "and must still look un-swept to the tile tier that may demote it",
        )
        // §32.12: the underground *refined* verdict sorts with the tile tier, not below it — a swept
        // metro leg must never be re-offered to the sweep.
        assertTrue(
            MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND >= MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED,
            "a sweep-confirmed metro leg must read as swept",
        )
        assertFalse(
            RailRefinement.needsRefinement(
                ModeClassification(
                    MovementMode.TRANSIT,
                    confidence = 80,
                    medianSpeedMmPerS = 9_000,
                    version = MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND,
                ),
            ),
            "…and must not be swept again",
        )
    }

    @Test
    fun theBucketBoundariesWereDeliberatelyNotRetuned() {
        // ⚠ Fitting MODE_BOUNDARIES honestly needs the T3 corpus (a real Takeout import against known
        // modes), which does not exist. These pins are here so a future retune is a *deliberate* act
        // that has to update this test, not a quiet drift bundled into an unrelated slice.
        fun medianSpeedTrace(metresPerSecond: Double): List<CapturedFix> =
            (0..10).map {
                CapturedFix(
                    latE7 = 525_000_000L + (it * metresPerSecond * 10.0 / 0.011132).toLong(),
                    lngE7 = 134_000_000L,
                    timestampMs = it * 10_000L,
                )
            }

        assertEquals(MovementMode.WALK, classifyMode(medianSpeedTrace(1.5)).mode)
        assertEquals(MovementMode.RUN, classifyMode(medianSpeedTrace(3.0)).mode)
        assertEquals(MovementMode.CYCLE, classifyMode(medianSpeedTrace(6.0)).mode)
    }
}
