package org.tenshu.inference.proxy.harness

import org.tenshu.inference.proxy.onnx.ImagePreprocessing

/**
 * The parts of the measurement harness that are **arithmetic**, split out so they can be tested
 * without a model, a runtime or a photo library (`inference-service.md` §13).
 *
 * ⚠ The harness itself cannot be run in CI — it needs weights nobody has chosen yet. That makes it
 * *especially* important that the pieces which can be wrong quietly (preprocessing, similarity)
 * are pinned here: a resize that crops the wrong region or a cosine that forgets to normalise
 * produces plausible-looking numbers and a completely wrong verdict.
 */
object HarnessMath {

    // ⚠⚠ The preprocessing profile, the CHW conversion and `normalise` moved into SHIPPED code
    // with IN-3b: `ImagePreprocessing` is what devices run, so it is what the harness must run
    // too. §15.2's original instruction — "do not move it to commonMain, it is test scaffolding" —
    // held exactly as long as nothing shipped called it, and a second copy is how a harness stops
    // measuring the product.

    /**
     * ⚠ Assumes both are already [ImagePreprocessing.normalise]d — the harness normalises once, at
     * the source.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /** The [k] highest-scoring labels for one image. */
    fun topK(scores: Map<String, Float>, k: Int = 3): List<Pair<String, Float>> =
        scores.entries.sortedByDescending { it.value }.take(k).map { it.key to it.value }

    /**
     * SigLIP's actual score for an (image, label) pair: `sigmoid(scale · cos + bias)`.
     *
     * ⚠⚠ **A bare cosine is not this model's confidence.** SigLIP is trained with a *sigmoid* loss
     * and carries a learned `logit_scale` and `logit_bias` — and the bias is large and negative
     * (≈ −13 for the base checkpoint), which is what turns "everything looks similar to everything"
     * into a usable independent yes/no per label. Text-side cosines here run 0.57…0.98 across the
     * whole vocabulary, `pizza`↔`glacier` included, so calibrating a threshold on raw cosine would
     * be calibrating on a number the model never uses.
     *
     * ⚠ The two constants live in the *full* model export, not in the separate vision and text
     * towers the harness loads — see `docs/models.md` §3.6 for how they were recovered.
     */
    fun siglipProbability(cosine: Float, logitScale: Float, logitBias: Float): Float =
        1f / (1f + kotlin.math.exp(-(logitScale * cosine + logitBias)))

    /**
     * IN-D9's leaf rule: pick the best sibling, **but degrade to the parent when the top two are
     * close**. [margin] is in raw score units, whatever the caller is scoring in.
     *
     * ⚠⚠ Returning "corgi" on a shiba inu is worse than returning "dog", because a confidently
     * wrong leaf teaches the user the whole feature is unreliable — that is the reasoning IN-D9
     * ends on, and this is where it stops being prose.
     */
    fun resolveSiblings(siblings: Map<String, Float>, margin: Float): SiblingVerdict? {
        val ranked = siblings.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)
        val gap = best.value - (runnerUp?.value ?: Float.NEGATIVE_INFINITY)
        return SiblingVerdict(
            best = best.key,
            bestScore = best.value,
            runnerUp = runnerUp?.key,
            margin = gap,
            degrade = runnerUp != null && gap < margin,
        )
    }

    /** The outcome of [resolveSiblings] — [degrade] means "emit the parent and stop". */
    data class SiblingVerdict(
        val best: String,
        val bestScore: Float,
        val runnerUp: String?,
        val margin: Float,
        val degrade: Boolean,
    )

    /**
     * The [percentile]-th value of [values], nearest-rank.
     *
     * ⚠ A mean alone would hide the shape that matters: the first inference in a process is far
     * slower than the rest (graph setup, JIT), and a library pass is dominated by the median.
     */
    fun percentile(values: List<Long>, percentile: Int): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val rank = ((percentile / 100.0) * sorted.size).toInt().coerceIn(0, sorted.size - 1)
        return sorted[rank]
    }
}
