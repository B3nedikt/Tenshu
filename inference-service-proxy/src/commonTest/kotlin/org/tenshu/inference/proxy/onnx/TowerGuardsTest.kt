package org.tenshu.inference.proxy.onnx

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TowerGuards]: the arithmetic `OnnxInferenceBackend` and `CoreMlInferenceBackend` both call
 * (`ios-on-device-inference.md` §3.2).
 *
 * ⚠ The ONNX rows are the only place that backend's shape guard runs without a 100 MB artifact:
 * `OnnxInferenceBackendTest`'s refusal case skips in CI.
 */
class TowerGuardsTest {

    private val model = VisionModel.PinnedSigLip
    private val vision = TowerGuards.visionInput(model)
    private val text = TowerGuards.textInput(model)

    private fun onnx(shape: List<Long>, expected: List<Int?>): String? =
        TowerGuards.inputShapeRejection(shape, expected, dynamicAxes = true)

    private fun coreMl(shape: List<Long>, expected: List<Int?>): String? =
        TowerGuards.inputShapeRejection(shape, expected, dynamicAxes = false)

    @Test
    fun thePinnedVisionTowerIsAcceptedWithADynamicOrAFixedBatch() {
        assertNull(onnx(listOf(-1, 3, 256, 256), vision))
        assertNull(onnx(listOf(1, 3, 256, 256), vision))
        assertNull(onnx(listOf(8, 3, 256, 256), vision))
        assertNull(coreMl(listOf(1, 3, 256, 256), listOf(1, 3, 256, 256)))
    }

    @Test
    fun aVisionTowerOfAnotherSideIsRejected() {
        assertNotNull(onnx(listOf(-1, 3, 224, 224), vision))
        assertNotNull(onnx(listOf(-1, 3, 256, 224), vision))
        assertNotNull(onnx(listOf(-1, 3, 224, 256), vision))
    }

    @Test
    fun aVisionTowerWithAnotherChannelCountOrRankIsRejected() {
        assertNotNull(onnx(listOf(-1, 1, 256, 256), vision))
        assertNotNull(onnx(listOf(3, 256, 256), vision))
        assertNotNull(onnx(listOf(-1, 3, 256, 256, 1), vision))
        assertNotNull(onnx(emptyList(), vision))
    }

    /** ONNX Runtime reports an axis the graph leaves open as -1; that is not another model. */
    @Test
    fun aDynamicAxisIsAcceptedOnlyWhereTheRuntimeHasThem() {
        assertNull(onnx(listOf(-1, -1, -1, -1), vision))
        assertNull(onnx(listOf(-1, -1), text))
        assertNotNull(coreMl(listOf(1, 3, -1, -1), listOf(1, 3, 256, 256)))
        assertNotNull(coreMl(listOf(1, 0), listOf(1, 64)))
    }

    /** A Core ML tower is exported with every axis fixed, the batch included. */
    @Test
    fun aFixedBatchIsCheckedWhenTheSpecNamesIt() {
        assertNotNull(coreMl(listOf(2, 3, 256, 256), listOf(1, 3, 256, 256)))
    }

    @Test
    fun theTextTowerIsItsSequenceLength() {
        assertNull(onnx(listOf(-1, 64), text))
        assertNull(onnx(listOf(1, 64), text))
        // A tower expecting 32 tokens fed 64 does not fail: it embeds a truncated sentence.
        assertNotNull(onnx(listOf(-1, 32), text))
        assertNotNull(onnx(listOf(64), text))
        assertNotNull(onnx(listOf(-1, 64, 1), text))
    }

    @Test
    fun aModelWithoutThePooledOutputIsRejected() {
        assertNull(TowerGuards.outputRejection(listOf("last_hidden_state", "pooler_output")))
        // SigLIP pools with a trained head; `last_hidden_state` is another space of the same width.
        assertNotNull(TowerGuards.outputRejection(listOf("last_hidden_state")))
        assertNotNull(TowerGuards.outputRejection(emptyList()))
    }

    @Test
    fun aPooledOutputOfAnotherWidthIsAnotherModel() {
        assertTrue(TowerGuards.isPooledWidth(FloatArray(768), 768))
        assertFalse(TowerGuards.isPooledWidth(FloatArray(512), 768))
        assertNotNull(TowerGuards.unusableReason(FloatArray(512) { 1f }, 768))
    }

    @Test
    fun aFiniteNonZeroVectorIsUsable() {
        assertNull(TowerGuards.unusableReason(FloatArray(768) { 0.01f * (it + 1) }, 768))
        // One non-zero component is a direction.
        assertNull(TowerGuards.unusableReason(FloatArray(768) { if (it == 5) -0.5f else 0f }, 768))
    }

    @Test
    fun aNonFiniteVectorIsUnusable() {
        assertNotNull(TowerGuards.unusableReason(FloatArray(768) { if (it == 400) Float.NaN else 1f }, 768))
        assertNotNull(TowerGuards.unusableReason(FloatArray(768) { if (it == 7) Float.POSITIVE_INFINITY else 1f }, 768))
        assertNotNull(TowerGuards.unusableReason(FloatArray(768) { if (it == 767) Float.NEGATIVE_INFINITY else 1f }, 768))
    }

    @Test
    fun anAllZeroVectorIsUnusable() {
        assertNotNull(TowerGuards.unusableReason(FloatArray(768), 768))
        assertNotNull(TowerGuards.unusableReason(FloatArray(768) { -0f }, 768))
    }
}
