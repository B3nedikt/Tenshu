package org.tenshu.inference.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.onnx.PetEmbedderModel
import org.tenshu.inference.proxy.vision.FaceImage
import org.tenshu.inference.proxy.vision.FaceImageDecoder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Pets' selection rules (PT-D11) and the shared backend's three answers (`photos-pets.md` §10.8). */
class PetRecognitionTest {

    private fun cat(l: Float, t: Float, r: Float, b: Float, confidence: Float = 0.9f) =
        DetectedAnimal(AnimalSpecies.CAT, l, t, r, b, confidence)

    private val selection = PetSelection.BestGuess

    @Test
    fun belowTheFloorIsNotAnAnimal() {
        val picked = selection.select(listOf(cat(0.1f, 0.1f, 0.3f, 0.3f, confidence = 0.49f)), 1000, 1000)
        assertEquals(emptyList(), picked.animals)
    }

    @Test
    fun aSecondQueryOnTheSameAnimalIsDropped_notCountedAsAnOverlap() {
        // ⚠⚠ Kept, a duplicate would hold 100 % of the real box and skip the cat as "crowded".
        val picked = selection.select(
            listOf(cat(0.40f, 0.40f, 0.60f, 0.60f, 0.6f), cat(0.41f, 0.40f, 0.61f, 0.60f, 0.9f)),
            1000, 1000,
        )
        assertEquals(1, picked.animals.size)
        assertEquals(0.9f, picked.animals.single().confidence, "the more confident query wins")
        assertEquals(0, picked.overlapping)
    }

    @Test
    fun twoCatsSideBySideAreBothSkipped_twoApartAreBothKept() {
        // The tuxedo and the white cat (§10.8): each one's ×1.15 square holds much of the other.
        val huddled = selection.select(listOf(cat(0.30f, 0.40f, 0.50f, 0.60f), cat(0.48f, 0.40f, 0.68f, 0.60f)), 1000, 1000)
        assertEquals(emptyList(), huddled.animals)
        assertEquals(2, huddled.overlapping)

        val apart = selection.select(listOf(cat(0.05f, 0.40f, 0.25f, 0.60f), cat(0.70f, 0.40f, 0.90f, 0.60f)), 1000, 1000)
        assertEquals(2, apart.animals.size)
        assertEquals(0, apart.overlapping)
    }

    @Test
    fun theOverlapLimitIsStrict_aTenthOfTheOtherBox() {
        // A 200-px square box at x 300..500 → crop x 285..515. The other box is 200 px wide: 19 px of
        // it inside the crop is under a tenth, kept; 21 px is over, skipped (the crops are symmetric).
        val at = { inside: Int -> cat((515 - inside) / 1000f, 0.40f, (715 - inside) / 1000f, 0.60f) }
        assertEquals(2, selection.select(listOf(cat(0.30f, 0.40f, 0.50f, 0.60f), at(19)), 1000, 1000).animals.size)
        assertEquals(0, selection.select(listOf(cat(0.30f, 0.40f, 0.50f, 0.60f), at(21)), 1000, 1000).animals.size)
    }

    private class FakeDetector(var answer: List<DetectedAnimal>?) : PixelAnimalDetector {
        var present = true
        override fun available() = present
        override fun detect(argb: IntArray, width: Int, height: Int) = answer
    }

    private class FakeEmbedder(val outputs: ArrayDeque<FloatArray?>) : PixelPetEmbedder {
        override fun available() = true
        override fun embed(input: FloatArray): FloatArray? {
            assertEquals(3 * PetEmbedderModel.SIDE * PetEmbedderModel.SIDE, input.size)
            return outputs.removeFirst()
        }
    }

    private val decoder = FaceImageDecoder { bytes, _ ->
        if (bytes.decodeToString() == "image") FaceImage(100, 80, IntArray(100 * 80) { 0xFF808080.toInt() }) else null
    }

    private fun output(first: Float) = FloatArray(PetEmbedderModel.WIDTH) { if (it == 0) first else if (it < PetEmbedderModel.TEMPLATE) 1f else 99f }

    private fun backend(detector: PixelAnimalDetector, embedder: PixelPetEmbedder) =
        PixelPetBackend(decoder, detector, embedder)

    @Test
    fun aTemplateIsTheClsHalf_atUnitLength() = runTest {
        val found = backend(FakeDetector(listOf(cat(0.1f, 0.1f, 0.3f, 0.3f))), FakeEmbedder(ArrayDeque(listOf(output(3f)))))
            .findPets("image".encodeToByteArray(), null)
        val template = assertIs<PetOutcome.Found>(found).pets.single().vector
        assertEquals(PetEmbedderModel.TEMPLATE, template.size, "the patch mean (the 99s) is not part of it")
        val norm = sqrt(template.sumOf { it.toDouble() * it })
        assertTrue(abs(norm - 1.0) < 1e-5, "unit length, was $norm")
        assertEquals(0.1f, found.pets.single().box.left)
    }

    @Test
    fun aPhotographWithNoAnimalIsAFact_notAFailure() = runTest {
        val found = backend(FakeDetector(emptyList()), FakeEmbedder(ArrayDeque())).findPets("image".encodeToByteArray(), null)
        assertEquals(PetOutcome.Found(emptyList()), found)
    }

    @Test
    fun undecodableBytesAreUnreadable_aDetectorThatCannotRunIsFailed() = runTest {
        assertEquals(PetOutcome.Unreadable, backend(FakeDetector(emptyList()), FakeEmbedder(ArrayDeque())).findPets("pdf".encodeToByteArray(), null))
        assertEquals(PetOutcome.Failed, backend(FakeDetector(null), FakeEmbedder(ArrayDeque())).findPets("image".encodeToByteArray(), null))
        val gone = FakeDetector(emptyList()).apply { present = false }
        assertEquals(PetOutcome.Failed, backend(gone, FakeEmbedder(ArrayDeque())).findPets("image".encodeToByteArray(), null))
    }

    @Test
    fun oneEmbedThatCannotRunFailsThePhotograph_neverStoresTheRest() = runTest {
        // ⚠⚠ Two cats, the second embed fails: storing the first would mark the photo done with a pet lost.
        val two = listOf(cat(0.05f, 0.4f, 0.25f, 0.6f), cat(0.7f, 0.4f, 0.9f, 0.6f))
        assertEquals(PetOutcome.Failed, backend(FakeDetector(two), FakeEmbedder(ArrayDeque(listOf(output(1f), null)))).findPets("image".encodeToByteArray(), null))
        // A vector of the wrong width, or with no direction, is the model failing too.
        assertEquals(PetOutcome.Failed, backend(FakeDetector(two.take(1)), FakeEmbedder(ArrayDeque(listOf(FloatArray(384))))).findPets("image".encodeToByteArray(), null))
        assertEquals(PetOutcome.Failed, backend(FakeDetector(two.take(1)), FakeEmbedder(ArrayDeque(listOf(FloatArray(768))))).findPets("image".encodeToByteArray(), null))
    }

    @Test
    fun theStampCarriesTheEmbedderAndThePipeline() {
        val stamp = backend(FakeDetector(null), FakeEmbedder(ArrayDeque())).provenance
        assertEquals(ModelProvenance("dinov2-small-zeroshot", 1, labelSetVersion = 1001), stamp)
    }

    @Test
    fun aPetsLogAndAFacesLogNeverReadAsEachOther() = runTest {
        val files = InMemoryFileDatabase()
        val stamp = ModelProvenance("dinov2-small-zeroshot", 1, 1001)
        val pets = DefaultFaceTemplateStore(files, "/inference/pets.log", DefaultFaceTemplateStore.PETS_MAGIC)
        pets.put("/a.jpg", listOf(StoredFace(FaceBox(0.1f, 0.1f, 0.3f, 0.3f, 0.9f), FloatArray(384) { 1f })), stamp)

        assertEquals(1, DefaultFaceTemplateStore(files, "/inference/pets.log", DefaultFaceTemplateStore.PETS_MAGIC).size())
        assertEquals(0, DefaultFaceTemplateStore(files, "/inference/pets.log").size(), "opened as faces, it is not a face log")
    }
}
