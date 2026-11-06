package org.tenshu.inference.proxy.harness

import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * **A measurement harness, not a feature** (`inference-service.md` §13) — it exists to answer the
 * two questions §3.3 of `docs/models.md` says gate IN-3b, before the track takes a native
 * dependency in the shipped graph:
 *
 * 1. does a candidate model's zero-shot quality survive int8, against **our** vocabulary?
 * 2. what does a first index pass cost, per image?
 *
 * ⚠⚠ **Skipped unless pointed at real assets**, exactly like `MvtRealArchiveTest`:
 *
 * ```
 * ./gradlew :inference-service-proxy:jvmTest --tests '*EmbeddingHarness*' \
 *   -Dtenshu.inference.model=/path/to/image-encoder.onnx \
 *   -Dtenshu.inference.photos=/path/to/a/folder \
 *   [-Dtenshu.inference.labels=/path/to/label-vectors.tsv]
 * ```
 *
 * ⚠ It **prints**; it does not assert. A harness that failed a build on a quality number would be
 * a benchmark pretending to be a test, and the number it produces is an input to a decision rather
 * than a contract. What is asserted lives in `HarnessMathTest`.
 *
 * ⚠⚠ **Deletable, and expected to be deleted.** When IN-3b picks a model, the real backend
 * replaces this and the ONNX dependency moves out of `jvmTest`. Leaving a measurement harness
 * behind as if it were infrastructure is how a repo accumulates two ways to run a model.
 *
 * ⚠ The label file is optional and pre-computed: scoring text against images needs the *text*
 * encoder and its tokenizer, which is a second artifact and a second dependency. `label<TAB>v1,v2,…`
 * per line, produced offline, keeps this harness to one model.
 */
class EmbeddingHarness {

    @Test
    fun measure() {
        val modelPath = System.getProperty("tenshu.inference.model")
        val photosPath = System.getProperty("tenshu.inference.photos")
        if (modelPath == null || photosPath == null) {
            println(
                "EmbeddingHarness: skipped — pass -Dtenshu.inference.model=… and " +
                    "-Dtenshu.inference.photos=… to measure a candidate.",
            )
            return
        }

        val photos = imagesUnder(File(photosPath))
        if (photos.isEmpty()) {
            println("EmbeddingHarness: no images under $photosPath")
            return
        }
        val labels = System.getProperty("tenshu.inference.labels")?.let(::readLabelVectors)
            ?: LabelSet(emptyMap(), emptyMap())

        // ⚠ Echoes what actually arrived. A `-D` flag Gradle does not forward into the test JVM is
        // invisible otherwise — this harness has now been bitten by that twice.
        // ⚠⚠ Printed on every run, deliberately. Preprocessing is part of a checkpoint's contract
        // and the wrong profile is invisible in every model-vs-model comparison — so the one thing
        // that must never be silent is which one was used.
        // ⚠⚠ Read from a flag, never assumed. SigLIP's `-224` and `-256-multilingual` are separate
        // checkpoints with separate INPUT SIZES, and feeding a 256-patch model 224² pixels does not
        // fail — ONNX Runtime accepts the tensor and the model returns a vector computed from an
        // image it was never trained to see. `models.md` R7 asks for the same measurement on both
        // candidates, so the size had to stop being a constant before that could mean anything.
        val side = System.getProperty("tenshu.inference.side")?.toIntOrNull() ?: SIDE
        val profile = System.getProperty("tenshu.inference.preprocess")
            ?.uppercase()
            ?.let(ImagePreprocessing.Profile::valueOf)
            ?: ImagePreprocessing.Profile.SIGLIP
        println(
            "EmbeddingHarness: ${photos.size} image(s), model=$modelPath, labels=${labels.size}, " +
                "preprocess=$profile, side=$side, " +
                "baseline=${System.getProperty("tenshu.inference.baseline") ?: "(none)"}",
        )

        val environment = OrtEnvironment.getEnvironment()
        val root = File(photosPath)
        val (embeddings, timings) = embedAll(environment, modelPath, photos, root, profile, side)
        report(embeddings, labels, timings)

        // ⚠⚠ The measurement §3.3 actually asks for: what quantisation costs. Comparing a quantised
        // model against the fp32 one over the SAME images answers it with **no labels and no ground
        // truth** — if the vectors still point the same way, every threshold calibrated on fp32
        // survives; if they do not, the whole calibration has to be redone per variant.
        val baselinePath = System.getProperty("tenshu.inference.baseline")
        if (baselinePath != null) {
            println("--- drift against $baselinePath ---")
            val (reference, _) = embedAll(environment, baselinePath, photos, root, profile, side)
            val drifts = embeddings.mapNotNull { (name, vector) ->
                reference[name]?.let { name to HarnessMath.cosine(vector, it) }
            }
            drifts.forEach { (name, cosine) -> println("  $name  cos=${format(cosine)}") }
            val values = drifts.map { it.second }
            if (values.isNotEmpty()) {
                println("  worst=${format(values.min())}  mean=${format(values.average().toFloat())}")
            }

            // ⚠⚠ The question drift only ever *approximated*. "The vectors still point the same
            // way" is reassuring; "the same label still wins" is the thing a user would notice.
            // A 0.988 cosine leaves plenty of room for the top two labels to swap places, and
            // nothing in a drift average would say so.
            if (!labels.isEmpty()) {
                val winner = { vector: FloatArray ->
                    labels.vectors.maxByOrNull { HarnessMath.cosine(vector, it.value) }?.key
                }
                val comparable = embeddings.keys.filter { it in reference }
                val agreed = comparable.count { name ->
                    winner(embeddings.getValue(name)) == winner(reference.getValue(name))
                }
                println("  top-1 label agreement: $agreed of ${comparable.size}")
                comparable.forEach { name ->
                    val mine = winner(embeddings.getValue(name))
                    val theirs = winner(reference.getValue(name))
                    if (mine != theirs) println("    ⚠ $name: $mine vs baseline $theirs")
                }
            }
        }
    }

    /** Embeds every photo with one model; returns the vectors and the per-image timings. */
    private fun embedAll(
        environment: OrtEnvironment,
        modelPath: String,
        photos: List<File>,
        root: File,
        profile: ImagePreprocessing.Profile,
        side: Int,
    ): Pair<Map<String, FloatArray>, List<Long>> {
        val timings = mutableListOf<Long>()
        val embeddings = LinkedHashMap<String, FloatArray>()
        environment.createSession(modelPath, OrtSession.SessionOptions()).use { session ->
            val inputName = session.inputNames.first()
            println("  $modelPath: inputs=${session.inputNames} outputs=${session.outputNames}")
            photos.forEach { photo ->
                val image = ImageIO.read(photo) ?: run {
                    println("  ⚠ unreadable: ${photo.name}")
                    return@forEach
                }
                val input = preprocess(image, side, profile)
                val shape = longArrayOf(1, 3, side.toLong(), side.toLong())

                val startedAt = System.nanoTime()
                val vector = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use(::embeddingOf)
                }
                timings += (System.nanoTime() - startedAt) / 1_000_000
                if (vector == null) {
                    println("  ⚠ no usable embedding output for ${photo.name}")
                    return@forEach
                }
                // ⚠⚠ Keyed by RELATIVE PATH, never by name. Tenshu's own content plane names every
                // blob after its size tier — `.thumbnails/<uuid>/large` — so 60 images collapsed into
                // four map entries on the first real run: the timings stayed honest while the drift
                // average silently covered a handful of files. A collision that shrinks the sample
                // does not look like an error; it looks like a smaller report.
                embeddings[photo.relativeTo(root).path] = ImagePreprocessing.normalise(vector)
            }
        }
        return embeddings to timings
    }

    /**
     * The pooled image embedding.
     *
     * ⚠⚠ **Selected by name, never by position.** The first output of a SigLIP vision encoder is
     * `last_hidden_state` — one vector *per patch*, `[1, 196, 768]` — and taking `result.get(0)`
     * threw a `ClassCastException` the first time this was pointed at a real model. Position is not
     * a contract; a model is free to order its outputs however it likes.
     *
     * ⚠⚠ Mean-pooling is the fallback and it is announced loudly, because it is **not what the
     * model's own head does**: SigLIP pools with a trained attention head, so a mean over patches is
     * a different embedding space. Measuring one and shipping the other is precisely the "measuring
     * badly looks like evidence" failure this harness exists to avoid.
     */
    private fun embeddingOf(result: OrtSession.Result): FloatArray? {
        result.forEach { entry ->
            if (entry.key == "pooler_output") {
                ((entry.value.value as? Array<*>)?.firstOrNull() as? FloatArray)?.let { return it }
            }
        }
        result.forEach { entry ->
            val first = (entry.value.value as? Array<*>)?.firstOrNull() ?: return@forEach
            if (first is FloatArray) return first
            val tokens = (first as? Array<*>)?.filterIsInstance<FloatArray>().orEmpty()
            if (tokens.isNotEmpty()) {
                println(
                    "  ⚠⚠ '${entry.key}' is per-patch and there is no pooler_output — MEAN-POOLING " +
                        "${tokens.size} tokens. This is NOT the model's own pooling head; treat the " +
                        "numbers below as indicative only.",
                )
                val out = FloatArray(tokens[0].size)
                tokens.forEach { token -> token.forEachIndexed { i, v -> out[i] += v } }
                for (i in out.indices) out[i] = out[i] / tokens.size
                return out
            }
        }
        return null
    }

    private fun report(
        embeddings: Map<String, FloatArray>,
        labels: LabelSet,
        timings: List<Long>,
    ) {
        println("--- timing (ms per image) ---")
        // ⚠ The first inference in a process is far slower than the rest; the median is what a
        // library pass actually costs, and the max is what a user notices.
        println("  n=${timings.size}  p50=${HarnessMath.percentile(timings, 50)}" +
            "  p90=${HarnessMath.percentile(timings, 90)}  max=${timings.maxOrNull() ?: 0}")
        println("  a 5 000-photo pass at p50 ≈ ${HarnessMath.percentile(timings, 50) * 5_000 / 1_000}s")

        if (!labels.isEmpty()) reportLabels(embeddings, labels)

        println("--- nearest neighbour by image similarity ---")
        embeddings.forEach { (name, vector) ->
            val nearest = embeddings.filterKeys { it != name }
                .mapValues { (_, other) -> HarnessMath.cosine(vector, other) }
                .maxByOrNull { it.value }
            if (nearest != null) println("  $name ~ ${nearest.key} ${format(nearest.value)}")
        }
    }

    /**
     * The zero-shot read: what the vocabulary actually says about this library.
     *
     * Three views, because they fail independently:
     *
     * 1. **Per image** — the eyeball check. Wrong labels are only visible here.
     * 2. **Per label** — how often each one wins. ⚠⚠ The classic zero-shot failure is a label that
     *    is *always* in the top 3, and per-image output hides it completely: every line looks
     *    individually plausible while one row quietly matches the whole library.
     * 3. **The leaf rule** — how often IN-D9's margin test degrades to the parent. Too often and
     *    leaves are worthless; never, and the margin is not doing anything.
     */
    private fun reportLabels(embeddings: Map<String, FloatArray>, labels: LabelSet) {
        val scale = System.getProperty("tenshu.inference.logitScale")?.toFloatOrNull()
        val bias = System.getProperty("tenshu.inference.logitBias")?.toFloatOrNull()
        // ⚠ Probability is only printed when BOTH constants are supplied. Defaulting them would
        // invent a calibration and print it with three decimal places.
        fun probability(cosine: Float): String =
            if (scale != null && bias != null) {
                " p=${format(HarnessMath.siglipProbability(cosine, scale, bias))}"
            } else {
                ""
            }

        val scored = embeddings.mapValues { (_, vector) ->
            labels.vectors.mapValues { (_, labelVector) -> HarnessMath.cosine(vector, labelVector) }
        }

        println("--- top labels per image (zero-shot) ---")
        if (scale == null || bias == null) {
            println("  ⚠ no -Dtenshu.inference.logitScale/logitBias — cosines only, NOT the model's " +
                "own score. SigLIP scores sigmoid(scale·cos + bias); a raw cosine has no threshold.")
        }
        scored.forEach { (name, scores) ->
            val top = HarnessMath.topK(scores, k = 3)
                .joinToString { "${it.first} ${format(it.second)}${probability(it.second)}" }
            println("  $name → $top")
        }

        println("--- label health (how often each label wins) ---")
        val topOne = scored.values.mapNotNull { it.maxByOrNull { entry -> entry.value }?.key }
            .groupingBy { it }.eachCount()
        val topThree = scored.values.flatMap { HarnessMath.topK(it, k = 3).map { entry -> entry.first } }
            .groupingBy { it }.eachCount()
        val images = embeddings.size
        topThree.entries.sortedByDescending { it.value }.take(15).forEach { (label, inTopThree) ->
            val share = 100 * inTopThree / images
            val flag = if (share >= 50) "  ⚠⚠ fires on half the library" else ""
            println("  $label: top-1 ×${topOne[label] ?: 0}, top-3 ×$inTopThree ($share%)$flag")
        }
        println("  ${topThree.size} of ${labels.size} labels reached any top 3; " +
            "${topOne.size} ever won outright")

        val tops = labels.tops
        if (tops.isNotEmpty()) {
            println("--- IN-D9's leaf rule (argmax within siblings, margin → parent) ---")
            var degraded = 0
            var resolved = 0
            scored.forEach { (name, scores) ->
                val bestTop = tops.maxByOrNull { scores[it] ?: Float.NEGATIVE_INFINITY } ?: return@forEach
                val siblings = labels.childrenOf(bestTop).associateWith { scores[it] ?: 0f }
                val verdict = HarnessMath.resolveSiblings(siblings, margin = MARGIN) ?: return@forEach
                resolved++
                if (verdict.degrade) degraded++
                val emitted = if (verdict.degrade) "$bestTop (degraded)" else verdict.best
                println("  $name → $emitted  [top=$bestTop ${format(scores[bestTop] ?: 0f)}, " +
                    "best=${verdict.best} ${format(verdict.bestScore)}, " +
                    "runner-up=${verdict.runnerUp}, margin=${format(verdict.margin)}]")
            }
            println("  degraded to parent in $degraded of $resolved at margin=$MARGIN")
        }

        // ⚠⚠ The number that decides whether one global threshold is possible. IN-D8 says it is
        // not; this is where that stops being an assumption.
        val spread = scored.values.flatMap { it.values }
        println("  score range across all ${spread.size} (image, label) pairs: " +
            "${format(spread.min())} … ${format(spread.max())}")
    }

    /**
     * Every image under [root], **recursively**, decided by *content* rather than by name.
     *
     * ⚠⚠ Both halves were needed the first time this was pointed at Tenshu's own store, and each
     * failed silently on its own: the content plane keeps blobs in per-file subdirectories
     * (`.thumbnails/<uuid>/large`) with **no extension at all**, so an extension filter over one
     * flat directory found **zero images** in a store full of them — and reported "no images",
     * which reads as "your folder is empty" rather than "I cannot see this shape".
     *
     * ⚠ A real photo library is nested too, so the recursion is not only for Tenshu's store.
     */
    private fun imagesUnder(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.length() > 0 }
            .filter { it.extension.lowercase() in IMAGE_EXTENSIONS || looksLikeImage(it) }
            .sortedBy { it.path }
            .toList()

    /**
     * JPEG / PNG / GIF / WebP by magic bytes.
     *
     * ⚠ Deliberately narrow: this decides what gets *fed to a model*, and a false positive costs a
     * confusing decode failure rather than a wrong number. HEIC is **absent on purpose** —
     * `ImageIO` cannot decode it, so admitting it here would turn a skipped file into an
     * "unreadable" one and change nothing that matters.
     */
    private fun looksLikeImage(file: File): Boolean {
        val head = ByteArray(12)
        val read = file.inputStream().use { it.read(head) }
        if (read < 12) return false
        fun at(offset: Int, vararg bytes: Int) =
            bytes.withIndex().all { (i, b) -> head[offset + i] == b.toByte() }
        return at(0, 0xFF, 0xD8, 0xFF) ||                       // JPEG
            at(0, 0x89, 0x50, 0x4E, 0x47) ||                    // PNG
            at(0, 0x47, 0x49, 0x46, 0x38) ||                    // GIF
            (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)) // RIFF….WEBP
    }

    /**
     * `label<TAB>0.1,0.2,…[<TAB>parent]` per line — pre-computed offline by
     * `scripts/inference/embed-label-set.py`, so this harness loads one model.
     *
     * ⚠ The third column is optional and empty for a top-tier label. Without it the report falls
     * back to a flat top-K, which measures ranking but says nothing about IN-D9's leaf rule.
     */
    private fun readLabelVectors(path: String): LabelSet {
        val vectors = LinkedHashMap<String, FloatArray>()
        val parents = LinkedHashMap<String, String>()
        File(path).readLines().forEach { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@forEach
            val vector = parts[1].split(',').mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
            if (vector.isEmpty()) return@forEach
            vectors[parts[0]] = ImagePreprocessing.normalise(vector)
            parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }?.let { parents[parts[0]] = it }
        }
        return LabelSet(vectors, parents)
    }

    /** Label vectors plus the leaf→parent edges, which is all IN-D9's two-tier rule needs. */
    private class LabelSet(val vectors: Map<String, FloatArray>, val parents: Map<String, String>) {
        val size get() = vectors.size
        fun isEmpty() = vectors.isEmpty()
        /** Top-tier labels: the ones nothing declares as their parent's child. */
        val tops get() = vectors.keys.filter { it !in parents }
        fun childrenOf(top: String) = parents.filterValues { it == top }.keys
    }

    private fun format(value: Float): String = ((value * 1000).toInt() / 1000f).toString()

    private companion object {
        /** ⚠ The `-224` default. Override with `-Dtenshu.inference.side=` per checkpoint. */
        const val SIDE = 224

        /**
         * IN-D9's margin, in cosine units.
         *
         * ⚠ A first guess, and the harness prints the margin distribution precisely so it can be
         * replaced by a measured one. Nothing calibrated it.
         */
        const val MARGIN = 0.01f
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
