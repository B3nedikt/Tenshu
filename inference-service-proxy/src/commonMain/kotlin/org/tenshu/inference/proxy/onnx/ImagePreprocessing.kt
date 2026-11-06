package org.tenshu.inference.proxy.onnx

import kotlin.math.sqrt

/**
 * How a photograph becomes the tensor a vision model was trained on (IN-3b).
 *
 * ⚠⚠ **This is part of the weights' contract, not a style choice.** `models.md` §3.7.1 records two
 * sessions of quality numbers measured on the wrong pixels: the harness used CLIP's dataset
 * statistics and a centre crop while SigLIP ships `image_mean = image_std = 0.5` and squashes
 * straight to its input size. The error was invisible to every measurement that compared two
 * models to *each other*, because both got the same wrong input and agreed beautifully.
 *
 * ⚠⚠ **It lives in shipped code, and that is a deliberate reversal.** §15.2 put this arithmetic in
 * test scaffolding and said explicitly not to move it to `commonMain` — correctly, while the only
 * caller was a harness. IN-3b makes production a caller, and a second copy is exactly how the
 * measurement stops describing the product: the harness would be pinning one implementation while
 * devices ran another. The harness now uses this one.
 *
 * ⭐⭐ **Pixels in, floats out — no image library.** A packed ARGB [IntArray] exists on the JVM, on
 * Android and in a browser; `BufferedImage` and `Bitmap` do not exist on each other's platforms.
 * So the half that can be silently wrong — channel order, per-channel normalisation, CHW layout —
 * is written once here, and each platform keeps only its own decode-and-resize.
 */
object ImagePreprocessing {

    /**
     * A model family's own image-processor constants. ⚠ Per-channel, and the order is RGB.
     */
    enum class Profile(val mean: FloatArray, val std: FloatArray, val centreCrop: Boolean) {
        /** `SiglipImageProcessor`: rescale to [-1, 1], squash to the target size, no crop. */
        SIGLIP(
            mean = floatArrayOf(0.5f, 0.5f, 0.5f),
            std = floatArrayOf(0.5f, 0.5f, 0.5f),
            centreCrop = false,
        ),

        /**
         * `CLIPImageProcessor`: the OpenAI dataset statistics, short-side resize then centre crop.
         *
         * ⚠ Kept because it is the other half of the lesson and the next candidate may need it —
         * **not** because anything currently shipped uses it.
         */
        CLIP(
            mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f),
            std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f),
            centreCrop = true,
        ),
    }

    /**
     * Prepares an already-decoded, already-resized image for [profile] and returns CHW float data.
     *
     * ⚠⚠ **What is NOT shared is the resize**, and callers must know it: AWT's `SCALE_SMOOTH` and
     * Android's `Bitmap.createScaledBitmap` are different resamplers, so the same photograph
     * produces slightly different pixels — and therefore slightly different vectors — on the two
     * platforms. That is tolerable only because IN-D5 keeps embeddings **device-local**: two
     * devices' vectors are never compared to each other, only to a query embedded on the same
     * device. ⚠ It is also why a store's vectors cannot be shipped between devices as an
     * optimisation, however tempting that looks.
     *
     * @param argb row-major packed ARGB, exactly `size * size` entries — the caller has already
     *   applied [Profile.centreCrop] and scaled to `size × size`.
     */
    fun preprocessPixels(
        argb: IntArray,
        size: Int = 224,
        profile: Profile = Profile.SIGLIP,
    ): FloatArray = preprocessPixelsInto(argb, FloatArray(3 * size * size), size, profile)

    /**
     * [preprocessPixels] into a tensor the caller owns, and returns it.
     *
     * ⚠ For a backend that embeds a library one photo at a time: 0.8 MB a photo is otherwise left
     * to the collector's schedule. Every element of [out] is overwritten, so nothing of the
     * previous photo survives; the caller must not hand [out] to anything that keeps it.
     */
    fun preprocessPixelsInto(
        argb: IntArray,
        out: FloatArray,
        size: Int = 224,
        profile: Profile = Profile.SIGLIP,
    ): FloatArray {
        require(argb.size == size * size) {
            "expected ${size * size} pixels for ${size}x$size, got ${argb.size}"
        }
        require(out.size == 3 * size * size) {
            "expected a tensor of ${3 * size * size} values for ${size}x$size, got ${out.size}"
        }
        val mean = profile.mean
        val std = profile.std

        // CHW: all reds, then all greens, then all blues — the layout every CLIP-family export uses.
        val plane = size * size
        for (i in 0 until plane) {
            val rgb = argb[i]
            out[i] = (((rgb shr 16) and 0xFF) / 255f - mean[0]) / std[0]
            out[plane + i] = (((rgb shr 8) and 0xFF) / 255f - mean[1]) / std[1]
            out[2 * plane + i] = ((rgb and 0xFF) / 255f - mean[2]) / std[2]
        }
        return out
    }

    /**
     * L2-normalises in place and returns the same array — cosine is a dot product afterwards.
     *
     * ⚠⚠ Every vector that leaves a backend is normalised here, because `PhotosLike` scores with a
     * bare dot product. An un-normalised vector would make that rank by *magnitude* as much as by
     * direction, and the results would look plausible rather than wrong.
     */
    fun normalise(vector: FloatArray): FloatArray {
        val length = sqrt(vector.fold(0f) { sum, v -> sum + v * v })
        if (length == 0f) return vector
        for (i in vector.indices) vector[i] = vector[i] / length
        return vector
    }
}
