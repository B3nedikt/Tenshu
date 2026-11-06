package org.tenshu.inference.proxy.coreml

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tenshu.core.common.proxy.MediaWorkBudget
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.ImageEmbedding
import org.tenshu.inference.proxy.ImageEmbeddingOutcome
import org.tenshu.inference.proxy.ImageTag
import org.tenshu.inference.proxy.InferenceBackend
import org.tenshu.inference.proxy.IntentLabel
import org.tenshu.inference.proxy.ScoreCalibration
import org.tenshu.inference.proxy.TextRegion
import org.tenshu.inference.proxy.onnx.ImageDecoder
import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import org.tenshu.inference.proxy.onnx.SiglipTextTokenizer
import org.tenshu.inference.proxy.onnx.TowerGuards
import org.tenshu.inference.proxy.onnx.UnigramVocabulary
import org.tenshu.inference.proxy.onnx.VisionModel

/**
 * The artifact formats a build that binds [CoreMlInferenceBackend] can load — what Photos
 * contributes as `supportedModelRuntimes` (`ios-on-device-inference.md` §3.1, §3.4).
 *
 * ⚠⚠ Never contributed by a graph that does not bind the backend: the catalog filter would then
 * offer ~709 MB this process can never run (`ModelCatalog.kt`, `SUPPORTED_RUNTIMES`).
 */
val COREML_RUNTIMES: Set<String> = setOf("coreml")

/**
 * The pinned checkpoint on iOS: Core ML over the two fp16 towers
 * (`ios-on-device-inference.md` §3.2). `OnnxInferenceBackend`'s twin, with the same contract.
 *
 * ⚠⚠ **Bound in no graph.** Photos binds it once, at assembly (PR-5); until then nothing calls it.
 *
 * ⚠⚠ **One photo at a time.** A tower's mutex is held from the session lookup to the prediction:
 * `CoreMlSessionPort` is not thread-safe, a session must not be closed under a prediction, and the
 * decode and tensor buffers are reused from photo to photo. So the decode is serialised too, where
 * the ONNX twin decodes outside its lock.
 *
 * ⚠ The latch is **keyed to the compiled path**, unlike the twin's, which is never reset: here an
 * artifact can be replaced in a running process, and a latch left by the old one would refuse the
 * new one.
 *
 * @param locate the compiled vision tower, or `null` while there is none. ⚠⚠ It must answer only
 *   for a model whose ready marker exists ([compiledTower] does): an installed archive with no
 *   compiled model must not be claimed. Asked on every call, so an artifact that arrives or
 *   changes while the process runs is picked up.
 * @param locateVocabulary a name for the packed vocabulary, handed to [readVocabulary]. Both
 *   halves of the text side are needed: without either, [embedText] answers `null`.
 * @param ready the seam for PR-5's readiness state (§3.2, *Readiness*). `false` makes the backend claim
 *   nothing, fail an image and close its sessions on the next call, whatever [locate] says.
 * @param open [CoreMlSession.open] with the shipped compute units; a test passes a fake.
 */
class CoreMlInferenceBackend(
    private val locate: () -> String?,
    private val locateText: () -> String? = { null },
    private val locateVocabulary: () -> String? = { null },
    private val readVocabulary: (String) -> ByteArray? = { null },
    private val decoder: ImageDecoder = IosImageDecoder,
    private val model: VisionModel = VisionModel.PinnedSigLip,
    private val ready: () -> Boolean = { true },
    private val open: (compiledPath: String, spec: CoreMlTowerSpec) -> CoreMlSessionPort =
        { compiledPath, spec -> CoreMlSession.open(compiledPath, spec) },
    /** Where an image is decoded and embedded: the process's media lane. */
    private val lane: CoroutineDispatcher = MediaWorkBudget.lane,
    /** ⚠ Never the lane: a query is IPC serving, and must not queue behind an index pass. */
    private val textDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Awaited at the start of every embed, before a tower can be opened: opening one is where
     * memory is committed. A graph whose predecessor in this process is still unloading waits
     * here, so two pairs of towers are never resident. Cancellable: the caller's own bound applies.
     */
    private val beforeLoad: suspend () -> Unit = {},
    /**
     * Around every open of a tower ([sessionFor]): readiness's record of it (§14.19,
     * `AiReadiness.servingOpen`), so an open Photos does not survive is counted. A throw is an
     * open that did not happen. ⚠ Called under the tower's lock: it may take no lock that a
     * writer of readiness holds while it waits for this tower.
     */
    private val recordedOpen: suspend (role: String, open: () -> CoreMlSessionPort) -> CoreMlSessionPort = { _, opening -> opening() },
) : InferenceBackend {

    override val id: String get() = "core-ml"

    /** One tower's session and latch. Everything but [rejectedPath] is touched only under [lock]. */
    private class Tower(val spec: CoreMlTowerSpec) {
        val lock = Mutex()
        var session: CoreMlSessionPort? = null

        /**
         * ⚠⚠ Latched, because a model that is not this tower is a property of the artifact, not of
         * the call: a library pass would otherwise re-open and re-reject it once per photograph.
         * Read without the lock by [supports].
         */
        @Volatile
        var rejectedPath: String? = null
    }

    private val vision = Tower(CoreMlTowerSpec.vision(model))
    private val text = Tower(CoreMlTowerSpec.text(model))

    // Under the text tower's lock.
    private var tokenizer: SiglipTextTokenizer? = null
    private var tokenizerSource: String? = null

    // Under the vision tower's lock. 1.0 MB and 0.8 MB at side 256, overwritten whole by each photo.
    private var pixels: IntArray? = null
    private var tensor: FloatArray? = null

    override fun scoreCalibration(): ScoreCalibration? = model.calibration

    /** ⚠ Vision-only, as the twin's is: whether a query can be embedded is [embedText]'s answer. */
    override fun supports(capability: InferenceCapability): Boolean {
        if (capability != InferenceCapability.SEMANTIC_IMAGE_SEARCH || !ready()) return false
        val path: String = locate() ?: return false
        return path != vision.rejectedPath
    }

    override suspend fun embedImage(bytes: ByteArray): ImageEmbedding? =
        (embedImageOutcome(bytes) as? ImageEmbeddingOutcome.Embedded)?.embedding

    /**
     * ⚠⚠ Only a decoder refusal is [ImageEmbeddingOutcome.Unreadable], and only from a backend
     * that could have run: the session is asked for first. No model, a model that will not load,
     * a rejected one, a failed prediction and an unusable vector are all
     * [ImageEmbeddingOutcome.Failed], which an indexer never remembers against the photograph.
     */
    override suspend fun embedImageOutcome(bytes: ByteArray): ImageEmbeddingOutcome = withContext(lane) {
        // ⚠ Before the tower's lock, never under it: the wait can be long, and it is not this tower's.
        beforeLoad()
        vision.lock.withLock {
            val session: CoreMlSessionPort = sessionFor(vision, locate)
                ?: return@withContext ImageEmbeddingOutcome.Failed

            val side: Int = model.side
            val argb: IntArray = pixels ?: IntArray(side * side).also { pixels = it }
            if (!decoder.decodeInto(bytes, side, model.profile.centreCrop, argb)) {
                return@withContext ImageEmbeddingOutcome.Unreadable
            }
            val input: FloatArray = ImagePreprocessing.preprocessPixelsInto(
                argb,
                tensor ?: FloatArray(3 * side * side).also { tensor = it },
                side,
                model.profile,
            )
            // ⚠ The session copies `input` into Core ML's own buffer before it returns, so the
            // reused tensor never outlives this call.
            val vector: FloatArray = usable { session.predict(input) }
                ?: return@withContext ImageEmbeddingOutcome.Failed
            // ⚠⚠ Asked again AFTER the prediction: it is a blocking call that can outlast a
            // teardown or a realm reset, and by then the stores are given up or cleared. The
            // vector of a backend that no longer serves never leaves it, so nothing can store it.
            if (!ready()) return@withContext ImageEmbeddingOutcome.Failed
            ImageEmbeddingOutcome.Embedded(ImageEmbedding(ImagePreprocessing.normalise(vector), model.provenance))
        }
    }

    /**
     * ⚠ `null` whenever the text half is absent or cannot answer, which `photosLike` turns into
     * `NO_TEXT_EMBEDDING` rather than an empty result.
     */
    override suspend fun embedText(text: String): ImageEmbedding? = withContext(textDispatcher) {
        if (text.isBlank()) return@withContext null
        beforeLoad()
        this@CoreMlInferenceBackend.text.lock.withLock {
            val tower: Tower = this@CoreMlInferenceBackend.text
            if (!ready()) {
                close(tower)
                return@withContext null
            }
            // ⚠ The vocabulary first: a text tower costs seconds to open the first time, and
            // without a vocabulary there is nothing to feed it.
            val encoder: SiglipTextTokenizer = tokenizerNow() ?: return@withContext null
            val session: CoreMlSessionPort = sessionFor(tower, locateText) ?: return@withContext null
            val ids: IntArray = encoder.encode(text)
            val vector: FloatArray = usable { session.predict(ids) } ?: return@withContext null
            // As for an image: a query that outlasted its runtime is answered with nothing.
            if (!ready()) return@withContext null
            ImageEmbedding(ImagePreprocessing.normalise(vector), model.provenance)
        }
    }

    /**
     * Opens the text tower and parses the vocabulary with no query to answer: the launch warm-up
     * (§3.2). A query that arrives meanwhile waits on the same lock and finds the session open.
     *
     * ⚠ Everything [embedText] checks first is checked here: [beforeLoad], then [ready]. A
     * backend that does not serve opens nothing.
     *
     * @return whether the text half is now open and can tokenise.
     */
    suspend fun warmText(): Boolean = withContext(textDispatcher) {
        beforeLoad()
        text.lock.withLock {
            if (!ready()) {
                close(text)
                return@withContext false
            }
            tokenizerNow() ?: return@withContext false
            sessionFor(text, locateText) != null
        }
    }

    override suspend fun tagImage(bytes: ByteArray, vocabulary: List<String>): List<ImageTag> = emptyList()

    override suspend fun recognizeText(bytes: ByteArray): List<TextRegion> = emptyList()

    override suspend fun classifyIntent(utterance: String, labels: List<IntentLabel>): String? = null

    /**
     * Closes the text tower and drops the vocabulary: search was switched off (§14.18.15). ⚠ The
     * vision tower stays: an index pass is not search's. ⚠ Waits for a query that holds the tower.
     */
    suspend fun closeText() {
        text.lock.withLock {
            close(text)
            tokenizer = null
            tokenizerSource = null
        }
    }

    /**
     * Closes both sessions and drops the vocabulary and the buffers: the last step of leaving
     * *ready*, after readiness is flipped and a running pass is cancelled (§3.2), and what a
     * memory warning asks for. A prediction in flight finishes first. The next call reopens.
     */
    suspend fun closeSessions() {
        vision.lock.withLock {
            close(vision)
            pixels = null
            tensor = null
        }
        text.lock.withLock {
            close(text)
            tokenizer = null
            tokenizerSource = null
        }
    }

    /**
     * The session for whatever [locate] names now, opened if it is not the one held. Under
     * [tower]'s lock.
     */
    private suspend fun sessionFor(tower: Tower, locate: () -> String?): CoreMlSessionPort? {
        val path: String? = if (ready()) locate() else null
        val held: CoreMlSessionPort? = tower.session
        if (held != null && held.compiledPath == path) return held
        // ⚠ Another path is another artifact, and no path is no artifact: never answer from the
        // model that was there before.
        close(tower)
        if (path == null || path == tower.rejectedPath) return null
        // A latch is a verdict on one path. This is another.
        tower.rejectedPath = null

        val opened: CoreMlSessionPort = try {
            recordedOpen(tower.spec.role) { open(path, tower.spec) }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (rejected: CoreMlRejectedModelException) {
            tower.rejectedPath = path
            return null
        } catch (failure: Throwable) {
            // ⚠ Not latched: a model that would not load this time says nothing about the next,
            // and neither does one whose open could not be recorded first (nothing was opened).
            return null
        }
        tower.session = opened
        return opened
    }

    private fun close(tower: Tower) {
        val held: CoreMlSessionPort = tower.session ?: return
        tower.session = null
        runCatching { held.close() }
    }

    /** The tokenizer for the vocabulary [locateVocabulary] names now. Under the text tower's lock. */
    private fun tokenizerNow(): SiglipTextTokenizer? {
        val source: String = locateVocabulary() ?: return null
        tokenizer?.takeIf { tokenizerSource == source }?.let { return it }
        tokenizer = null
        tokenizerSource = null
        // ⚠ 3.1 MB parsed into a 250 100-entry map: once per vocabulary, never per query.
        val built: SiglipTextTokenizer = runCatching {
            val bytes: ByteArray = readVocabulary(source) ?: return null
            val vocabulary: UnigramVocabulary = UnigramVocabulary.read(bytes) ?: return null
            SiglipTextTokenizer(vocabulary, model.textSequenceLength)
        }.getOrNull() ?: return null
        tokenizer = built
        tokenizerSource = source
        return built
    }

    /**
     * A prediction's vector, or `null` when it failed or cannot be an embedding.
     *
     * ⚠⚠ [TowerGuards.unusableReason] on **every** vector this backend returns, here and not only
     * in [CoreMlSession]: a NaN normalises to NaN and a zero vector to itself, and either would be
     * stored as an indexed photo that no search can find.
     */
    private inline fun usable(predict: () -> FloatArray): FloatArray? {
        val vector: FloatArray = try {
            predict()
        } catch (failure: Throwable) {
            return null
        }
        return vector.takeIf { TowerGuards.unusableReason(it, model.dimensions) == null }
    }

    companion object {
        /**
         * A locator over [cache]: the compiled [role] for the installed archive's digest, and
         * `null` until that digest's ready marker exists.
         *
         * @param archiveDigest the installed archive's sha256, or `null` when nothing is installed.
         */
        fun compiledTower(cache: CoreMlModelCache, role: String, archiveDigest: () -> String?): () -> String? =
            { archiveDigest()?.let { cache.compiledPath(role, it) } }
    }
}
