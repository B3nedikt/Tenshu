package org.tenshu.inference.proxy.coreml

import org.tenshu.inference.proxy.onnx.VisionModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two guards as arithmetic, with no model loaded (`ios-on-device-inference.md` §3.2). */
class CoreMlTowerSpecTest {

    private val vision: CoreMlTowerSpec = CoreMlTowerSpec.vision(VisionModel.PinnedSigLip)
    private val text: CoreMlTowerSpec = CoreMlTowerSpec.text(VisionModel.PinnedSigLip)

    private fun shape(
        input: String = "pixel_values",
        dimensions: List<Int> = listOf(1, 3, 256, 256),
        type: CoreMlElementType? = CoreMlElementType.FLOAT32,
        outputs: Set<String> = setOf("pooler_output"),
    ) = CoreMlModelShape(mapOf(input to CoreMlInputDescription(dimensions, type)), outputs)

    @Test
    fun thePinnedTowersAreWhatTheExportScriptWrites() {
        assertEquals(listOf(1, 3, 256, 256), vision.inputShape)
        assertEquals(listOf(1, 64), text.inputShape)
        assertEquals(768, vision.outputWidth)
        assertNull(vision.rejectionFor(shape()))
        assertNull(text.rejectionFor(shape("input_ids", listOf(1, 64), CoreMlElementType.INT32)))
    }

    @Test
    fun aModelOfAnotherSideIsRejected() {
        val reason: String? = vision.rejectionFor(shape(dimensions = listOf(1, 3, 224, 224)))

        assertTrue("[1, 3, 224, 224]" in assertNotNull(reason), reason)
    }

    @Test
    fun aTextTowerOfAnotherLengthIsRejected() {
        assertNotNull(text.rejectionFor(shape("input_ids", listOf(1, 32), CoreMlElementType.INT32)))
    }

    @Test
    fun anInputOfAnotherTypeIsRejected() {
        assertNotNull(vision.rejectionFor(shape(type = CoreMlElementType.INT32)))
        assertNotNull(vision.rejectionFor(shape(type = null)))
    }

    @Test
    fun aModelWithoutThePooledOutputIsRejected() {
        // SigLIP pools with a trained head; `last_hidden_state` is another space of the same width.
        assertNotNull(vision.rejectionFor(shape(outputs = setOf("last_hidden_state"))))
    }

    @Test
    fun aModelWithAGeneratedInputNameIsRejected() {
        assertNotNull(vision.rejectionFor(shape(input = "x_1")))
    }

    @Test
    fun aFiniteNonZeroVectorOfTheRightWidthIsUsable() {
        assertNull(vision.unusableReason(FloatArray(768) { 0.01f * (it + 1) }))
    }

    @Test
    fun aVectorOfAnotherWidthIsUnusable() {
        assertNotNull(vision.unusableReason(FloatArray(512) { 1f }))
    }

    @Test
    fun aNonFiniteVectorIsUnusable() {
        assertNotNull(vision.unusableReason(FloatArray(768) { if (it == 400) Float.NaN else 1f }))
        assertNotNull(vision.unusableReason(FloatArray(768) { if (it == 7) Float.POSITIVE_INFINITY else 1f }))
    }

    @Test
    fun anAllZeroVectorIsUnusable() {
        assertNotNull(vision.unusableReason(FloatArray(768)))
    }
}
