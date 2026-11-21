package org.tenshu.inference.proxy.coreml

import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.onnx.AnimalDetectorModel
import org.tenshu.inference.proxy.onnx.FaceModel
import org.tenshu.inference.proxy.onnx.PetEmbedderModel
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
    /**
     * Pets PT-4b: further named outputs and their element counts, for a model that answers with more
     * than one tensor — RF-DETR's `labels` beside its `dets`. ⚠ Each is checked like [outputName]: present
     * at load, and at run time the right count of finite values. Empty for every tower.
     */
    val extraOutputs: Map<String, Int> = emptyMap(),
) {
    val inputCount: Int get() = inputShape.fold(1) { product, dimension -> product * dimension }

    /** `null` when [shape] is this tower, otherwise why it is not. */
    fun rejectionFor(shape: CoreMlModelShape): String? {
        TowerGuards.outputRejection(shape.outputs, outputName)?.let { return it }
        extraOutputs.keys.forEach { name -> TowerGuards.outputRejection(shape.outputs, name)?.let { return it } }
        val input: CoreMlInputDescription = shape.inputs[inputName]
            ?: return "no input named '$inputName' (has ${shape.inputs.keys.sorted()})"
        if (input.type != inputType) return "input '$inputName' is ${input.type}, expected $inputType"
        // ⚠ Every axis, the batch included, and no dynamic one: the export script fixes them all.
        return TowerGuards.inputShapeRejection(input.shape.map { it.toLong() }, inputShape, dynamicAxes = false)
            ?.let { "'$inputName': $it" }
    }

    /** `null` when [vector] can be a pooled embedding of this tower ([TowerGuards.unusableReason]). */
    fun unusableReason(vector: FloatArray): String? = TowerGuards.unusableReason(vector, outputWidth)

    /** [unusableReason] for one of [extraOutputs]: its count, and every value finite. */
    fun unusableExtraReason(name: String, values: FloatArray): String? {
        val width: Int = extraOutputs[name] ?: return "'$name' is not an output of the ${role} model"
        if (values.size != width) return "'$name' has ${values.size} values, expected $width"
        if (values.any { !it.isFinite() }) return "'$name' holds a non-finite value"
        return null
    }

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

        /**
         * The face embedder (`ios-face-grouping.md` §12.1): `input` Float32 `[1, 3, 112, 112]` →
         * `embedding`, 512 wide — the names `scripts/inference/export-coreml-face-embedder.py`
         * sets. ⚠ Its role is the catalog's, so the compiled model is `faceEmbedder.mlmodelc`.
         */
        fun faceEmbedder(model: FaceModel): CoreMlTowerSpec = CoreMlTowerSpec(
            role = ModelArtifactRole.FACE_EMBEDDER,
            inputName = "input",
            inputShape = listOf(1, 3, model.template.side, model.template.side),
            inputType = CoreMlElementType.FLOAT32,
            outputName = "embedding",
            outputWidth = model.dimensions,
        )

        /**
         * Pets' embedder (`photos-pets.md` PT-4): `input` Float32 `[1, 3, 224, 224]` → `embedding`, 768
         * wide (CLS ‖ patch mean) — the names and the Float32 IO `scripts/inference/export-coreml-pets.py`
         * sets. ⚠ Role `petEmbedder`, so the compiled model is `petEmbedder.mlmodelc`.
         */
        fun petEmbedder(): CoreMlTowerSpec = CoreMlTowerSpec(
            role = ModelArtifactRole.PET_EMBEDDER,
            inputName = "input",
            inputShape = listOf(1, 3, PetEmbedderModel.SIDE, PetEmbedderModel.SIDE),
            inputType = CoreMlElementType.FLOAT32,
            outputName = "embedding",
            outputWidth = PetEmbedderModel.WIDTH,
        )

        /**
         * Pets' detector on iOS (PT-4, §10.13 — RF-DETR, not Vision): `input` Float32 `[1, 3, 384, 384]` →
         * `dets` `[1, 300, 4]` and `labels` `[1, 300, 91]`, as the export sets them. ⚠ `dets` is the
         * primary output only by convention; the backend reads both through `predictAll`.
         */
        fun animalDetector(): CoreMlTowerSpec = CoreMlTowerSpec(
            role = ModelArtifactRole.ANIMAL_DETECTOR,
            inputName = "input",
            inputShape = listOf(1, 3, AnimalDetectorModel.SIDE, AnimalDetectorModel.SIDE),
            inputType = CoreMlElementType.FLOAT32,
            outputName = "dets",
            outputWidth = AnimalDetectorModel.QUERIES * 4,
            extraOutputs = mapOf("labels" to AnimalDetectorModel.QUERIES * AnimalDetectorModel.CLASSES),
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
