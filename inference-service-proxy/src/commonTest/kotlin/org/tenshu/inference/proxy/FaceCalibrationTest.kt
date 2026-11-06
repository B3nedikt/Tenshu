package org.tenshu.inference.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The calibration production wires (IN-6 F2, `photos-people-and-pets.md` §12.6).
 *
 * ⚠⚠ **These pin the number against the measurement it came from, not against itself.** A test that
 * asserted `0.44f == 0.44f` would survive any edit; these assert the properties §12.6 actually
 * established, so a future change has to either stay inside the measured band or come with a new
 * measurement — which is the only honest way to move it.
 */
class FaceCalibrationTest {

    private val measured = FaceClustering.Measured

    @Test
    fun theThresholdSitsInsideTheBandTheMeasurementEstablished() {
        // §12.8c, for the SHIPPED checkpoint (AuraFace int8): the verified cluster holds at 29
        // photographs from 0.20 through 0.44 and has fragmented by 0.52. (HSFace, §12.6, held to
        // 0.52 — that band described a model that no longer ships.) Outside it the run says
        // nothing: below, purity was not shown; above, the cluster is already coming apart.
        assertTrue(measured.threshold.cosine > 0.20f, "below the measured floor")
        assertTrue(measured.threshold.cosine <= 0.44f, "above where AuraFace's stability was demonstrated")
    }

    @Test
    fun theThresholdClearsTheNearestDifferentPersonByAMargin() {
        // §12.8c: the nearest pair between the verified cluster and any OTHER person is 0.215 on
        // AuraFace int8 (HSFace's was 0.10–0.20). A cut at or below that merges two people — and a
        // merge writes a stranger's face under a friend's name, in synced data, where nothing
        // corrects it. ⚠ This is the assertion that ruled out 0.40.
        val nearestDifferentPerson = 0.215f
        assertTrue(
            measured.threshold.cosine >= nearestDifferentPerson * 2f,
            "the margin over different-people similarity is under 2x, which is where a merge " +
                "stops being a remote possibility",
        )
    }

    @Test
    fun theThresholdIsAtTheHighEndOfTheKneeRatherThanItsMiddle() {
        // ⚠⚠ Deliberate, and the asymmetry is the argument: splitting one person into two clusters
        // costs the user one extra naming and folds (`labelKey` is per file/capability/label),
        // while merging two people writes the wrong name into synced rows. §12.6 puts the knee at
        // 0.28–0.44, and this sits at its top.
        assertEquals(0.44f, measured.threshold.cosine)
    }

    @Test
    fun aPersonMustAppearTwiceToEarnACard() {
        // Photos' own `DEFAULT_MIN_LABEL_PHOTOS` makes the same call for Things: one photograph is
        // a search result, not a person. ⚠ Not 3 — a spurious card is clutter the user can ignore,
        // a missing person is not something they can fix.
        assertEquals(2, measured.minFaces)
    }

    @Test
    fun clusteringWithTheMeasuredValueActuallyEmitsSomething() {
        // ⚠⚠ The guard against the defect this whole calibration exists to end: `Uncalibrated`
        // returns an empty list by construction, so wiring it — as production did until
        // 2026-09-24 — makes every device report a library containing nobody, however good the
        // model is. This asserts the wired value is not that.
        assertTrue(measured is FaceClustering.Calibrated)
    }
}
