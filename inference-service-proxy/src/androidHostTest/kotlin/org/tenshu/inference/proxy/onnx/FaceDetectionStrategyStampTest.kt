package org.tenshu.inference.proxy.onnx

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
        assertTrue("$stamps", stamps.all { it < 100 })
    }
}
