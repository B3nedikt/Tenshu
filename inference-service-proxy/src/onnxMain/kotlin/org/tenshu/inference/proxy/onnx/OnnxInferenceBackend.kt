package org.tenshu.inference.proxy.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.jvm.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tenshu.core.common.proxy.MediaWorkBudget
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.ImageEmbedding
import org.tenshu.inference.proxy.ScoreCalibration
import org.tenshu.inference.proxy.ImageEmbeddingOutcome
import org.tenshu.inference.proxy.ImageTag
import org.tenshu.inference.proxy.InferenceBackend
import org.tenshu.inference.proxy.IntentLabel
import org.tenshu.inference.proxy.TextRegion

/**
 * **The real backend** (IN-3b): ONNX Runtime over the pinned vision tower, on JVM and Android.
 *
 * ⭐ It replaces `InferenceBackendRegistry.Empty` and nothing else — `MediaIndexer`, `EmbeddingStore`
 * and `photosLike` were built and proved against `FakeInferenceBackend` (§16), so this is the
 * backend swap §16 said it would be.
 *
 * ⚠⚠ **The image half only.** R3 defers the text tower, so [embedText] returns `null` and a text
 * query refuses with `SemanticSearchUnavailable.NO_TEXT_EMBEDDING` — *"the installed model cannot
 * embed text, only images"*. That reason already existed, because the design anticipated exactly
 * this state. Indexing is fully functional: vectors are produced, stored and stamped, and the day
 * the text tower lands every photo already indexed is searchable with no re-embed.
 *
 * ⚠⚠ **CPU, deliberately, and this is a measurement rather than a default.** §15.12.3: on a
 * OnePlus CPH2581 the NNAPI EP ran the graph and lost to the plain CPU EP by 44–70 %, XNNPACK by
 * 12–26 %, across three passes. IN-D13's ladder is a list of things to *measure*, not a preference
 * order. ⚠ It is also a hard constraint here: `addNnapi` ships only in the Android AAR and this
 * file compiles for both artifacts, so an accelerated provider needs a per-platform seam — worth
 * building the day a device is found where one wins, and not before.
 *
 * ⚠ One session for the process, created on first use. A 100 MB model is memory-mapped by ORT
 * rather than read onto the heap, which is why [locate] hands over a **path**: `Control` on Android
 * has a 256 MB heap and has already died of a 257 MB read once.
 */
class OnnxInferenceBackend internal constructor(
    private val decoder: ImageDecoder,
    private val locate: () -> String?,
    private val model: VisionModel = VisionModel.PinnedSigLip,
    private val locateText: () -> String? = { null },
    private val locateVocabulary: () -> String? = { null },
    private val lane: CoroutineDispatcher = MediaWorkBudget.lane,
    /** Opens the tower at a path, throwing as `createSession` does. A test passes a fake. */
    private val open: (path: String) -> OnnxTower,
) : InferenceBackend {

    constructor(
        decoder: ImageDecoder,
        /**
         * Where this device's copy of [model] is, or `null` when it has none.
         *
         * ⚠ Called per [supports] rather than captured once, so a model that arrives *after* the
         * proxy started is picked up without a restart — which is what IN-2c's install flow will need.
         */
        locate: () -> String?,
        model: VisionModel = VisionModel.PinnedSigLip,
        /**
         * R3's text tower and its packed vocabulary — both absent on a device that only indexes.
         *
         * ⚠⚠ Either one alone is useless: a graph with no vocabulary cannot be fed and a vocabulary
         * with no graph has nothing to feed. [embedText] therefore requires both and answers `null`
         * otherwise, which `photosLike` renders as `NO_TEXT_EMBEDDING` — "the installed model cannot
         * embed text, only images".
         */
        locateText: () -> String? = { null },
        locateVocabulary: () -> String? = { null },
        environment: () -> OrtEnvironment = { OrtEnvironment.getEnvironment() },
        /** D4: every session — vision and text — opens with the capped, non-spinning options. */
        sessions: OnnxSessionFactory = OnnxSessionFactory(environment = environment),
        /**
         * Where [embedImageOutcome]'s decode and inference run: the process's media lane (D4).
         * ⚠ [embedText] does not use it. A query is IPC serving, and must not queue behind an index pass.
         */
        lane: CoroutineDispatcher = MediaWorkBudget.lane,
    ) : this(
        decoder = decoder,
        locate = locate,
        model = model,
        locateText = locateText,
        locateVocabulary = locateVocabulary,
        lane = lane,
        open = { path -> OrtTower(sessions.open(path), environment) },
    )

    override val id: String get() = "onnx-runtime"

    private val lock = Mutex()
    private var session: OnnxTower? = null
    private var sessionPath: String? = null

    private val textLock = Mutex()
    private var textSession: OnnxTower? = null
    private var textSessionPath: String? = null
    private var tokenizer: SiglipTextTokenizer? = null
    private var tokenizerPath: String? = null

    @Volatile
    private var textRejected = false

    /**
     * ⚠⚠ Latched, because the failure it records is a property of the *artifact*, not of the call.
     * Retrying a model whose shape is wrong would re-open, re-check and re-fail once per photograph
     * in a library pass — thousands of identical failures where one answer was needed.
     */
    @Volatile
    private var rejected = false

    /**
     * ⚠⚠ Only what this artifact can actually do. Zero-shot tagging needs the *text* tower to embed
     * the vocabulary, so [InferenceCapability.CONTENT_TAGGING] is false until R3 — claiming it and
     * returning no tags would make a switched-on capability indistinguishable from a model with no
     * opinions.
     */
    /** ⚠ The loaded checkpoint's own constants, or `null` for one whose graph nobody has read. */
    override fun scoreCalibration(): ScoreCalibration? = model.calibration

    override fun supports(capability: InferenceCapability): Boolean =
        capability == InferenceCapability.SEMANTIC_IMAGE_SEARCH && !rejected && locate() != null

    override suspend fun embedImage(bytes: ByteArray): ImageEmbedding? =
        (embedImageOutcome(bytes) as? ImageEmbeddingOutcome.Embedded)?.embedding

    /**
     * ⚠⚠ Only a decoder refusal is [ImageEmbeddingOutcome.Unreadable]. A session that will not open
     * — a half-copied artifact, memory pressure — a run that throws and a vector [usable] refuses
     * are [ImageEmbeddingOutcome.Failed]: facts about this moment, which an indexer must not
     * remember against the photograph (`inference-service.md` §32.3).
     *
     * ⚠⚠ A refused vector sets no latch: [rejected] is a verdict on the artifact's shape, and one
     * bad output must not make [supports] answer `false`.
     */
    override suspend fun embedImageOutcome(bytes: ByteArray): ImageEmbeddingOutcome = withContext(lane) {
        val session = session() ?: return@withContext ImageEmbeddingOutcome.Failed
        // ⚠ Decode failure is ordinary — a library holds files that are not photographs.
        val pixels = decoder.decode(bytes, model.side, model.profile.centreCrop)
            ?: return@withContext ImageEmbeddingOutcome.Unreadable
        val input = ImagePreprocessing.preprocessPixels(pixels, model.side, model.profile)
        val shape = longArrayOf(1, 3, model.side.toLong(), model.side.toLong())

        val vector = usable { session.pooled(input, shape) }
            ?: return@withContext ImageEmbeddingOutcome.Failed

        // ⚠⚠ Normalised here, once, because `photosLike` scores with a bare dot product: an
        // un-normalised vector ranks by magnitude as much as by direction and looks plausible.
        ImageEmbeddingOutcome.Embedded(ImageEmbedding(ImagePreprocessing.normalise(vector), model.provenance))
    }

    /**
     * The **query** half (R3): tokenize, run the text tower, return a vector in the *same* space as
     * [embedImage].
     *
     * ⚠⚠ **Same space is a property of the checkpoint, not of this code.** Nothing here can verify
     * that the text tower beside the vision tower is its sibling — a mismatched pair produces
     * cosines that are computable and meaningless (§16.1), and the ranking would look ordinary.
     * The shape checks below catch a crude mismatch; a digest against the signed catalog (IN-2c) is
     * what actually closes it.
     *
     * ⚠ `null` whenever the text half is absent, which is the ordinary state of a device that only
     * indexes — `photosLike` turns it into `NO_TEXT_EMBEDDING` rather than an empty result. The same
     * `null` for a vocabulary no tokenizer can be built from and for a vector [usable] refuses:
     * neither latches, and neither is thrown at the caller.
     */
    override suspend fun embedText(text: String): ImageEmbedding? = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext null
        val session = textSession() ?: return@withContext null
        val tokenizer = tokenizer ?: return@withContext null

        val ids = tokenizer.encode(text)
        val shape = longArrayOf(1, ids.size.toLong())
        val vector = usable { session.pooled(LongArray(ids.size) { ids[it].toLong() }, shape) }
            ?: return@withContext null

        ImageEmbedding(ImagePreprocessing.normalise(vector), model.provenance)
    }

    /** ⚠ Zero-shot tagging is the text tower scoring a vocabulary; without it there is nothing to say. */
    override suspend fun tagImage(bytes: ByteArray, vocabulary: List<String>): List<ImageTag> = emptyList()

    override suspend fun recognizeText(bytes: ByteArray): List<TextRegion> = emptyList()

    override suspend fun classifyIntent(utterance: String, labels: List<IntentLabel>): String? = null

    private suspend fun session(): OnnxTower? {
        if (rejected) return null
        val path = locate() ?: return null
        lock.withLock {
            val open = session
            if (open != null && sessionPath == path) return open
            // ⚠ A different path means a different artifact — close the old one rather than
            // leaking a 100 MB mapping and answering with the previous model's space.
            open?.let { runCatching { it.close() } }
            session = null
            sessionPath = null

            val created = runCatching { open(path) }.getOrNull() ?: return null

            if (!accepts(created)) {
                runCatching { created.close() }
                rejected = true
                return null
            }
            session = created
            sessionPath = path
            return created
        }
    }

    private suspend fun textSession(): OnnxTower? {
        if (textRejected) return null
        val path = locateText() ?: return null
        val vocabularyPath = locateVocabulary() ?: return null
        textLock.withLock {
            if (tokenizerPath != vocabularyPath) {
                // ⚠ A 3.1 MB parse building a 250 100-entry map — once per vocabulary, never per
                // query, and only on a device that actually searches.
                val bytes = runCatching { File(vocabularyPath).readBytes() }.getOrNull() ?: return null
                val vocabulary = UnigramVocabulary.read(bytes) ?: return null
                // ⚠ A vocabulary with no `</s>` has no padding id, and the tokenizer refuses to guess
                // one. That is no text, as on iOS, not an exception out of a query. Not latched.
                tokenizer = runCatching { SiglipTextTokenizer(vocabulary, model.textSequenceLength) }
                    .getOrNull() ?: return null
                tokenizerPath = vocabularyPath
            }
            val open = textSession
            if (open != null && textSessionPath == path) return open
            open?.let { runCatching { it.close() } }
            textSession = null
            textSessionPath = null

            val created = runCatching { open(path) }.getOrNull() ?: return null

            if (!acceptsText(created)) {
                runCatching { created.close() }
                textRejected = true
                return null
            }
            textSession = created
            textSessionPath = path
            return created
        }
    }

    /**
     * ⚠ The text tower's own shape guard: the sequence length it was exported at, and a pooled
     * output of the width the image side produces. A tower expecting 32 tokens fed 64 does not
     * fail — it embeds a truncated sentence.
     */
    private fun acceptsText(session: OnnxTower): Boolean = accepts(session, TowerGuards.textInput(model))

    /**
     * ⚠⚠ **The guard that keeps a stamp honest.** Until the catalog is wired, the artifact at
     * [locate] is *asserted* to be [model] by its filename alone — and a vector from the wrong
     * model, stamped as this one, is the failure mode §16.1 calls computable-and-meaningless: the
     * arithmetic succeeds, the results are noise, and nothing downstream can notice.
     *
     * So the two things ORT will tell us for free are checked: the input's spatial size (feeding a
     * 256-patch model 224² pixels returns a vector rather than an error) and the presence of
     * `pooler_output` (SigLIP pools with a trained attention head; mean-pooling `last_hidden_state`
     * is a *different embedding space* that would also have produced 768 plausible numbers).
     */
    private fun accepts(session: OnnxTower): Boolean = accepts(session, TowerGuards.visionInput(model))

    /** ⚠ The arithmetic is [TowerGuards]', shared with the Core ML backend. A dynamic axis reads as -1. */
    private fun accepts(session: OnnxTower, expected: List<Int?>): Boolean {
        if (TowerGuards.outputRejection(session.outputNames) != null) return false
        val shape = session.inputShape ?: return false
        return TowerGuards.inputShapeRejection(shape, expected, dynamicAxes = true) == null
    }

    /**
     * A run's pooled vector, or `null` when the run threw, had no pooled output, or the vector
     * cannot be an embedding.
     *
     * ⚠⚠ [TowerGuards.unusableReason] on **every** vector this backend returns, as the Core ML twin
     * does: a NaN normalises to NaN and a zero vector to itself, and either would be stored as an
     * indexed photo that no search can find. The width check is in there too.
     */
    private inline fun usable(run: () -> FloatArray?): FloatArray? {
        val vector = runCatching(run).getOrNull() ?: return null
        return vector.takeIf { TowerGuards.unusableReason(it, model.dimensions) == null }
    }
}

/**
 * One opened tower, as the values [OnnxInferenceBackend] reads and the two runs it makes.
 *
 * ⚠ The seam a test fakes: an `OrtSession` cannot be built without a model file, so what the backend
 * does with a run's *output* was untestable in the gate.
 */
internal interface OnnxTower {
    val outputNames: Collection<String>

    /** The first input's shape, a dynamic axis as -1; `null` when it is not a tensor. */
    val inputShape: List<Long>?

    /** The pooled output for a vision input, unchecked; `null` when the run has none. Throws as ORT does. */
    fun pooled(pixels: FloatArray, shape: LongArray): FloatArray?

    /** The pooled output for a text input, unchecked; `null` when the run has none. Throws as ORT does. */
    fun pooled(ids: LongArray, shape: LongArray): FloatArray?

    fun close()
}

private class OrtTower(
    private val session: OrtSession,
    private val environment: () -> OrtEnvironment,
) : OnnxTower {

    override val outputNames: Collection<String> get() = session.outputNames

    override val inputShape: List<Long>?
        get() = (session.inputInfo[session.inputNames.firstOrNull()]?.info as? TensorInfo)?.shape?.toList()

    override fun pooled(pixels: FloatArray, shape: LongArray): FloatArray? =
        OnnxTensor.createTensor(environment(), FloatBuffer.wrap(pixels), shape).use(::run)

    override fun pooled(ids: LongArray, shape: LongArray): FloatArray? =
        OnnxTensor.createTensor(environment(), LongBuffer.wrap(ids), shape).use(::run)

    override fun close() = session.close()

    private fun run(tensor: OnnxTensor): FloatArray? =
        session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
            result.forEach { entry ->
                if (entry.key == TowerGuards.POOLED_OUTPUT) {
                    return (entry.value.value as? Array<*>)?.firstOrNull() as? FloatArray
                }
            }
            null
        }
}
