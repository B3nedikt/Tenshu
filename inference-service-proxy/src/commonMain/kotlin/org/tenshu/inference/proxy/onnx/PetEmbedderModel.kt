package org.tenshu.inference.proxy.onnx

import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.DetectedAnimal
import org.tenshu.inference.proxy.asFloat32
import org.tenshu.inference.proxy.vision.FaceImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pets' identity model (`photos-pets.md` PT-D10): **DINOv2-small zero-shot**, no pet training data,
 * built by `scripts/inference/export-pet-embedder.py`. Input `[1, 3, 224, 224]`, output `embedding`
 * `[1, 768]` = CLS (384) ‖ patch-token mean (384).
 *
 * ⭐ Only the **CLS half** is a template: §10.6 measured the patch mean pulling different cats together
 * (EER 0.199 against CLS's 0.074). The mean stays in the graph so `m2-pet-embedding.py` scores the very
 * artifact the catalog serves.
 *
 * ⚠⚠ [sha256] and [byteSize] are the export the M2 runs measured (§10.6–§10.8; the script is
 * deterministic). A file that is not these bytes is another model and never runs under this stamp.
 */
class PetEmbedderModel(
    val modelId: String,
    val modelVersion: Int,
    val sha256: String,
    val byteSize: Long,
) {
    companion object {
        const val SIDE: Int = 224
        const val WIDTH: Int = 768

        /** The template: the first [TEMPLATE] floats of the output, the CLS token. */
        const val TEMPLATE: Int = 384

        /**
         * The square crop is the box's longer side times this (§10.6, PT-D7's whole body).
         * ⚠ A Double: `1.15f` widened is 1.1499999…, which truncates a box on an exact edge one pixel short
         * of the reference's (Python's float is a double).
         */
        const val MARGIN: Double = 1.15

        val MEAN: FloatArray = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD: FloatArray = floatArrayOf(0.229f, 0.224f, 0.225f)

        val DinoV2SmallZeroShot = PetEmbedderModel(
            modelId = "dinov2-small-zeroshot",
            modelVersion = 1,
            sha256 = "b44ac8be8472aba15a44552b2b35a1e427e23006c8d48d092e905fe97de96a55",
            byteSize = 88_441_580L,
        )

        /** iOS's archive of the same checkpoint (PT-4, §10.13): Core ML fp16, Float32 IO, `export-coreml-pets.py`. */
        val DinoV2SmallZeroShotCoreMl = PetEmbedderModel(
            modelId = "dinov2-small-zeroshot",
            modelVersion = 1,
            sha256 = "00c9cd864a385d955f26da0c26bd3a37adfa99ccce3e2f6c0a1f15735915d8b5",
            byteSize = 43_417_600L,
        )
    }
}

/**
 * One animal's crop as the embedder takes it: a **square of the box's longer side × [PetEmbedderModel.MARGIN]**,
 * centred on the box and slid (never shrunk below the image's shorter side) to stay inside the image,
 * resized to `224²` with PIL's antialiased bicubic, ImageNet-normalised, CHW.
 *
 * ⚠⚠ **`m2-pet-embedding.py`'s `crop()` is the reference, line for line**, because the 0.85 grouping cut
 * (PT-D11) was measured on exactly those pixels: the truncating integer crop, PIL's `BICUBIC` (a = −0.5,
 * support grown by the shrink), its 22-bit fixed-point weights and its 8-bit intermediate after the
 * horizontal pass. `PetCropInputTest` pins the resize to PIL's output.
 */
object PetCropInput {

    /** The crop square in pixels, `[left, top, right, bottom)` — what `m2-pet-embedding.py` cuts. */
    fun square(width: Int, height: Int, animal: DetectedAnimal): IntArray {
        val l = animal.left.toDouble() * width
        val t = animal.top.toDouble() * height
        val r = animal.right.toDouble() * width
        val b = animal.bottom.toDouble() * height
        var side = max(r - l, b - t) * PetEmbedderModel.MARGIN
        val shorter = min(width, height).toDouble()
        if (side > shorter) side = shorter
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val x0 = min(max(0.0, cx - side / 2), max(0.0, width - side))
        val y0 = min(max(0.0, cy - side / 2), max(0.0, height - side))
        // ⚠ Python's `int()` truncates; every value here is ≥ 0, so that is a floor.
        return intArrayOf(x0.toInt(), y0.toInt(), (x0 + side).toInt(), (y0 + side).toInt())
    }

    /** The embedder's input tensor for [animal] in [image]. */
    fun tensor(image: FaceImage, animal: DetectedAnimal): FloatArray {
        val (left, top, right, bottom) = square(image.width, image.height, animal).let { listOf(it[0], it[1], it[2], it[3]) }
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        val cropped = IntArray(w * h)
        for (y in 0 until h) {
            val sy = (top + y).coerceIn(0, image.height - 1)
            for (x in 0 until w) cropped[y * w + x] = image.argb[sy * image.width + (left + x).coerceIn(0, image.width - 1)]
        }
        val side = PetEmbedderModel.SIDE
        val rgb = resizeBicubic(cropped, w, h, side, side)
        val plane = side * side
        val out = FloatArray(3 * plane)
        val mean = PetEmbedderModel.MEAN
        val std = PetEmbedderModel.STD
        for (i in 0 until plane) {
            val pixel = rgb[i]
            out[i] = normalised((pixel shr 16) and 0xFF, mean[0], std[0])
            out[plane + i] = normalised((pixel shr 8) and 0xFF, mean[1], std[1])
            out[2 * plane + i] = normalised(pixel and 0xFF, mean[2], std[2])
        }
        return out
    }

    /**
     * PIL's `Image.resize(..., BICUBIC)` on an 8-bit RGB image: horizontal pass into an 8-bit
     * intermediate, then vertical, each with 22-bit fixed-point weights (`Resample.c`).
     * Packed ARGB in, packed RGB out.
     */
    fun resizeBicubic(argb: IntArray, width: Int, height: Int, outWidth: Int, outHeight: Int): IntArray {
        require(width > 0 && height > 0 && argb.size == width * height) { "${argb.size} pixels for ${width}x$height" }
        val columns = coefficients(width, outWidth)
        val rows = coefficients(height, outHeight)
        val horizontal = IntArray(height * outWidth)
        for (y in 0 until height) {
            val row = y * width
            for (j in 0 until outWidth) {
                val (start, k) = columns[j]
                var r = ROUNDING
                var g = ROUNDING
                var b = ROUNDING
                for (i in k.indices) {
                    val pixel = argb[row + start + i]
                    r += ((pixel shr 16) and 0xFF) * k[i]
                    g += ((pixel shr 8) and 0xFF) * k[i]
                    b += (pixel and 0xFF) * k[i]
                }
                horizontal[y * outWidth + j] = (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
            }
        }
        val out = IntArray(outWidth * outHeight)
        for (i in 0 until outHeight) {
            val (start, k) = rows[i]
            for (j in 0 until outWidth) {
                var r = ROUNDING
                var g = ROUNDING
                var b = ROUNDING
                for (n in k.indices) {
                    val pixel = horizontal[(start + n) * outWidth + j]
                    r += ((pixel shr 16) and 0xFF) * k[n]
                    g += ((pixel shr 8) and 0xFF) * k[n]
                    b += (pixel and 0xFF) * k[n]
                }
                out[i * outWidth + j] = (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
            }
        }
        return out
    }

    private const val PRECISION_BITS = 32 - 8 - 2
    private const val ROUNDING: Long = 1L shl (PRECISION_BITS - 1)

    private fun clip8(value: Long): Int = (value shr PRECISION_BITS).toInt().coerceIn(0, 255)

    /** `precompute_coeffs` + `normalize_coeffs_8bpc`: per output index, the first input and its fixed-point weights. */
    internal fun coefficients(inputs: Int, outputs: Int): List<Pair<Int, LongArray>> {
        val scale = inputs.toDouble() / outputs
        val filterScale = max(scale, 1.0)
        val support = BICUBIC_SUPPORT * filterScale
        val ss = 1.0 / filterScale
        return List(outputs) { xx ->
            val center = (xx + 0.5) * scale
            val xmin = max((center - support + 0.5).toInt(), 0)
            val xmax = min((center + support + 0.5).toInt(), inputs) - xmin
            val weights = DoubleArray(xmax) { x -> bicubic((x + xmin - center + 0.5) * ss) }
            val total = weights.sum()
            val fixed = LongArray(xmax) { x ->
                val w = if (total != 0.0) weights[x] / total else weights[x]
                // ⚠ C's `(int)(w * (1 << 22) + (w < 0 ? -0.5 : 0.5))` — rounds half away from zero.
                val scaled = w * (1 shl PRECISION_BITS)
                (if (scaled < 0) ceil(scaled - 0.5) else floor(scaled + 0.5)).toLong()
            }
            xmin to fixed
        }
    }

    private const val BICUBIC_SUPPORT = 2.0
    private const val A = -0.5

    private fun bicubic(value: Double): Double {
        val x = abs(value)
        return when {
            x < 1.0 -> ((A + 2.0) * x - (A + 3.0)) * x * x + 1.0
            x < 2.0 -> (((x - 5.0) * x + 8.0) * x - 4.0) * A
            else -> 0.0
        }
    }

    private fun normalised(value: Int, mean: Float, std: Float): Float =
        ((value / 255f).asFloat32() - mean).asFloat32() / std
}
