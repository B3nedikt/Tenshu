package org.tenshu.inference.proxy.onnx

import org.tenshu.inference.proxy.ScoreCalibration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `VisionModel.PinnedSigLip.calibration`, held beside **where its two numbers were read from**.
 *
 * ⚠⚠ The defect this exists for: until 2026-10-11 the model carried the `-224` checkpoint's
 * constants while pinning `-256-multilingual` (`models.md` §3.10.8). Nothing failed, because a
 * constant with no stated origin cannot be wrong against anything.
 *
 * ⚠⚠ **This cannot read the weights** — no gate has the 1.4 GB file — so it does not prove the
 * literals are right. It makes a change to either number fail here, next to the file and digest
 * that settle it. The check that does read them:
 *
 * ```
 * scripts/inference/label-eval.py evaluate --source <the checkpoint directory> …
 * ```
 *
 * which refuses weights that are not [WEIGHTS_SHA256] and refuses constants the weights do not
 * hold. `PinnedSigLipCalibrationSourceTest` holds this provenance to the catalog's.
 *
 * ⚠ Changing the pinned checkpoint changes all of these together, and owes a floor
 * (`PhotosLikeImplementation.SEMANTIC_RELEVANCE_FLOOR`) measured on the new one.
 */
class PinnedSigLipCalibrationPinTest {

    @Test
    fun theCalibrationIsTheOneReadFromThePinnedWeights() {
        val calibration: ScoreCalibration? = VisionModel.PinnedSigLip.calibration
        // ⚠ Compared as bits: on Kotlin/JS a `Float` is a double, and a literal is not its bits.
        assertEquals(
            SCALE.toBits() to LOGIT_BIAS_BITS,
            calibration?.let { it.scale.toBits() to it.bias.toBits() },
            "PinnedSigLip.calibration moved (now $calibration). It is read from $SOURCE_REPO@$SOURCE_REVISION, " +
                "model.safetensors sha256 $WEIGHTS_SHA256: re-read it with `label-eval.py evaluate --source` " +
                "and re-pin here only what the weights hold.",
        )
        assertEquals(MODEL_ID, VisionModel.PinnedSigLip.modelId, "another checkpoint has another calibration")
    }

    /** The weights store the scale as a log. ⚠ A literal that is not its exponential was mistyped. */
    @Test
    fun theScaleIsTheExponentialOfTheStoredLogitScale() {
        assertEquals(LOGIT_SCALE_BITS, 4.709494f.toBits())
        assertEquals(LOGIT_BIAS_BITS, (-12.885268f).toBits())
        // 110.99599437…, 0.03 of an ulp from the `Float` it rounds to: no `exp` rounds it elsewhere.
        assertEquals(SCALE.toBits(), kotlin.math.exp(Float.fromBits(LOGIT_SCALE_BITS).toDouble()).toFloat().toBits())
    }

    companion object {
        const val MODEL_ID = "siglip-base-patch16-256-multilingual"
        const val SOURCE_REPO = "google/siglip-base-patch16-256-multilingual"
        const val SOURCE_REVISION = "8952a4eafcde3cb7ab46b1dd629b33f8784ca9c6"
        const val WEIGHTS_SHA256 = "00df88d823790b34a4d2d0047803ffabd47497ae724fd6659b1533dd793255be"

        /** The four little-endian bytes of `logit_scale` in that file, `2d b4 96 40`: 4.709494. */
        private const val LOGIT_SCALE_BITS: Int = 0x4096B42D

        /** The four little-endian bytes of `logit_bias`, `0f 2a 4e c1`: −12.885268. */
        private const val LOGIT_BIAS_BITS: Int = 0xC14E2A0F.toInt()

        /** `exp(4.709494113922119)` = 110.99599437…, as a `Float`. */
        private const val SCALE = 110.995995f
    }
}
