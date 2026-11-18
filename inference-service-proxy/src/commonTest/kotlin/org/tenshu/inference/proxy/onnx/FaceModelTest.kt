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
     * PT-D9: the veto's pipeline is the face pipeline plus the animal request, so it carries both
     * revisions, and its stamp is its own — never the veto-off one, never in Android's range.
     */
    @Test
    fun theAnimalVetoPipelineHasItsOwnStampPairedWithBothRevisions() {
        assertEquals(
            Triple(101, 3, 2),
            Triple(FaceModel.VISION_ANIMAL_VETO_PIPELINE_VERSION, FaceModel.VISION_LANDMARKS_REVISION, FaceModel.VISION_ANIMALS_REVISION),
        )
        assertTrue(FaceModel.VISION_ANIMAL_VETO_PIPELINE_VERSION != FaceModel.VISION_PIPELINE_VERSION)
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

    /** The macOS desktop pair (`ios-face-grouping.md` §10.8): Android's very weights, under its own runtime. */
    @Test
    fun theMacModelIsAndroidsWeightsUnderItsOwnRuntime() {
        val mac = FaceModel.AuraFaceOnnxMac
        val android = FaceModel.PinnedAuraFace
        assertEquals(android.copy(minFaceNorm = null), mac)
        assertNotEquals(FaceModel.RUNTIME, FaceModel.ONNX_VISION_RUNTIME)
        assertNotEquals(FaceModel.COREML_VISION_RUNTIME, FaceModel.ONNX_VISION_RUNTIME)
        assertNotEquals("onnx", FaceModel.ONNX_VISION_RUNTIME)
    }

    /**
     * ⚠⚠ The macOS stamp: its own range, deterministic, and moved by the OS build (FD9). It must
     * never read as Android's (< 100) or iOS's (100, 101), and a pipeline change moves the range.
     */
    @Test
    fun theMacStampCarriesTheOsBuildInItsOwnRange() {
        assertEquals(200, FaceModel.MAC_VISION_PIPELINE_VERSION)
        val stamp = FaceModel.macVisionStamp("26B123")
        assertTrue(stamp in 1_200_000_000..1_200_999_999, "$stamp")
        assertEquals(stamp, FaceModel.macVisionStamp("26B123"))
        assertNotEquals(stamp, FaceModel.macVisionStamp("26C55"))
        assertTrue(FaceModel.macVisionStamp("26B123", pipeline = 201) in 1_201_000_000..1_201_999_999)
        assertTrue(stamp != FaceModel.VISION_PIPELINE_VERSION && stamp != FaceModel.VISION_ANIMAL_VETO_PIPELINE_VERSION)
        // A fixed CRC-32 vector, so the stamp is the same number on every JVM and target.
        assertEquals(0xCBF43926L, FaceModel.crc32("123456789".encodeToByteArray()))
    }
}
