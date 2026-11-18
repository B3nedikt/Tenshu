package org.tenshu.inference.proxy.onnx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠⚠ No Android detection strategy may stamp a value from iOS's range (`ios-face-grouping.md` §4):
 * a shared stamp would let a template store accept one platform's pipeline as the other's.
 */
class FaceDetectionStrategyStampTest {

    @Test
    fun noAndroidStrategyReachesTheIosRange() {
        val android = listOf(FaceDetectionStrategy.SingleStage) +
            (1..FaceDetectionStrategy.TwoStage.MAX_TILE_GRID).map { FaceDetectionStrategy.TwoStage(it) }
        val stamps = android.map { it.pipelineVersion }
        assertFalse("$stamps", FaceModel.VISION_PIPELINE_VERSION in stamps)
        assertFalse("$stamps", FaceModel.VISION_ANIMAL_VETO_PIPELINE_VERSION in stamps)
        assertTrue("$stamps", stamps.all { it < 100 })
    }

    /**
     * PT-D9: a veto-on stamp is never a veto-off one — today's or any used before (0, 1–4, 11–14,
     * 21–24) — and never iOS's.
     */
    @Test
    fun animalVetoStampsAreTheirOwn() {
        val android = listOf(FaceDetectionStrategy.SingleStage) +
            (1..FaceDetectionStrategy.TwoStage.MAX_TILE_GRID).map { FaceDetectionStrategy.TwoStage(it) }
        val used = setOf(0, 1, 2, 3, 4, 11, 12, 13, 14, 21, 22, 23, 24) + android.map { it.pipelineVersion }
        val veto = android.map { it.animalVetoPipelineVersion }
        assertTrue("$veto vs $used", veto.none { it in used })
        assertTrue("$veto", veto.all { it < 100 } && veto.toSet().size == veto.size)
        assertEquals(listOf(50, 71, 72, 73, 74), veto)
    }
}
