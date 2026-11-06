package org.tenshu.inference.proxy.coreml

import org.tenshu.inference.proxy.onnx.TowerGuards
import org.tenshu.inference.proxy.onnx.VisionModel

/** The element type of a tower's input. Core ML offers `MLMultiArray` these two for our towers. */
enum class CoreMlElementType { FLOAT32, INT32 }

/** One input as `MLModel.modelDescription` reports it. [type] is `null` for anything else. */
data class CoreMlInputDescription(val shape: List<Int>, val type: CoreMlElementType?)

/**
 * The facts about a loaded model the shape guard reads, lifted out of `MLModel.modelDescription` so the
 * guard is arithmetic over plain values.
 */
data class CoreMlModelShape(
    val inputs: Map<String, CoreMlInputDescription>,
    val outputs: Set<String>,
)

/**
 * What one tower of the pinned checkpoint must look like to Core ML
 * (`ios-on-device-inference.md` §3.2, *The shape guard, in Core ML's terms*).
 *
 * ⚠⚠ **The guard that keeps a stamp honest**, as `OnnxInferenceBackend.accepts` is on the ONNX
 * side. A model of another side or sequence length does not fail: it returns a vector computed
 * from an input the weights never saw, and that vector would be stamped as the pinned model's.
 *
 * ⚠ The names and types exist only because `scripts/inference/export-coreml-towers.py` sets them.
 */
data class CoreMlTowerSpec(
    /** The catalog role, `vision` or `text`: also the compiled model's file stem. */
    val role: String,
    val inputName: String,
    val inputShape: List<Int>,
    val inputType: CoreMlElementType,
    val outputName: String,
    /** The pooled embedding's width. A different count at run time is a different model. */
    val outputWidth: Int,
) {
    val inputCount: Int get() = inputShape.fold(1) { product, dimension -> product * dimension }

    /** `null` when [shape] is this tower, otherwise why it is not. */
    fun rejectionFor(shape: CoreMlModelShape): String? {
        TowerGuards.outputRejection(shape.outputs, outputName)?.let { return it }
        val input: CoreMlInputDescription = shape.inputs[inputName]
            ?: return "no input named '$inputName' (has ${shape.inputs.keys.sorted()})"
        if (input.type != inputType) return "input '$inputName' is ${input.type}, expected $inputType"
        // ⚠ Every axis, the batch included, and no dynamic one: the export script fixes them all.
        return TowerGuards.inputShapeRejection(input.shape.map { it.toLong() }, inputShape, dynamicAxes = false)
            ?.let { "'$inputName': $it" }
    }

    /** `null` when [vector] can be a pooled embedding of this tower ([TowerGuards.unusableReason]). */
    fun unusableReason(vector: FloatArray): String? = TowerGuards.unusableReason(vector, outputWidth)

    companion object {
        const val VISION_ROLE: String = "vision"
        const val TEXT_ROLE: String = "text"
        private const val POOLED_OUTPUT: String = TowerGuards.POOLED_OUTPUT

        fun vision(model: VisionModel): CoreMlTowerSpec = CoreMlTowerSpec(
            role = VISION_ROLE,
            inputName = "pixel_values",
            inputShape = listOf(1, 3, model.side, model.side),
            inputType = CoreMlElementType.FLOAT32,
            outputName = POOLED_OUTPUT,
            outputWidth = model.dimensions,
        )

        fun text(model: VisionModel): CoreMlTowerSpec = CoreMlTowerSpec(
            role = TEXT_ROLE,
            inputName = "input_ids",
            inputShape = listOf(1, model.textSequenceLength),
            inputType = CoreMlElementType.INT32,
            outputName = POOLED_OUTPUT,
            outputWidth = model.dimensions,
        )
    }
}
