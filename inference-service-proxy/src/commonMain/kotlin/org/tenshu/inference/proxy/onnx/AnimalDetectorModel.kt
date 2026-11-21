package org.tenshu.inference.proxy.onnx

import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.AnimalSpecies
import org.tenshu.inference.proxy.DetectedAnimal
import org.tenshu.inference.proxy.asFloat32
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max

/**
 * The animal detector off Apple (`photos-pets.md` PT-D4, §10.1): RF-DETR Nano trained on COCO, static
 * QDQ int8, built by `scripts/inference/export-animal-detector.py`. ⚖️ COCO-trained weights accepted
 * 2026-11-17 (§5.3).
 *
 * ⚠⚠ [sha256] and [byteSize] are the artifact the export's self-check passed; a file that is not
 * these bytes is a different detector and is never run under this descriptor's stamp.
 */
class AnimalDetectorModel(
    val modelId: String,
    val modelVersion: Int,
    val sha256: String,
    val byteSize: Long,
    /** Below this a query is not an animal at all, before any veto threshold applies. */
    val minConfidence: Float,
) {
    val provenance: ModelProvenance get() = ModelProvenance(modelId, modelVersion)

    companion object {
        const val SIDE: Int = 384
        const val QUERIES: Int = 300
        /** The logits are COCO **category ids**, 91 wide — not the 80-class index. */
        const val CLASSES: Int = 91
        const val COCO_CAT: Int = 17
        const val COCO_DOG: Int = 18

        /** ImageNet statistics, RGB — what rfdetr's `predict` normalises with. */
        val MEAN: FloatArray = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD: FloatArray = floatArrayOf(0.229f, 0.224f, 0.225f)

        /**
         * The 2026-11-17 export: held-out COCO val2017 at 0.5, cat recall 0.909 / precision 1.000, dog
         * 0.857 / 0.933 (`photos-pets.md` §10.1). ⚠ COCO is not a home camera roll: M1 is still owed.
         */
        val RfDetrNanoInt8 = AnimalDetectorModel(
            modelId = "rfdetr-nano-coco-int8",
            modelVersion = 1,
            sha256 = "e3523f7581daa4e20469e853488e04cc70a70856898c04cb57439867363dd167",
            byteSize = 28_806_262L,
            minConfidence = 0.3f,
        )

        /**
         * The same checkpoint for iOS Pets (`photos-pets.md` PT-4, §10.13): Core ML fp16 from torch fp32, by
         * `scripts/inference/export-coreml-pets.py` — never Android's int8. ⚠ The same id and version, as the
         * face embedder's Core ML twin keeps AuraFace's: one checkpoint under several runtimes (the signer's
         * rule). End-to-end template parity with Android: median 0.989, min 0.8695.
         */
        val RfDetrNanoCoreMl = AnimalDetectorModel(
            modelId = "rfdetr-nano-coco-int8",
            modelVersion = 1,
            sha256 = "ed3df77920ca3f235ae82451e32770dc15188f0def52f8ca6b39452dca63b8ce",
            byteSize = 54_077_440L,
            minConfidence = 0.3f,
        )
    }
}

/**
 * The detector's input from decoded pixels: an **antialiased bilinear squash** to `384²`, then ImageNet
 * normalisation, CHW.
 *
 * ⚠⚠ Shared, not per platform, unlike the SigLIP resize ([ImagePreprocessing.preprocessPixels]): the
 * detector was trained on torchvision's antialiased resize, and `Bitmap.createScaledBitmap` from a
 * 2048-px decode is plain bilinear and aliases. A box filter was measured too and cost recall (cat
 * 0.909 → 0.873). ⚠⚠ The export's `resize()` is the reference, and `AnimalDetectorInputGoldenTest`
 * pins this to it pixel for pixel: the calibration and the self-check saw exactly these pixels.
 */
object AnimalDetectorInput {

    /** Packed ARGB `width × height` → packed RGB `side × side`, rounded half up once at the end. */
    fun resize(argb: IntArray, width: Int, height: Int, side: Int): IntArray {
        require(width > 0 && height > 0 && side > 0 && argb.size == width * height) { "${argb.size} pixels for ${width}x$height" }
        val columns = weights(width, side)
        val rows = weights(height, side)
        // Horizontal pass: height × side × 3 doubles.
        val horizontal = DoubleArray(height * side * 3)
        for (y in 0 until height) {
            val row = y * width
            for (j in 0 until side) {
                val (start, w) = columns[j]
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (k in w.indices) {
                    val pixel = argb[row + start + k]
                    r += ((pixel shr 16) and 0xFF) * w[k]
                    g += ((pixel shr 8) and 0xFF) * w[k]
                    b += (pixel and 0xFF) * w[k]
                }
                val at = (y * side + j) * 3
                horizontal[at] = r
                horizontal[at + 1] = g
                horizontal[at + 2] = b
            }
        }
        val out = IntArray(side * side)
        for (i in 0 until side) {
            val (start, w) = rows[i]
            for (j in 0 until side) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (k in w.indices) {
                    val at = ((start + k) * side + j) * 3
                    r += w[k] * horizontal[at]
                    g += w[k] * horizontal[at + 1]
                    b += w[k] * horizontal[at + 2]
                }
                out[i * side + j] = (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
            }
        }
        return out
    }

    /** [resize] to [AnimalDetectorModel.SIDE], normalised: the tensor the session takes. */
    fun tensor(argb: IntArray, width: Int, height: Int): FloatArray {
        val side = AnimalDetectorModel.SIDE
        val rgb = resize(argb, width, height, side)
        val plane = side * side
        val out = FloatArray(3 * plane)
        val mean = AnimalDetectorModel.MEAN
        val std = AnimalDetectorModel.STD
        for (i in 0 until plane) {
            val pixel = rgb[i]
            out[i] = normalised((pixel shr 16) and 0xFF, mean[0], std[0])
            out[plane + i] = normalised((pixel shr 8) and 0xFF, mean[1], std[1])
            out[2 * plane + i] = normalised(pixel and 0xFF, mean[2], std[2])
        }
        return out
    }

    private fun normalised(value: Int, mean: Float, std: Float): Float =
        ((value / 255f).asFloat32() - mean).asFloat32() / std

    private fun channel(value: Double): Int = floor(value + 0.5).toInt().coerceIn(0, 255)

    /** Per output index, the first input index and its triangle weights, support grown by the shrink. */
    internal fun weights(inputs: Int, outputs: Int): List<Pair<Int, DoubleArray>> {
        val scale = inputs.toDouble() / outputs
        val support = max(scale, 1.0)
        return List(outputs) { i ->
            val centre = (i + 0.5) * scale
            val lo = max(floor(centre - support).toInt(), 0)
            val hi = minOf(ceil(centre + support).toInt(), inputs)
            val w = DoubleArray(hi - lo) { k -> max(0.0, 1.0 - kotlin.math.abs((lo + k + 0.5 - centre) / support)) }
            val sum = w.sum()
            for (k in w.indices) w[k] /= sum
            lo to w
        }
    }
}

/**
 * RF-DETR's two outputs as cats and dogs: `dets` `[300, 4]` normalised `cx, cy, w, h`, `labels`
 * `[300, 91]` raw logits over COCO category ids.
 *
 * ⚠ Each query becomes at most one animal — the likelier of cat and dog — when its sigmoid is at least
 * [floor]. Boxes are clamped to the image, top-left fractions ([DetectedAnimal]'s convention). No NMS:
 * DETR-family queries are trained one-to-one, and a veto only asks whether ANY box holds a face.
 */
object RfDetrOutput {

    fun animals(dets: FloatArray, logits: FloatArray, floor: Float): List<DetectedAnimal> {
        val queries = AnimalDetectorModel.QUERIES
        val classes = AnimalDetectorModel.CLASSES
        require(dets.size == queries * 4 && logits.size == queries * classes) { "dets ${dets.size}, logits ${logits.size}" }
        val out = ArrayList<DetectedAnimal>()
        for (q in 0 until queries) {
            val cat = sigmoid(logits[q * classes + AnimalDetectorModel.COCO_CAT])
            val dog = sigmoid(logits[q * classes + AnimalDetectorModel.COCO_DOG])
            val confidence = max(cat, dog)
            if (confidence < floor) continue
            val cx = dets[q * 4]
            val cy = dets[q * 4 + 1]
            val w = dets[q * 4 + 2]
            val h = dets[q * 4 + 3]
            out += DetectedAnimal(
                species = if (cat >= dog) AnimalSpecies.CAT else AnimalSpecies.DOG,
                left = (cx - w / 2).coerceIn(0f, 1f),
                top = (cy - h / 2).coerceIn(0f, 1f),
                right = (cx + w / 2).coerceIn(0f, 1f),
                bottom = (cy + h / 2).coerceIn(0f, 1f),
                confidence = confidence,
            )
        }
        return out.sortedByDescending { it.confidence }
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))
}
