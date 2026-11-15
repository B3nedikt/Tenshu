package org.tenshu.inference.proxy.onnx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** [FaceModel.AuraFaceCoreMl] and the iOS stamp (`ios-face-grouping.md` §4, F3). */
class FaceModelTest {

    /**
     * ⚠⚠ Android has used 0, 1–4, 11–14 and 21–24, and only ever adds 10 per revision; ≥ 100 keeps
     * the two platforms' stamps apart for as long as Android stays under ten revisions.
     * `FaceDetectionStrategyStampTest` (Android host) checks the strategies themselves.
     */
    @Test
    fun theIosPipelineStampIsOutsideAndroidsRange() {
        assertTrue(FaceModel.VISION_PIPELINE_VERSION >= 100, "${FaceModel.VISION_PIPELINE_VERSION}")
    }

    /**
     * ⚠⚠ FD9: the pipeline stamp and the Vision revision it was measured at move together. Moving
     * either alone edits this pair, which is the point: another revision is a new stamp.
     */
    @Test
    fun theIosPipelineStampIsPairedWithTheVisionRevisionItWasMeasuredAt() {
        assertEquals(100 to 3, FaceModel.VISION_PIPELINE_VERSION to FaceModel.VISION_LANDMARKS_REVISION)
    }

    /**
     * The same checkpoint in another format: everything that decides where a crop lands in the
     * space — the template, the normalisation, the width — is Android's, and the id and version are
     * the catalog's one entry (FD4). Only the runtime and the pipeline stamp tell them apart.
     */
    @Test
    fun theCoreMlModelIsTheSameCheckpointAsAndroids() {
        val ios = FaceModel.AuraFaceCoreMl
        val android = FaceModel.PinnedAuraFace
        assertEquals(android.modelId, ios.modelId)
        assertEquals(android.modelVersion, ios.modelVersion)
        assertEquals(android.template, ios.template)
        assertEquals(android.profile, ios.profile)
        assertEquals(android.dimensions, ios.dimensions)
        assertEquals(null, ios.minFaceNorm)
        assertNotEquals(FaceModel.RUNTIME, FaceModel.COREML_VISION_RUNTIME)
        assertNotEquals("coreml", FaceModel.COREML_VISION_RUNTIME)
    }
}
