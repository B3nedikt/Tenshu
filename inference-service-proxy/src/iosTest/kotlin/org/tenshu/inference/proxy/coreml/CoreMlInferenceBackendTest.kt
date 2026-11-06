package org.tenshu.inference.proxy.coreml

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.ImageEmbedding
import org.tenshu.inference.proxy.ImageEmbeddingOutcome
import org.tenshu.inference.proxy.catalog.TarTestEntry
import org.tenshu.inference.proxy.catalog.testTar
import org.tenshu.inference.proxy.onnx.ImageDecoder
import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import org.tenshu.inference.proxy.onnx.VisionModel
import org.tenshu.inference.proxy.onnx.packedVocabulary
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [CoreMlInferenceBackend]'s own guards, against a fake [CoreMlSessionPort]
 * (`ios-on-device-inference.md` §3.2, §5 PR-2). No model is loaded: what is under test is what the
 * backend does around a session, which is the half that is silently wrong when it drifts from
 * `OnnxInferenceBackend`.
 *
 * ⚠ Each guard here was removed once and its case seen red (`ios-on-device-inference.md` §10.5).
 */
@OptIn(ExperimentalForeignApi::class)
class CoreMlInferenceBackendTest {

    private val model = VisionModel.PinnedSigLip
    private val plane: Int = model.side * model.side

    /** A session that records what it was fed and answers what the test says. */
    private class FakeSession(
        override val compiledPath: String,
        val spec: CoreMlTowerSpec,
        private val answer: () -> FloatArray,
    ) : CoreMlSessionPort {
        val floatInputs = ArrayList<FloatArray>()
        val idInputs = ArrayList<IntArray>()
        var closed = false

        override fun predict(floats: FloatArray): FloatArray {
            check(!closed) { "predicted on a closed session" }
            // A copy: the backend reuses its tensor, and the test compares one call with the next.
            floatInputs += floats.copyOf()
            return answer()
        }

        override fun predict(ids: IntArray): FloatArray {
            check(!closed) { "predicted on a closed session" }
            idInputs += ids.copyOf()
            return answer()
        }

        override fun close() {
            closed = true
        }
    }

    /** Stands in for [CoreMlSession.open]: every attempt is recorded, and a path can be made to fail. */
    private inner class Opener {
        val attempts = ArrayList<Pair<String, String>>()
        val sessions = ArrayList<FakeSession>()
        val failures = HashMap<String, () -> Throwable>()
        var answer: () -> FloatArray = { FloatArray(model.dimensions) { 0.5f + it } }

        fun open(path: String, spec: CoreMlTowerSpec): CoreMlSessionPort {
            attempts += path to spec.role
            failures[path]?.let { throw it() }
            return FakeSession(path, spec) { answer() }.also { sessions += it }
        }

        fun opened(role: String): List<FakeSession> = sessions.filter { it.spec.role == role }
    }

    /** A decoder that paints the whole image the colour its first byte names, and refuses `!`. */
    private class FakeDecoder : ImageDecoder {
        val targets = ArrayList<IntArray>()

        override fun decode(bytes: ByteArray, size: Int, centreCrop: Boolean): IntArray? =
            IntArray(size * size).takeIf { decodeInto(bytes, size, centreCrop, it) }

        override fun decodeInto(bytes: ByteArray, size: Int, centreCrop: Boolean, target: IntArray): Boolean {
            if (bytes.isEmpty() || bytes[0] == '!'.code.toByte()) return false
            targets += target
            target.fill(colourOf(bytes))
            return true
        }

        companion object {
            fun colourOf(bytes: ByteArray): Int = when (bytes[0].toInt().toChar()) {
                'r' -> 0xFFFF0000.toInt()
                'g' -> 0xFF00FF00.toInt()
                else -> 0xFF0000FF.toInt()
            }
        }
    }

    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatched = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched++
            block.run()
        }
    }

    private val red: ByteArray = "red".encodeToByteArray()
    private val green: ByteArray = "green".encodeToByteArray()
    private val notAnImage: ByteArray = "!".encodeToByteArray()

    private val opener = Opener()
    private val decoder = FakeDecoder()
    private var visionPath: String? = "/models/a/vision.mlmodelc"
    private var textPath: String? = "/models/a/text.mlmodelc"
    private var vocabularySource: String? = "vocabulary-a"
    private var isReady = true
    private val vocabularyReads = ArrayList<String>()
    private var vocabularyBytes: (String) -> ByteArray? = {
        packedVocabulary(
            listOf("<pad>" to 0f, "</s>" to 0f, "<unk>" to 0f, "▁" to -3f, "▁a" to -1f, "▁dog" to -1f),
        )
    }
    private val lane = CountingDispatcher()
    private val textDispatcher = CountingDispatcher()

    private fun backend(): CoreMlInferenceBackend = CoreMlInferenceBackend(
        locate = { visionPath },
        locateText = { textPath },
        locateVocabulary = { vocabularySource },
        readVocabulary = { source -> vocabularyReads += source; vocabularyBytes(source) },
        decoder = decoder,
        model = model,
        ready = { isReady },
        open = opener::open,
        lane = lane,
        textDispatcher = textDispatcher,
    )

    private fun CoreMlInferenceBackend.image(bytes: ByteArray): ImageEmbeddingOutcome = runBlocking { embedImageOutcome(bytes) }

    private fun CoreMlInferenceBackend.text(query: String): ImageEmbedding? = runBlocking { embedText(query) }

    private fun norm(vector: FloatArray): Float = sqrt(vector.fold(0f) { sum, v -> sum + v * v })

    // ------------------------------------------------------------------ what it claims

    @Test
    fun withNoModelItClaimsNothingAndAnImageIsAFailure() {
        visionPath = null
        val backend = backend()

        InferenceCapability.entries.forEach { assertFalse(backend.supports(it), "claimed $it with no model") }
        // ⚠⚠ No model is a failure of the MODEL, never a verdict on the photo, even for bytes no
        // decoder would read: an indexer remembers `Unreadable` against a file for good.
        assertEquals(ImageEmbeddingOutcome.Failed, backend.image(red))
        assertEquals(ImageEmbeddingOutcome.Failed, backend.image(notAnImage))
        assertNull(runBlocking { backend.embedImage(red) })
        assertTrue(opener.attempts.isEmpty())
    }

    @Test
    fun itClaimsOnlySemanticImageSearch() = runBlocking {
        val backend = backend()

        InferenceCapability.entries.forEach { capability ->
            assertEquals(capability == InferenceCapability.SEMANTIC_IMAGE_SEARCH, backend.supports(capability), "$capability")
        }
        assertEquals(emptyList(), backend.tagImage(red, listOf("cat")))
        assertEquals(emptyList(), backend.recognizeText(red))
        assertEquals("core-ml", backend.id)
        assertEquals(model.calibration, backend.scoreCalibration())
    }

    // ------------------------------------------------------------------ Unreadable vs Failed

    @Test
    fun aDecoderRefusalIsUnreadableAndEveryOtherNoVectorIsAFailure() {
        val backend = backend()

        assertEquals(ImageEmbeddingOutcome.Unreadable, backend.image(notAnImage))
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))
        // The refusal reached no model.
        assertEquals(1, opener.opened("vision").single().floatInputs.size)
    }

    @Test
    fun aModelThatWillNotLoadIsAFailureAndIsTriedAgain() {
        val backend = backend()
        opener.failures[visionPath!!] = { CoreMlLoadException("memory pressure") }

        assertEquals(ImageEmbeddingOutcome.Failed, backend.image(red))
        // ⚠ Bytes that are not an image too: the decoder's verdict is only reported by a backend
        // that could have run.
        assertEquals(ImageEmbeddingOutcome.Failed, backend.image(notAnImage))
        // Not latched: a load that failed this time says nothing about the next.
        assertTrue(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
        assertEquals(2, opener.attempts.size)

        opener.failures.clear()
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))
    }

    @Test
    fun aFailedPredictionIsAFailureAndTheSessionIsKept() {
        val backend = backend()
        opener.answer = { throw CoreMlPredictionException("the prediction failed") }

        assertEquals(ImageEmbeddingOutcome.Failed, backend.image(red))
        assertNull(backend.text("a dog"))

        opener.answer = { FloatArray(model.dimensions) { 1f } }
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))
        assertNotNull(backend.text("a dog"))
        assertEquals(2, opener.attempts.size, "one open a tower: a failed prediction is not a reason to reload")
    }

    // ------------------------------------------------------------------ the latch

    @Test
    fun aRejectedModelIsLatchedAndNeverReopened() {
        val backend = backend()
        opener.failures[visionPath!!] = { CoreMlRejectedModelException("another side") }

        repeat(5) { assertEquals(ImageEmbeddingOutcome.Failed, backend.image(red)) }

        // ⚠⚠ One answer, not one per photograph of a library pass.
        assertEquals(1, opener.attempts.size)
        assertFalse(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH), "kept claiming a rejected artifact")
    }

    @Test
    fun aRejectedTextTowerIsLatchedAndLeavesTheVisionTowerAlone() {
        val backend = backend()
        opener.failures[textPath!!] = { CoreMlRejectedModelException("another sequence length") }

        repeat(3) { assertNull(backend.text("a dog")) }

        assertEquals(listOf(textPath!! to "text"), opener.attempts)
        assertTrue(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))
    }

    /**
     * ⚠⚠ Where this backend must NOT copy its twin: an artifact can be replaced while the process
     * runs, and a latch left by the old one would refuse the new one for good.
     */
    @Test
    fun theLatchIsAVerdictOnOnePathAndANewArtifactIsTried() {
        val backend = backend()
        val rejected: String = visionPath!!
        opener.failures[rejected] = { CoreMlRejectedModelException("another side") }
        assertEquals(ImageEmbeddingOutcome.Failed, backend.image(red))
        assertFalse(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH))

        visionPath = "/models/b/vision.mlmodelc"

        assertTrue(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH), "the old artifact's latch refused the new one")
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))
        assertEquals(listOf(rejected, "/models/b/vision.mlmodelc"), opener.attempts.map { it.first })
    }

    // ------------------------------------------------------------------ sessions

    @Test
    fun oneSessionServesEveryCallOnAPath() {
        val backend = backend()

        repeat(4) { assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red)) }
        repeat(3) { assertNotNull(backend.text("a dog")) }

        assertEquals(listOf(visionPath!! to "vision", textPath!! to "text"), opener.attempts)
        assertEquals(4, opener.opened("vision").single().floatInputs.size)
        assertEquals(3, opener.opened("text").single().idInputs.size)
    }

    @Test
    fun aPathChangeClosesTheOldSessionAndOpensTheNew() {
        val backend = backend()
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))
        assertNotNull(backend.text("a dog"))
        val oldVision: FakeSession = opener.opened("vision").single()
        val oldText: FakeSession = opener.opened("text").single()

        visionPath = "/models/b/vision.mlmodelc"
        textPath = "/models/b/text.mlmodelc"
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))
        assertNotNull(backend.text("a dog"))

        // ⚠ Closed, and never asked again: no prediction is served from the model that was there before.
        assertTrue(oldVision.closed, "the old vision session was left open")
        assertTrue(oldText.closed, "the old text session was left open")
        assertEquals(1, oldVision.floatInputs.size)
        assertEquals(1, oldText.idInputs.size)
        assertEquals("/models/b/vision.mlmodelc", opener.opened("vision").last().compiledPath)
        assertEquals(1, opener.opened("vision").last().floatInputs.size)
        assertEquals("/models/b/text.mlmodelc", opener.opened("text").last().compiledPath)
        assertFalse(opener.opened("vision").last().closed)
    }

    @Test
    fun aModelThatIsRemovedClosesItsSession() {
        val backend = backend()
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))

        visionPath = null

        assertFalse(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
        assertEquals(ImageEmbeddingOutcome.Failed, backend.image(red))
        assertTrue(opener.opened("vision").single().closed)
    }

    @Test
    fun closingTheSessionsClosesBothAndTheNextCallReopens() {
        val backend = backend()
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))
        assertNotNull(backend.text("a dog"))

        runBlocking { backend.closeSessions() }

        assertTrue(opener.sessions.all { it.closed })
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))
        assertNotNull(backend.text("a dog"))
        assertEquals(4, opener.attempts.size)
        assertEquals(2, vocabularyReads.size, "the vocabulary is dropped with the sessions")
    }

    // ------------------------------------------------------------------ readiness (PR-5's seam)

    @Test
    fun whileNotReadyItClaimsNothingServesNothingAndClosesItsSessions() {
        val backend = backend()
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))
        assertNotNull(backend.text("a dog"))

        isReady = false

        assertFalse(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
        // A pass that reaches the backend first gets `Failed`, never a vector of the artifact being replaced.
        assertEquals(ImageEmbeddingOutcome.Failed, backend.image(red))
        assertNull(backend.text("a dog"))
        assertTrue(opener.sessions.all { it.closed })
        assertEquals(1, opener.opened("vision").single().floatInputs.size)

        isReady = true
        assertTrue(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))
        assertNotNull(backend.text("a dog"))
    }

    // ------------------------------------------------------------------ the output guard

    private fun assertRefused(what: String, vector: () -> FloatArray) {
        val backend = backend()
        opener.answer = vector

        assertEquals(ImageEmbeddingOutcome.Failed, backend.image(red), "an image vector that is $what")
        assertNull(backend.text("a dog"), "a text vector that is $what")
    }

    /** ⚠⚠ A Core ML path on the measured phone ran and returned 768 NaNs (§2.1). */
    @Test
    fun aNonFiniteVectorIsRefusedNotStored() {
        assertRefused("NaN in one place") { FloatArray(model.dimensions) { if (it == 300) Float.NaN else 1f } }
        assertRefused("all NaN") { FloatArray(model.dimensions) { Float.NaN } }
        assertRefused("infinite in one place") { FloatArray(model.dimensions) { if (it == 0) Float.POSITIVE_INFINITY else 1f } }
    }

    /** ⚠⚠ A zero vector normalises to itself and scores 0 against every query. */
    @Test
    fun anAllZeroVectorIsRefusedNotStored() {
        assertRefused("all zeros") { FloatArray(model.dimensions) }
    }

    @Test
    fun aVectorOfAnotherWidthIsRefused() {
        assertRefused("512 wide") { FloatArray(512) { 1f } }
    }

    @Test
    fun everyVectorIsNormalisedOnceAndStampedWithThePinnedModel() {
        val backend = backend()
        opener.answer = { FloatArray(model.dimensions) { 3f } }

        val image: ImageEmbedding = assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red)).embedding
        val text: ImageEmbedding = assertNotNull(backend.text("a dog"))

        listOf(image, text).forEach { embedding ->
            assertEquals(model.dimensions, embedding.vector.size)
            assertTrue(abs(norm(embedding.vector) - 1f) < 1e-4f, "not unit length: ${norm(embedding.vector)}")
            assertEquals(model.provenance, embedding.producedBy)
        }
    }

    // ------------------------------------------------------------------ routing

    @Test
    fun anImageIsDecodedPreprocessedAndFedToTheVisionTower() {
        val backend = backend()

        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))

        val session: FakeSession = opener.sessions.single()
        assertEquals(CoreMlTowerSpec.vision(model), session.spec)
        assertTrue(session.idInputs.isEmpty())
        val tensor: FloatArray = session.floatInputs.single()
        // The shared arithmetic, in CHW: all red is +1 on the first plane and -1 on the other two.
        assertContentEquals(
            ImagePreprocessing.preprocessPixels(IntArray(plane) { 0xFFFF0000.toInt() }, model.side, model.profile),
            tensor,
        )
        assertEquals(1f, tensor[0])
        assertEquals(-1f, tensor[plane])
    }

    @Test
    fun aQueryIsTokenisedAndFedToTheTextTower() {
        val backend = backend()

        assertNotNull(backend.text("A dog!"))

        val session: FakeSession = opener.sessions.single()
        assertEquals(CoreMlTowerSpec.text(model), session.spec)
        assertTrue(session.floatInputs.isEmpty())
        val ids: IntArray = session.idInputs.single()
        // [▁a][▁dog] then `</s>` to the window's end: the checkpoint pads with `</s>` (id 1).
        assertEquals(model.textSequenceLength, ids.size)
        assertEquals(listOf(4, 5, 1, 1), ids.take(4))
        assertTrue(ids.drop(2).all { it == 1 })
    }

    /** ⚠ A query is IPC serving, and must not queue behind an index pass. */
    @Test
    fun aQueryNeverRunsOnTheMediaLaneAndAnImageAlwaysDoes() {
        val backend = backend()

        assertNotNull(backend.text("a dog"))
        assertEquals(0, lane.dispatched, "a query ran on the media lane")
        assertEquals(1, textDispatcher.dispatched)

        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))
        assertEquals(1, lane.dispatched)
        assertEquals(1, textDispatcher.dispatched)
    }

    // ------------------------------------------------------------------ the text half's two halves

    @Test
    fun withoutAVocabularyThereIsNoTextAndTheTextTowerIsNeverOpened() {
        val backend = backend()

        vocabularySource = null
        assertNull(backend.text("a dog"))

        vocabularySource = "vocabulary-a"
        vocabularyBytes = { null }
        assertNull(backend.text("a dog"))

        vocabularyBytes = { "half a file".encodeToByteArray() }
        assertNull(backend.text("a dog"))

        // No `</s>`: the tokenizer refuses to guess a padding id, and that is no text, not a crash.
        vocabularyBytes = { packedVocabulary(listOf("<pad>" to 0f, "<unk>" to 0f, "▁a" to -1f)) }
        assertNull(backend.text("a dog"))

        vocabularyBytes = { throw IllegalStateException("the store is locked") }
        assertNull(backend.text("a dog"))

        // ⚠ The first open of a text tower costs seconds; none of the above may pay it.
        assertTrue(opener.attempts.isEmpty(), "opened ${opener.attempts}")
    }

    @Test
    fun withoutATextTowerThereIsNoText() {
        textPath = null

        assertNull(backend().text("a dog"))
    }

    @Test
    fun aBlankQueryIsNoQuery() {
        val backend = backend()

        assertNull(backend.text(""))
        assertNull(backend.text("   "))
        assertTrue(opener.attempts.isEmpty())
        assertTrue(vocabularyReads.isEmpty())
    }

    @Test
    fun theVocabularyIsParsedOncePerSourceNeverPerQuery() {
        val backend = backend()

        repeat(3) { assertNotNull(backend.text("a dog")) }
        assertEquals(listOf("vocabulary-a"), vocabularyReads)

        vocabularySource = "vocabulary-b"
        assertNotNull(backend.text("a dog"))
        assertEquals(listOf("vocabulary-a", "vocabulary-b"), vocabularyReads)
    }

    // ------------------------------------------------------------------ buffer reuse

    /**
     * ⚠ The decode and tensor buffers are reused from photo to photo, so the thing to prove is
     * that the second photo's tensor holds nothing of the first.
     */
    @Test
    fun theBuffersAreReusedAndEachPhotoOverwritesThemWhole() {
        val backend = backend()

        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))
        // A refused decode in between must not leave the next photo with the last one's pixels.
        assertEquals(ImageEmbeddingOutcome.Unreadable, backend.image(notAnImage))
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(green))

        assertEquals(2, decoder.targets.size)
        assertSame(decoder.targets[0], decoder.targets[1], "a fresh megabyte was allocated for the second photo")
        val inputs: List<FloatArray> = opener.sessions.single().floatInputs
        assertContentEquals(
            ImagePreprocessing.preprocessPixels(IntArray(plane) { 0xFFFF0000.toInt() }, model.side, model.profile),
            inputs[0],
        )
        assertContentEquals(
            ImagePreprocessing.preprocessPixels(IntArray(plane) { 0xFF00FF00.toInt() }, model.side, model.profile),
            inputs[1],
        )
    }

    /** ⚠ A returned vector is the caller's: the next photo must not rewrite it. */
    @Test
    fun aReturnedVectorIsNotTouchedByTheNextPhoto() {
        val backend = backend()
        var call = 0
        opener.answer = { call++; FloatArray(model.dimensions) { if (it == call) 1f else 0f } }

        val first: ImageEmbedding = assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red)).embedding
        val snapshot: FloatArray = first.vector.copyOf()
        assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(green))

        assertContentEquals(snapshot, first.vector)
    }

    // ------------------------------------------------------------------ the marker

    private val scratch: String = "${NSTemporaryDirectory()}coreml-backend-test-${Random.nextLong()}"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(scratch, error = null)
    }

    /**
     * ⚠⚠ *"There is a model"* means the cache's ready marker exists, not that an archive is
     * installed or that a compiled directory is on disk: every step before the marker can be
     * interrupted and leave something that looks complete.
     */
    @Test
    fun itClaimsNothingUntilTheReadyMarkerExists() {
        val fixtures = CoreMlFixtures()
        try {
            val digest: String = "c".repeat(64)
            var installed: String? = null
            val compiler = object : CoreMlCompiler {
                override fun compile(packagePath: String): String {
                    val temporary = "$scratch/compiled-${Random.nextLong()}.mlmodelc"
                    NSFileManager.defaultManager.createDirectoryAtPath(temporary, true, null, null)
                    NSFileManager.defaultManager.createFileAtPath("$temporary/model.mil", null, null)
                    return temporary
                }

                override fun firstOpen(compiledPath: String, spec: CoreMlTowerSpec) = Unit
            }
            val cache: CoreMlModelCache = fixtures.cache(compiler)
            val vision: CoreMlTowerSpec = CoreMlTowerSpec.vision(model)
            val backend = CoreMlInferenceBackend(
                locate = CoreMlInferenceBackend.compiledTower(cache, vision.role) { installed },
                decoder = decoder,
                open = opener::open,
                lane = Dispatchers.Unconfined,
            )
            fixtures.files.writeRawByteArray(
                "archives/vision.tar",
                testTar(
                    TarTestEntry("vision_fp16.mlpackage", type = '5'),
                    TarTestEntry("vision_fp16.mlpackage/Manifest.json", "{}".encodeToByteArray()),
                    TarTestEntry("vision_fp16.mlpackage/Data/weights/weight.bin", ByteArray(2048) { 5 }),
                ),
            )

            // Nothing installed.
            assertFalse(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH))

            // An install record with no compiled model: the archive is verified and on disk.
            installed = digest
            assertFalse(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH), "claimed an archive nothing has compiled")
            assertEquals(ImageEmbeddingOutcome.Failed, backend.image(red))

            val compiled: String = runBlocking { cache.prepare(vision, "archives/vision.tar", digest) }
            assertTrue(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
            assertIs<ImageEmbeddingOutcome.Embedded>(backend.image(red))
            assertEquals(compiled, opener.sessions.single().compiledPath)

            // The compiled directory is still there; only the marker is gone.
            fixtures.files.deleteFile("coreml/${digest.take(16)}/ready")
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(compiled))
            assertFalse(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH), "claimed a model with no marker")
            assertEquals(ImageEmbeddingOutcome.Failed, backend.image(red))
            assertTrue(opener.sessions.single().closed)
        } finally {
            fixtures.cleanUp()
        }
    }

    // ------------------------------------------------------------------ the launch warm-up (5b)

    @Test
    fun theWarmUpOpensTheTextTowerAndAQueryThenFindsItOpen() {
        val backend = backend()

        assertTrue(runBlocking { backend.warmText() })

        assertEquals(listOf(textPath to CoreMlTowerSpec.TEXT_ROLE), opener.attempts, "the warm-up opened something else, or nothing")
        assertEquals(1, vocabularyReads.size)
        assertNotNull(backend.text("a dog"))
        assertEquals(1, opener.attempts.size, "the query opened the tower again")
        assertEquals(1, vocabularyReads.size, "the query parsed the vocabulary again")
    }

    @Test
    fun theWarmUpOpensNothingForABackendThatDoesNotServe() {
        isReady = false
        val backend = backend()

        assertFalse(runBlocking { backend.warmText() })

        assertTrue(opener.attempts.isEmpty(), "a tower was opened while nothing is served")
        assertTrue(vocabularyReads.isEmpty())
    }

    @Test
    fun theWarmUpOpensNoTowerWithoutAVocabulary() {
        vocabularyBytes = { null }

        assertFalse(runBlocking { backend().warmText() })

        assertTrue(opener.attempts.isEmpty(), "a text tower was opened with nothing to feed it")
    }

    @Test
    fun theWarmUpWaitsForTheLoadToBeAllowed() = runBlocking {
        val allowed = CompletableDeferred<Unit>()
        val backend = CoreMlInferenceBackend(
            locate = { visionPath },
            locateText = { textPath },
            locateVocabulary = { vocabularySource },
            readVocabulary = { source -> vocabularyBytes(source) },
            open = opener::open,
            beforeLoad = { allowed.await() },
        )
        try {
            val warming = async(Dispatchers.Default) { backend.warmText() }
            delay(300)
            assertTrue(opener.attempts.isEmpty(), "the warm-up opened a tower before the load was allowed")
            allowed.complete(Unit)
            assertTrue(withTimeout(30_000) { warming.await() })
        } finally {
            allowed.complete(Unit)
        }
    }
}
