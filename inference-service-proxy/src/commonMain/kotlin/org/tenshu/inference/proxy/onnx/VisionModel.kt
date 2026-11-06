package org.tenshu.inference.proxy.onnx

import org.tenshu.inference.proxy.ScoreCalibration

import org.tenshu.inference.ModelProvenance

/**
 * Everything about a vision checkpoint that a backend has to get right, in one place (IN-3b).
 *
 * ⚠⚠ **Four of these five fields are silently wrong rather than loudly wrong.** Feeding a
 * 256-patch model 224² pixels does not fail — ORT accepts the tensor and returns a vector computed
 * from an image the model never saw (`inference-service.md` §15.11.4). The same is true of the
 * normalisation profile (`models.md` §3.7.1 cost two sessions of quality numbers to that) and of
 * the provenance stamp, which is what `EmbeddingStore` uses to refuse mixing two models' spaces.
 * So they travel together, and a backend takes one of these rather than five arguments.
 */
data class VisionModel(
    val modelId: String,
    val modelVersion: Int,
    /** The checkpoint's own input size, from its `preprocessor_config.json` — never a constant. */
    val side: Int,
    val profile: ImagePreprocessing.Profile,
    /** The pooled embedding's dimensionality, used to reject an artifact that is not this model. */
    val dimensions: Int,
    /**
     * R3: the fixed window the *text* tower was exported at.
     *
     * ⚠ SigLIP's text side is trained at one length and padded to it — 64 for every published
     * checkpoint — and a tower fed the wrong length embeds a truncated sentence rather than failing.
     */
    val textSequenceLength: Int = 64,
    /**
     * How this checkpoint's cosines become probabilities — see [ScoreCalibration].
     *
     * ⚠ Nullable because a checkpoint whose constants nobody has read out of its graph has no
     * calibration, and inventing one is the failure `models.md` §3.7.2 exists to prevent.
     */
    val calibration: ScoreCalibration? = null,
) {
    val provenance: ModelProvenance get() = ModelProvenance(modelId, modelVersion)

    companion object {
        /**
         * ⭐ **The pinned checkpoint** — `models.md` §3.8, R1 + R2, decided 2026-08-25 and measured
         * on hardware 2026-09-01 (`inference-service.md` §15.12).
         *
         * ⚠⚠ **Changing this is a re-embed of every photo on every device**, not an upgrade: two
         * checkpoints have different embedding spaces, so there is no migration — that is what a
         * model *is* (§16.1). `EmbeddingStore.rebase` is the only path, and it drops everything.
         *
         * ⚠ Until IN-2c's catalog is wired, this is also what a side-loaded artifact is *asserted*
         * to be. The backend checks the shape it can check — input side, pooled output — so a
         * different model under this filename is refused rather than stamped as this one.
         */
        val PinnedSigLip = VisionModel(
            modelId = "siglip-base-patch16-256-multilingual",
            modelVersion = 1,
            side = 256,
            profile = ImagePreprocessing.Profile.SIGLIP,
            dimensions = 768,
            // ⚠⚠ Read out of **this** checkpoint's weights rather than remembered: `logit_scale`
            // (4.709494, stored as a log, so the scale is its exponential) and `logit_bias` in the
            // `model.safetensors` the catalog pins (`models.md` §3.7.2). They are NOT in the vision
            // and text towers a backend loads, which is why they have to be stated here at all.
            //
            // ⚠⚠ A constant per **checkpoint**: until 2026-10-11 this line carried the `-224`
            // checkpoint's pair (117.330765, −12.932437), read before the pin moved (§3.10.8).
            // No gate can read the weights, so `PinnedSigLipCalibrationPinTest` holds the literals
            // beside their provenance and `label-eval.py evaluate --source` re-reads them.
            calibration = ScoreCalibration(scale = 110.995995f, bias = -12.885268f),
        )
    }
}

/**
 * Decode-and-resize, the one half of preprocessing that cannot be shared (IN-3b).
 *
 * ⚠ Each platform keeps its own: AWT is not on Android, `Bitmap` is not on the JVM, and neither is
 * in a browser. The arithmetic that follows *is* shared — see [ImagePreprocessing].
 *
 * @return row-major packed ARGB of exactly `size * size` entries, or `null` when the bytes are not
 *   a readable image. ⚠ `null` is an ordinary answer: a photo library contains files that are not
 *   photographs, and an index pass counts them rather than failing.
 */
fun interface ImageDecoder {
    fun decode(bytes: ByteArray, size: Int, centreCrop: Boolean): IntArray?

    /**
     * [decode] into [target], which must hold exactly `size * size` entries. `false` is [decode]'s
     * `null`, and then [target] holds nothing meaningful.
     *
     * ⚠ The default still allocates. A decoder overrides it when its caller embeds photo after
     * photo and can hand over one buffer for all of them.
     */
    fun decodeInto(bytes: ByteArray, size: Int, centreCrop: Boolean, target: IntArray): Boolean {
        val pixels: IntArray = decode(bytes, size, centreCrop) ?: return false
        pixels.copyInto(target)
        return true
    }
}
