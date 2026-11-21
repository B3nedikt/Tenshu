package app.tenshu.control.ui.screens.models

import org.tenshu.inference.InferenceCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pets on iOS's Models screen (`photos-pets.md` PT-4c): faces' shape and Android's words — and ⚠⚠ never
 * "face data" about pets, nor "pet data" about faces.
 */
class PhotosPetsCopyTest {

    private val pet = InferenceCapability.PET_RECOGNITION
    private val face = InferenceCapability.FACE_CLUSTERING

    @Test
    fun petsAreTheirOwnDeletingFeature() {
        assertTrue(PhotosModelsCopy.deletesData(pet))
        assertTrue(PhotosModelsCopy.deletesData(face))
        assertFalse(PhotosModelsCopy.deletesData(InferenceCapability.SEMANTIC_IMAGE_SEARCH))
        assertSame(PhotosModelsCopy.PetsCopy, PhotosModelsCopy.deleting(pet))
        assertSame(PhotosModelsCopy.FacesCopy, PhotosModelsCopy.deleting(face))
        assertEquals("Recognise your pets", PhotosModelsCopy.featureTitle(pet))
    }

    @Test
    fun everyPetsSentenceSaysPetData_andNoFaceSentenceDoes() {
        val words = PhotosModelsCopy.PetsCopy
        val petsSentences = listOf(
            words.description, words.offDeletes("iPhone"), words.turnOffTitle("iPhone"), words.turnOffText("iPhone"),
            words.turnOffOtherTitle("iPad"), words.turnOffOtherText, words.turnOffEverywhere, words.turnOffEverywhereTitle,
            words.turnOffEverywhereText, words.offerTitle("iPhone"), words.offerText("iPhone"), words.removeText(1_000_000, "iPhone"),
            words.unsuitable("iPhone"),
        )
        petsSentences.forEach { assertFalse("face" in it.lowercase(), it) }
        assertTrue("pet data" in words.offDeletes("iPhone"))
        assertTrue("names you typed are kept" in words.turnOffEverywhereText)

        val faces = PhotosModelsCopy.FacesCopy
        listOf(faces.description, faces.offDeletes("iPhone"), faces.turnOffEverywhereText).forEach { assertFalse("pet" in it.lowercase(), it) }
        // ⚠ Faces' words are exactly the constants they always were.
        assertEquals(PhotosModelsCopy.FACES_TURN_OFF_EVERYWHERE, faces.turnOffEverywhere)
        assertEquals(PhotosModelsCopy.FACES_DESCRIPTION, faces.description)
    }
}
