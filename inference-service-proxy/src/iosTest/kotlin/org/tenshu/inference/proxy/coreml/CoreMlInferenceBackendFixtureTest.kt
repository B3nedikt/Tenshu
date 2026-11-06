package org.tenshu.inference.proxy.coreml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.ImageEmbedding
import org.tenshu.inference.proxy.ImageEmbeddingOutcome
import org.tenshu.inference.proxy.onnx.VisionModel
import org.tenshu.inference.proxy.onnx.packedVocabulary
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [CoreMlInferenceBackend] over real [CoreMlSession]s: ML Programs Core ML compiled and loaded on
 * the simulator, a real [IosImageDecoder], and JPEGs written in the test.
 *
 * ⚠ The fixtures embed nothing (`CoreMlSessionTest`). What this adds to `CoreMlInferenceBackendTest`
 * is that the real exceptions land where the fake's did: a model of another side latches, an
 * all-zero output is a failure and not a stored vector.
 */
class CoreMlInferenceBackendFixtureTest {

    private val fixtures = CoreMlFixtures()
    private val model = VisionModel.PinnedSigLip
    private val vision: CoreMlTowerSpec = CoreMlTowerSpec.vision(model)
    private val text: CoreMlTowerSpec = CoreMlTowerSpec.text(model)

    @AfterTest
    fun cleanUp() = fixtures.cleanUp()

    private fun photo(colour: Int): ByteArray = testJpeg(width = 320, height = 240) { x, _ -> if (x < 160) colour else 0x202020 }

    private fun backend(visionPath: String?, textPath: String? = null): CoreMlInferenceBackend = CoreMlInferenceBackend(
        locate = { visionPath },
        locateText = { textPath },
        locateVocabulary = { "vocabulary" },
        readVocabulary = {
            packedVocabulary(listOf("<pad>" to 0f, "</s>" to 0f, "<unk>" to 0f, "▁" to -3f, "▁a" to -1f, "▁dog" to -1f))
        },
        lane = Dispatchers.Unconfined,
        textDispatcher = Dispatchers.Unconfined,
    )

    private fun norm(vector: FloatArray): Float = sqrt(vector.fold(0f) { sum, v -> sum + v * v })

    @Test
    fun aPhotoBecomesAUnitVectorOfThePinnedWidth() {
        val path: String = fixtures.compiled("fixture_vision", vision) ?: return
        val backend = backend(path)

        val red: ImageEmbedding = assertIs<ImageEmbeddingOutcome.Embedded>(runBlocking { backend.embedImageOutcome(photo(0xE01010)) }).embedding
        val redAgain: ImageEmbedding = assertNotNull(runBlocking { backend.embedImage(photo(0xE01010)) })
        val blue: ImageEmbedding = assertNotNull(runBlocking { backend.embedImage(photo(0x1010E0)) })

        assertEquals(model.dimensions, red.vector.size)
        assertEquals(model.provenance, red.producedBy)
        assertTrue(abs(norm(red.vector) - 1f) < 1e-3f, "not unit length: ${norm(red.vector)}")
        assertEquals(red, redAgain, "the same bytes embedded differently twice")
        assertFalse(red.vector.contentEquals(blue.vector), "two different photos embedded identically")
        assertEquals(ImageEmbeddingOutcome.Unreadable, runBlocking { backend.embedImageOutcome("not an image".encodeToByteArray()) })
    }

    @Test
    fun aQueryBecomesAUnitVectorOfThePinnedWidth() {
        val visionPath: String = fixtures.compiled("fixture_vision", vision) ?: return
        val textPath: String = fixtures.compiled("fixture_text", text) ?: return
        val backend = backend(visionPath, textPath)

        val embedding: ImageEmbedding = assertNotNull(runBlocking { backend.embedText("a dog") })

        assertEquals(model.dimensions, embedding.vector.size)
        assertTrue(abs(norm(embedding.vector) - 1f) < 1e-3f, "not unit length: ${norm(embedding.vector)}")
        assertFalse(embedding.vector.contentEquals(assertNotNull(runBlocking { backend.embedText("a") }).vector))
    }

    @Test
    fun aModelOfAnotherSideIsRefusedAndLatched() {
        val other: CoreMlTowerSpec = vision.copy(inputShape = listOf(1, 3, 224, 224))
        val path: String = fixtures.compiled("fixture_vision_224", other) ?: return
        val backend = backend(path)

        assertEquals(ImageEmbeddingOutcome.Failed, runBlocking { backend.embedImageOutcome(photo(0xE01010)) })
        assertFalse(
            backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
            "kept claiming the capability after rejecting the artifact",
        )
    }

    @Test
    fun aModelThatAnswersZerosIsAFailureForEveryPhotoAndIsNotLatched() {
        val path: String = fixtures.compiled("fixture_vision_zero", vision) ?: return
        val backend = backend(path)

        assertEquals(ImageEmbeddingOutcome.Failed, runBlocking { backend.embedImageOutcome(photo(0xE01010)) })
        // ⚠ It is the pinned shape, so the claim stands: PR-5's preflight is what keeps such a
        // model from ever being *ready* (§7 D12).
        assertTrue(backend.supports(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
    }

    @Test
    fun aPathWithNoModelIsAFailureNotAnUnreadablePhoto() {
        val backend = backend("${fixtures.root}/absent.mlmodelc")

        assertEquals(ImageEmbeddingOutcome.Failed, runBlocking { backend.embedImageOutcome("not an image".encodeToByteArray()) })
    }
}
