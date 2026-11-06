package org.tenshu.inference.proxy.onnx

/**
 * The guards every backend of the pinned checkpoint applies, as arithmetic over plain values
 * (`ios-on-device-inference.md` §3.2).
 *
 * ⚠⚠ **One copy, because this is the half that is silently wrong when it drifts.** A tower of
 * another input size, another pooling head or another width does not fail: it returns a vector, and
 * that vector would be stamped as the pinned model's (`inference-service.md` §16.1). ONNX Runtime
 * and Core ML each report a loaded model's inputs and outputs in their own types; each backend
 * lifts those into the values below and asks here.
 */
internal object TowerGuards {

    /**
     * SigLIP pools with a trained attention head. Mean-pooling `last_hidden_state` is a different
     * embedding space of the same width.
     */
    const val POOLED_OUTPUT: String = "pooler_output"

    /** The vision tower's input: `[batch, 3, side, side]`. The batch axis is not checked. */
    fun visionInput(model: VisionModel): List<Int?> = listOf(null, 3, model.side, model.side)

    /** The text tower's input: `[batch, sequence]`. The batch axis is not checked. */
    fun textInput(model: VisionModel): List<Int?> = listOf(null, model.textSequenceLength)

    /** `null` when [outputNames] has the pooled output, otherwise why the model is not a tower. */
    fun outputRejection(outputNames: Collection<String>, pooled: String = POOLED_OUTPUT): String? =
        if (pooled in outputNames) null else "no output named '$pooled' (has ${outputNames.sorted()})"

    /**
     * `null` when an input of shape [actual] is the one [expected] describes, otherwise why not.
     *
     * @param expected one entry per axis; `null` leaves that axis unchecked.
     * @param dynamicAxes whether an axis the runtime reports as non-positive is accepted. ONNX
     *   Runtime reports a dynamic axis as `-1`; a Core ML tower is exported with fixed axes, so
     *   there a non-positive one is another model.
     */
    fun inputShapeRejection(actual: List<Long>, expected: List<Int?>, dynamicAxes: Boolean): String? {
        if (actual.size != expected.size) return "input has shape $actual, expected ${expected.size} axes"
        for (axis in expected.indices) {
            val wanted: Int = expected[axis] ?: continue
            val found: Long = actual[axis]
            if (dynamicAxes && found <= 0) continue
            if (found.toInt() != wanted) return "input has shape $actual, expected $wanted on axis $axis"
        }
        return null
    }

    /** A pooled output of another width is a different model, not a bad photo. */
    fun isPooledWidth(vector: FloatArray, width: Int): Boolean = vector.size == width

    /**
     * `null` when [vector] can be a pooled embedding of [width] values, otherwise why it cannot.
     *
     * ⚠⚠ **A model that runs is not a model that answers.** One Core ML path on the measured phone
     * ran and returned 768 NaNs (`ios-on-device-inference.md` §2.1), [ImagePreprocessing.normalise]
     * propagates NaN, and the width check alone passes it. A zero vector normalises to itself and
     * scores 0 against every query, so it would be stored as an indexed photo no search can find.
     */
    fun unusableReason(vector: FloatArray, width: Int): String? {
        if (!isPooledWidth(vector, width)) return "'$POOLED_OUTPUT' has ${vector.size} values, expected $width"
        if (vector.any { !it.isFinite() }) return "'$POOLED_OUTPUT' holds a non-finite value"
        if (vector.all { it == 0f }) return "'$POOLED_OUTPUT' is all zeros"
        return null
    }
}
