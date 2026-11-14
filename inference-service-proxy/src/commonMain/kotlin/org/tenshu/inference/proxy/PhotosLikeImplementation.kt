package org.tenshu.inference.proxy

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.get
import org.tenshu.core.number.TInteger
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.SemanticSearchUnavailable
import org.tenshu.inference.SimilarPhoto

/**
 * [org.tenshu.inference.PhotosLike] — embed the utterance, score it against every vector this
 * device holds, rank and cap. The `what` sibling of `PhotosNearImplementation`'s `where`,
 * deliberately the same shape.
 *
 * ⚠⚠ **Consent is checked before the text is embedded**, not after. Embedding the query is itself
 * an inference run, and a device whose owner has switched semantic search off should not be running
 * a model to discover it has nothing to search. The indexer makes the same check per *file*; here
 * one call is one unit of work, so once is right.
 *
 * ⚠⚠ Every refusal path throws [SemanticSearchUnavailable] instead of returning an empty list. See
 * that error: "nothing matched" and "this cannot run here" are different answers, and shipping the
 * second as the first hides a switched-off feature behind a plausible result.
 */
internal class PhotosLikeImplementation(
    private val registry: InferenceBackendRegistry,
    private val embeddings: EmbeddingStore,
    private val deviceId: String,
    /**
     * Which stored paths still name a live, untrashed file. A deleted photograph's vector
     * stays in the store until the next pass reconciles it, and must not be ranked meanwhile.
     * `null` ranks every stored vector — see `FaceGroupingComponents.livePaths`.
     */
    private val livePaths: LivePaths? = null,
    /**
     * Whether [embeddings] may be read for this query, asked before anything else; `null` asks
     * nothing. Where the owner of a realm-stamped store checks the stamp
     * ([InferenceStoreCustody.ensureCurrentRealm]), which clears another realm's vectors first.
     */
    private val storeGate: (suspend () -> Boolean)? = null,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val text = params?.get(org.tenshu.inference.PhotosLike.Params.TEXT)
            ?.let { (it as TString).get() }.orEmpty()
        val limit = params?.get(org.tenshu.inference.PhotosLike.Params.LIMIT)
            ?.let { (it as TInteger).get() } ?: 0L

        // ⚠ An empty query or a non-positive cap is a caller mistake, not an unavailable feature —
        // it returns nothing without claiming the device cannot search.
        if (limit <= 0L || text.isBlank()) return SimpleTList(emptyList<Data>()) as T

        // ⚠ Before the backend is asked: a store that may not be read is a device that cannot
        // search right now, whatever is bound. Asking is not running, as below.
        if (storeGate != null && !storeGate.invoke()) {
            unavailable(
                executionContext,
                SemanticSearchUnavailable.Reasons.NO_BACKEND,
                "This device's photo index cannot be used right now.",
            )
        }

        // ⚠ The backend is asked about FIRST, and that ordering is the refusal's meaning (§32). A
        // device with neither a model nor consent is told about the model: the consent sentence
        // names the switch, and on a device that can never run a model — iOS, web — it would send
        // the user to turn on something that changes nothing. Asking is not running: `backendFor`
        // reads no query and no photo, so nothing happens here before consent that consent governs.
        val backend = registry.backendFor(CAPABILITY)
            ?: unavailable(
                executionContext,
                SemanticSearchUnavailable.Reasons.NO_BACKEND,
                "No model on this device can embed images.",
            )
        if (!executionContext.hasInferenceConsent(CAPABILITY, deviceId)) {
            unavailable(
                executionContext,
                SemanticSearchUnavailable.Reasons.NO_CONSENT,
                "Semantic image search is switched off for this realm or this device.",
            )
        }

        // ⚠⚠ Checked BEFORE embedding the query. Running the model to rank against zero vectors is
        // work spent to produce a result indistinguishable from "nothing matched" — the exact
        // confusion this error exists to prevent.
        if (embeddings.size() == 0) {
            unavailable(
                executionContext,
                SemanticSearchUnavailable.Reasons.NOT_INDEXED,
                "No photos have been indexed on this device yet.",
            )
        }
        // ⚠ The same question for a store holding only vectors of photographs that are gone: its
        // size is not zero, but every candidate would be dropped below, and an empty success after
        // running the model reads as "nothing matched".
        if (livePaths != null && !anyLiveVector(executionContext)) {
            unavailable(
                executionContext,
                SemanticSearchUnavailable.Reasons.NOT_INDEXED,
                "None of the photos indexed on this device still exists.",
            )
        }

        val query = backend.embedText(text)
            ?: unavailable(
                executionContext,
                SemanticSearchUnavailable.Reasons.NO_TEXT_EMBEDDING,
                "The installed model cannot embed text, only images.",
            )

        // ⚠⚠ The store's stamp against the query's. Cosine across two models' spaces is meaningless
        // *and computable*, so nothing downstream would ever notice — this is the only place the
        // mismatch is visible, and `EmbeddingStore` refuses the write for the same reason.
        val held = embeddings.provenance()
        if (held != null && held != query.producedBy) {
            unavailable(
                executionContext,
                SemanticSearchUnavailable.Reasons.NOT_INDEXED,
                "Indexed with $held, searching with ${query.producedBy} — re-index required.",
            )
        }

        // ⚠⚠ **A relevance FLOOR, not just a cap.** Ranking everything and cutting at [limit] means
        // a query that matches nothing still returns [limit] photographs — and Photos badges each of
        // them "looks like this". Observed on the first device to run this for real: a two-photo
        // library returned both for "cat" AND for "xylophone", merely re-ordered.
        //
        // ⚠⚠ The floor is on the model's **probability**, never on the raw cosine. A cosine is not a
        // score (`models.md` §3.7.2): image↔text cosines over a real library span −0.13…0.123, so a
        // cautious-looking raw threshold either admits everything or nothing depending on a scale
        // nobody stated. [ScoreCalibration] is the model's own conversion, which is why it comes from
        // the backend rather than from a constant here.
        //
        // ⚠ A backend with no calibration cannot filter honestly, so it does not filter — today's
        // behaviour, kept deliberately rather than replaced by an invented number (§19.3).
        val calibration = backend.scoreCalibration()
        val ranked = embeddings.entries()
            .map { (path, vector) -> path to cosine(query.vector, vector) }
            .filter { (_, score) ->
                calibration == null || calibration.probability(score) >= SEMANTIC_RELEVANCE_FLOOR
            }
            // ⚠ Best first, so the cap drops the least similar rather than an arbitrary slice.
            .sortedByDescending { it.second }
            .let { best -> topLive(executionContext, best, limit.toInt()) }
            .map { (path, score) ->
                SimilarPhoto.create(
                    context = executionContext.context,
                    filePath = path,
                    scorePerMille = (score * 1_000).toInt().coerceIn(-1_000, 1_000),
                )
            }
            .toList()

        return SimpleTList(ranked) as T
    }

    /** Whether any stored vector belongs to a live photograph — chunk by chunk, stopping at the first. */
    private suspend fun anyLiveVector(executionContext: ExecutionContext): Boolean {
        val predicate = livePaths ?: return true
        return embeddings.entries().asSequence().map { it.first }.chunked(LIVE_PATHS_CHUNK)
            .any { chunk -> predicate.live(executionContext, chunk).isNotEmpty() }
    }

    /**
     * The first [limit] of [best] whose photograph still exists.
     *
     * ⚠ Asked best-first in chunks and stopped once the cap is full, so a search over a large library
     * looks up about [limit] paths rather than every vector it ranked — and a dead path costs a slot
     * to nobody, because the next live one moves up.
     */
    private suspend fun topLive(
        executionContext: ExecutionContext,
        best: List<Pair<String, Float>>,
        limit: Int,
    ): List<Pair<String, Float>> {
        val predicate = livePaths ?: return best.take(limit)
        val out = ArrayList<Pair<String, Float>>(minOf(limit, best.size))
        for (chunk in best.chunked(LIVE_PATHS_CHUNK)) {
            val live = predicate.liveAmong(executionContext, chunk.map { it.first })
            chunk.filterTo(out) { it.first in live }
            if (out.size >= limit) break
        }
        return out.take(limit)
    }

    private fun unavailable(
        executionContext: ExecutionContext,
        reason: String,
        message: String,
    ): Nothing = throw TenshuException(
        SemanticSearchUnavailable.create(
            context = executionContext.context,
            reason = reason,
            message = message,
        ),
    )

    /**
     * ⚠ Both sides are unit vectors — `ImageEmbedding` says so and `FakeInferenceBackend`
     * normalises — so cosine is a plain dot product. ⚠⚠ A backend that returned an un-normalised
     * vector would make this a *dot product*, which ranks by magnitude as much as by direction:
     * long vectors would win every search. That is a real hazard for a new backend, and it belongs
     * on the backend contract rather than in a defensive re-normalisation here, which would hide
     * it.
     */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    internal companion object {
        private val CAPABILITY = InferenceCapability.SEMANTIC_IMAGE_SEARCH

        /**
         * The model's own probability below which a photograph is **not** a match: cosine
         * **≈ 0.05998** under `VisionModel.PinnedSigLip`'s calibration (cosine 0.06 is admitted,
         * 0.0599 is not).
         *
         * ⚠⚠ **Measured on the pinned checkpoint** (2026-11-09, `inference-service.md` §35.1): the
         * fp32, ONNX (uint8 vision + int8 text) and Core ML fp16 pairs of
         * `siglip-base-patch16-256-multilingual`, scored against COCO val2017 (5,000 images, ground
         * truth), a 554-photo camera roll and the 22 vectors an iPhone had indexed. What it rests on:
         * - **True matches of a one-word query typically sit at cosine 0.06–0.10** — typically, not
         *   all: COCO's cats have a median of 0.085 but a 5th percentile of 0.038. On that iPhone,
         *   the five cat photographs scored 0.080–0.084 for "cat" and the best non-cat 0.021; the
         *   floor this replaces (cosine 0.0915, carried over from another checkpoint) returned none.
         * - **An object that is not in the library tops out below it.** Five instruments
         *   ("xylophone" …) over the camera roll peak at 0.030–0.057, and "xylophone" over the iPhone's
         *   library at 0.021: at this floor none returns a photograph, on any of the three pairs.
         * - At this floor a bare-word query over COCO keeps a median 57 % of its photographs (so a
         *   weak true match can still fall below it) at a median precision of 0.88, the same on
         *   Core ML and ONNX.
         *
         * ⚠ One absolute floor, not a rule relative to the best score or the library's median. A cut
         * relative to the top always admits the top, so it cannot return nothing for "xylophone"; one
         * relative to the median returns nothing for "cat" in a library that is mostly cats.
         *
         * ⚠ One constant for every platform. What was observed: on that 554-photo camera roll no
         * instrument crosses 0.06 on any of the three pairs. ⚠ The margin is thin next to the skew:
         * the worst instrument is ~0.003 below the floor, while ONNX minus Core ML for the same image
         * and query near the cut has a mean +0.002, sd 0.009–0.014 and |difference| ≤ 0.028 for
         * 95 % of pairs. Another library may put an absent object just over the cut on one platform.
         *
         * ⚠ Erring low on purpose: a floor that is slightly too generous shows a weak match, which
         * the user can dismiss. One that is too strict makes a photograph the user knows they own
         * unfindable, and they cannot tell that from it not being indexed.
         *
         * ⚠ A new checkpoint has new constants and owes a new measurement
         * (`PinnedSigLipCalibrationPinTest`).
         */
        const val SEMANTIC_RELEVANCE_FLOOR = 0.00197f
    }
}
