package org.tenshu.inference.proxy.onnx

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.ImageEmbeddingOutcome

/**
 * [OnnxInferenceBackend] (IN-3b). Everything that does not need weights runs always; the three
 * tests that need the real artifact **skip with a printed reason** unless it is handed to them:
 *
 * ```
 * ./gradlew :inference-service-proxy:jvmTest -Dtenshu.inference.model=/path/vision_model_uint8.onnx
 * ```
 *
 * ⚠⚠ Skipping rather than failing, and never in the gate: the artifact is ~100 MB, is not committed
 * (the repo is public) and is not fetched by any build. ⚠ That is also why the no-model tests below
 * matter more than they look — on CI they are the *only* ones that run, so they have to pin the
 * behaviour a device with no model actually gets.
 */
class OnnxInferenceBackendTest {

    @Test
    fun `with no model it claims nothing`() = runTest {
        val backend = OnnxInferenceBackend(decoder = AwtImageDecoder, locate = { null })

        InferenceCapability.entries.forEach {
            assertTrue(!backend.supports(it), "claimed $it with no model installed")
        }
        assertNull(backend.embedImage(png(Color.RED)))
        // ⚠⚠ No model is a failure of the MODEL, never a verdict on the photo — an indexer remembers
        // `Unreadable` against a file for good.
        assertEquals(ImageEmbeddingOutcome.Failed, backend.embedImageOutcome(png(Color.RED)))
    }

    /**
     * ⚠⚠ The state a device is in for every capability the vision tower cannot serve. A backend
     * that claimed [InferenceCapability.CONTENT_TAGGING] and returned no tags would make a
     * switched-on feature indistinguishable from a model with no opinions.
     */
    /**
     * ⚠ Driven through [OnnxModelFiles.locator] rather than a hand-written lambda, so the
     * side-load **convention** is pinned too: the directory, the filename derived from the model
     * id, and the existence check that makes a claim track the file. A locator that answered a
     * path unconditionally would make every device claim it can search photos it cannot read.
     */
    @Test
    fun `it claims only semantic image search, and only while the file is there`() = runTest {
        val root = createTempDirectory("tenshu-inference").toFile()
        val artifact = File(File(root, OnnxModelFiles.DIRECTORY), OnnxModelFiles.fileName())
        artifact.parentFile.mkdirs()
        artifact.writeText("not an onnx graph")
        try {
            val backend = OnnxInferenceBackend(decoder = AwtImageDecoder, locate = OnnxModelFiles.locator(root))

            assertTrue(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
            assertTrue(!backend.supports(InferenceCapability.CONTENT_TAGGING))
            assertTrue(!backend.supports(InferenceCapability.TEXT_IN_IMAGES))
            assertTrue(!backend.supports(InferenceCapability.FACE_CLUSTERING))
            assertTrue(!backend.supports(InferenceCapability.QUERY_PLANNING))

            // ⚠ Garbage in the file is an answer of `null`, never a throw: an index pass over a
            // library must not die because the artifact is half-copied.
            assertNull(backend.embedImage(png(Color.RED)))
            // ⚠⚠ And it is a FAILURE, not an unreadable photo, while `supports()` still claims search
            // — the combination that made a claim re-check unable to tell the two apart.
            assertEquals(ImageEmbeddingOutcome.Failed, backend.embedImageOutcome(png(Color.RED)))

            artifact.delete()
            assertTrue(!backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH), "claimed a deleted model")
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * ⚠ Zero-shot tagging needs the text tower to embed a *vocabulary* and a calibrated threshold
     * per label (R4), and OCR is a different model entirely — so both stay empty even now that
     * text embedding works. Claiming either and returning nothing would make a switched-on
     * capability indistinguishable from a model with no opinions.
     */
    @Test
    fun `it still has no tagging and no OCR, and returns nothing rather than claiming them`() = runTest {
        val backend = OnnxInferenceBackend(decoder = AwtImageDecoder, locate = { model()?.path })
        assertEquals(emptyList(), backend.tagImage(png(Color.RED), listOf("cat")))
        assertEquals(emptyList(), backend.recognizeText(png(Color.RED)))
    }

    @Test
    fun `it embeds an image into the pinned model's space`() = runTest {
        val model = model() ?: return@runTest skip()
        val backend = OnnxInferenceBackend(decoder = AwtImageDecoder, locate = { model.path })

        val embedding = assertNotNull(backend.embedImage(png(Color.RED)), "no embedding from $model")

        assertEquals(VisionModel.PinnedSigLip.dimensions, embedding.vector.size)
        assertEquals(VisionModel.PinnedSigLip.provenance, embedding.producedBy)
        // ⚠⚠ Unit length is load-bearing: `photosLike` scores with a bare dot product, so an
        // un-normalised vector would rank by magnitude and still look like a ranking.
        val length = sqrt(embedding.vector.fold(0f) { sum, v -> sum + v * v })
        assertTrue(abs(length - 1f) < 1e-3f, "vector is not unit length: $length")
    }

    /**
     * ⚠⚠ With a working model, bytes that are not an image are `Unreadable` — the one "no vector" an
     * indexer may remember against the file. It needs the real model because the session opens
     * first: a decoder verdict is only reported by a backend that could have run.
     */
    @Test
    fun `bytes that are not an image are unreadable, not a failure`() = runTest {
        val model = model() ?: return@runTest skip()
        val backend = OnnxInferenceBackend(decoder = AwtImageDecoder, locate = { model.path })

        assertEquals(ImageEmbeddingOutcome.Unreadable, backend.embedImageOutcome("not an image".encodeToByteArray()))
        assertTrue(backend.embedImageOutcome(png(Color.RED)) is ImageEmbeddingOutcome.Embedded)
    }

    /**
     * The property every consumer actually depends on, and the one a plumbing test misses: the same
     * photograph embeds the same way, a different one does not.
     */
    @Test
    fun `it is deterministic, and different images point in different directions`() = runTest {
        val model = model() ?: return@runTest skip()
        val backend = OnnxInferenceBackend(decoder = AwtImageDecoder, locate = { model.path })

        val red = assertNotNull(backend.embedImage(png(Color.RED)))
        val redAgain = assertNotNull(backend.embedImage(png(Color.RED)))
        val stripes = assertNotNull(backend.embedImage(png(Color.RED, stripes = true)))

        assertEquals(red, redAgain, "the same bytes embedded differently twice")
        val similarity = red.vector.indices.fold(0f) { sum, i -> sum + red.vector[i] * stripes.vector[i] }
        assertTrue(similarity < 0.999f, "two different images embedded identically ($similarity)")
    }

    /**
     * ⚠⚠ **The guard that keeps a provenance stamp honest.** Until IN-2c's catalog verifies a
     * digest, the artifact is asserted to be the pinned model *by its filename* — so a checkpoint
     * with a different input size must be refused rather than embedded and stamped as this one.
     * Cosine across two models' spaces is meaningless **and computable** (§16.1): nothing
     * downstream could ever notice.
     */
    @Test
    fun `a model whose shape is not the pinned model's is refused`() = runTest {
        val model = model() ?: return@runTest skip()
        val backend = OnnxInferenceBackend(
            decoder = AwtImageDecoder,
            locate = { model.path },
            // The real artifact, described as a 224² checkpoint — which is what feeding SigLIP-256
            // the wrong pixels would look like from the outside.
            model = VisionModel.PinnedSigLip.copy(side = 224),
        )

        assertNull(backend.embedImage(png(Color.RED)), "embedded with a mismatched input size")
        assertTrue(
            !backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
            "kept claiming the capability after rejecting the artifact",
        )
    }


    // ------------------------------------------------------- R3: the text half, when it is there

    private fun textModel(): File? =
        System.getProperty("tenshu.inference.textModel")?.let(::File)?.takeIf { it.isFile }

    private fun vocabulary(): File? =
        System.getProperty("tenshu.inference.tokenizer")?.let(::File)?.takeIf { it.isFile }

    private fun textBackend(): OnnxInferenceBackend? {
        val model = model() ?: return null
        val text = textModel() ?: return null
        val vocabulary = vocabulary() ?: return null
        return OnnxInferenceBackend(
            decoder = AwtImageDecoder,
            locate = { model.path },
            locateText = { text.path },
            locateVocabulary = { vocabulary.path },
        )
    }

    /**
     * ⚠⚠ **The int8 artifact has to load in the JAVA build of ORT, not just in Python's.**
     * `models.md` §3.5 recorded int8 refusing to run at all — `ConvInteger` unimplemented in ORT's
     * Java CPU build — which is why the *vision* tower ships uint8. The text tower has no
     * convolutions, so the blocker should not apply; "should not" is not a measurement, and this
     * test is the measurement.
     */
    @Test
    fun `it embeds text into a unit vector of the pinned model's width`() = runTest {
        val backend = textBackend() ?: return@runTest skip()

        val embedding = assertNotNull(backend.embedText("a sunset over water"), "no text embedding")

        assertEquals(VisionModel.PinnedSigLip.dimensions, embedding.vector.size)
        assertEquals(VisionModel.PinnedSigLip.provenance, embedding.producedBy)
        val length = sqrt(embedding.vector.fold(0f) { sum, v -> sum + v * v })
        assertTrue(abs(length - 1f) < 1e-3f, "text vector is not unit length: $length")
    }

    /**
     * ⭐ The property retrieval actually rests on: related sentences point in similar directions
     * and unrelated ones do not. ⚠ Text-to-text, deliberately — an image-text threshold is
     * `models.md` R4's calibration problem and is not settled by anything here.
     */
    @Test
    fun `related queries embed closer than unrelated ones`() = runTest {
        val backend = textBackend() ?: return@runTest skip()

        val dog = assertNotNull(backend.embedText("a photo of a dog"))
        val puppy = assertNotNull(backend.embedText("a photo of a puppy"))
        val code = assertNotNull(backend.embedText("a screenshot of source code"))

        fun cosine(a: FloatArray, b: FloatArray) = a.indices.fold(0f) { sum, i -> sum + a[i] * b[i] }
        val near = cosine(dog.vector, puppy.vector)
        val far = cosine(dog.vector, code.vector)
        assertTrue(near > far, "dog~puppy ($near) should beat dog~code ($far)")
        assertTrue(near - far > 0.05f, "the gap is too small to be a signal: $near vs $far")
    }

    /** ⚠ Without the text half — the ordinary state of a device that only indexes. */
    @Test
    fun `with no text tower it returns null rather than guessing`() = runTest {
        val model = model()
        val backend = OnnxInferenceBackend(
            decoder = AwtImageDecoder,
            locate = { model?.path },
        )
        assertNull(backend.embedText("a sunset over water"))
    }

    // ------------------------------------------- the output guard, over a fake tower (no weights)

    /** A tower of the pinned model's shape whose run answers whatever the test says. */
    private class FakeTower(vision: Boolean, private val answer: () -> FloatArray?) : OnnxTower {
        override val outputNames: Collection<String> = listOf(TowerGuards.POOLED_OUTPUT)
        override val inputShape: List<Long> = if (vision) listOf(-1L, 3L, 256L, 256L) else listOf(-1L, 64L)
        override fun pooled(pixels: FloatArray, shape: LongArray): FloatArray? = answer()
        override fun pooled(ids: LongArray, shape: LongArray): FloatArray? = answer()
        override fun close() = Unit
    }

    private val width = VisionModel.PinnedSigLip.dimensions
    private val good: () -> FloatArray? = { FloatArray(width) { 1f } }
    private var answer: () -> FloatArray? = good
    private val opened = mutableListOf<String>()

    private val fullVocabulary =
        listOf("<pad>" to 0f, "</s>" to 0f, "<unk>" to 0f, "▁" to -3f, "▁a" to -1f, "▁dog" to -1f)

    private fun vocabularyFile(pieces: List<Pair<String, Float>>): File =
        File(createTempDirectory("tenshu-vocabulary").toFile(), "tokenizer.bin")
            .apply { writeBytes(packedVocabulary(pieces)) }

    private fun fakeBackend(vocabulary: File = vocabularyFile(fullVocabulary)) = OnnxInferenceBackend(
        decoder = AwtImageDecoder,
        locate = { "vision" },
        locateText = { "text" },
        locateVocabulary = { vocabulary.path },
        open = { path ->
            opened += path
            FakeTower(vision = path == "vision") { answer() }
        },
    )

    private fun norm(vector: FloatArray): Float = sqrt(vector.fold(0f) { sum, v -> sum + v * v })

    /**
     * ⚠⚠ Refused, and **not latched**: the next input is embedded by the same session, and the
     * backend goes on claiming search. A bad output is a failure of that run, never a verdict on
     * the model.
     */
    private suspend fun assertRefusedThenServed(what: String, bad: () -> FloatArray?) {
        opened.clear()
        val backend = fakeBackend()

        answer = bad
        assertEquals(
            ImageEmbeddingOutcome.Failed,
            backend.embedImageOutcome(png(Color.RED)),
            "an image vector that is $what",
        )
        assertNull(backend.embedImage(png(Color.RED)), "an image vector that is $what")
        assertNull(backend.embedText("a dog"), "a text vector that is $what")
        assertTrue(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH), "latched on a vector that is $what")

        answer = good
        val image = backend.embedImageOutcome(png(Color.RED))
        assertTrue(image is ImageEmbeddingOutcome.Embedded, "the next photo after a vector that is $what: $image")
        assertTrue(abs(norm(image.embedding.vector) - 1f) < 1e-3f)
        val text = assertNotNull(backend.embedText("a dog"), "the next query after a vector that is $what")
        assertTrue(abs(norm(text.vector) - 1f) < 1e-3f)
        assertEquals(listOf("vision", "text"), opened, "each tower is opened once and kept")
    }

    /** ⚠⚠ A Core ML path on a measured phone ran and returned 768 NaNs; normalising keeps them. */
    @Test
    fun `a vector holding NaN is refused, and the next input is served`() = runTest {
        assertRefusedThenServed("NaN in one place") { FloatArray(width) { if (it == 300) Float.NaN else 1f } }
        assertRefusedThenServed("all NaN") { FloatArray(width) { Float.NaN } }
    }

    @Test
    fun `a vector holding an infinity is refused, and the next input is served`() = runTest {
        assertRefusedThenServed("infinite in one place") {
            FloatArray(width) { if (it == 0) Float.POSITIVE_INFINITY else 1f }
        }
        assertRefusedThenServed("negative infinity in the last place") {
            FloatArray(width) { if (it == width - 1) Float.NEGATIVE_INFINITY else 1f }
        }
    }

    /** ⚠⚠ A zero vector normalises to itself and scores 0 against every query. */
    @Test
    fun `an all-zero vector is refused, and the next input is served`() = runTest {
        assertRefusedThenServed("all zeros") { FloatArray(width) }
    }

    @Test
    fun `a vector of another width is refused, and the next input is served`() = runTest {
        assertRefusedThenServed("512 wide") { FloatArray(512) { 1f } }
    }

    @Test
    fun `a run that throws or has no pooled output is a failure, and the next input is served`() = runTest {
        assertRefusedThenServed("thrown instead") { error("the run failed") }
        assertRefusedThenServed("absent") { null }
    }

    /**
     * ⚠ The tokenizer refuses to guess a padding id. That is no text, as on iOS, never an exception
     * into the search screen, and it is not latched: a repaired vocabulary at the same path is read.
     */
    @Test
    fun `a vocabulary without the end-of-sequence piece is no text, not an exception`() = runTest {
        opened.clear()
        answer = good
        val vocabulary = vocabularyFile(listOf("<pad>" to 0f, "<unk>" to 0f, "▁a" to -1f))
        val backend = fakeBackend(vocabulary)

        assertNull(backend.embedText("a dog"))
        assertNull(backend.embedText("a dog"), "the second query throws no more than the first")
        assertEquals(emptyList(), opened, "no text tower is opened for a vocabulary that cannot feed it")
        assertTrue(backend.embedImageOutcome(png(Color.RED)) is ImageEmbeddingOutcome.Embedded)

        vocabulary.writeBytes(packedVocabulary(fullVocabulary))
        assertNotNull(backend.embedText("a dog"), "a repaired vocabulary is read")
    }

    private fun model(): File? =
        System.getProperty("tenshu.inference.model")?.let(::File)?.takeIf { it.isFile }

    private fun skip() {
        println("skipped: no -Dtenshu.inference.model=<vision_model_uint8.onnx> — see the KDoc")
    }

    private fun png(colour: Color, stripes: Boolean = false): ByteArray {
        val image = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val striped = stripes && ((x / 16) % 2 == 0)
                image.setRGB(x, y, (if (striped) Color.WHITE else colour).rgb)
            }
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
