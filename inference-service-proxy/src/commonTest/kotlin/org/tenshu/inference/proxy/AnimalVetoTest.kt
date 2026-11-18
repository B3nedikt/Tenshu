package org.tenshu.inference.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PT-D9's geometry (`photos-pets.md` §6): which face boxes an animal box vetoes. */
class AnimalVetoTest {

    private val veto = AnimalVeto.Proposed

    private fun cat(left: Float, top: Float, right: Float, bottom: Float, confidence: Float = 0.9f) =
        DetectedAnimal(AnimalSpecies.CAT, left, top, right, bottom, confidence)

    private fun face(left: Float, top: Float, right: Float, bottom: Float) =
        DetectedFace(
            box = FaceBox(left, top, right, bottom),
            landmarks = FaceLandmarks(
                FacePoint(left, top), FacePoint(right, top), FacePoint(left, bottom),
                FacePoint(left, bottom), FacePoint(right, bottom),
            ),
        )

    @Test
    fun aFaceInsideAConfidentCatIsVetoed() {
        assertTrue(veto.vetoes(face(0.3f, 0.3f, 0.4f, 0.4f).box, listOf(cat(0.2f, 0.2f, 0.6f, 0.7f))))
    }

    /** A person holding a cat: the face sits beside the cat's box, so it is a person and stays. */
    @Test
    fun aFaceBesideACatIsKept() {
        val cat = cat(0.4f, 0.4f, 0.8f, 0.9f)
        assertFalse(veto.vetoes(face(0.1f, 0.1f, 0.3f, 0.3f).box, listOf(cat)), "no overlap")
        assertFalse(veto.vetoes(face(0.3f, 0.3f, 0.5f, 0.5f).box, listOf(cat)), "a quarter inside")
    }

    /**
     * ⚠⚠ Over the FACE's area, never the union's: a small face wholly inside a large dog box has a
     * tiny IoU, and an IoU rule would never veto it — the case the veto exists for.
     */
    @Test
    fun containmentIsMeasuredOverTheFaceNotTheUnion() {
        assertTrue(veto.vetoes(face(0.45f, 0.45f, 0.5f, 0.5f).box, listOf(cat(0f, 0f, 1f, 1f))))
    }

    @Test
    fun aLowConfidenceAnimalVetoesNothing() {
        assertFalse(veto.vetoes(face(0.3f, 0.3f, 0.4f, 0.4f).box, listOf(cat(0.2f, 0.2f, 0.6f, 0.7f, confidence = 0.3f))))
    }

    @Test
    fun aDegenerateFaceBoxIsNeverVetoed() {
        assertFalse(veto.vetoes(FaceBox(0.3f, 0.3f, 0.3f, 0.4f), listOf(cat(0f, 0f, 1f, 1f))))
    }

    @Test
    fun applyKeepsTheOthersAndCountsTheRemoved() {
        val inside = face(0.3f, 0.3f, 0.4f, 0.4f)
        val outside = face(0.7f, 0.7f, 0.9f, 0.9f)
        val result = veto.apply(listOf(inside, outside), listOf(cat(0.2f, 0.2f, 0.6f, 0.6f)))
        assertEquals(listOf(outside), result.kept)
        assertEquals(1, result.removed)
    }

    @Test
    fun noAnimalsKeepsEveryFace() {
        val faces = listOf(face(0.3f, 0.3f, 0.4f, 0.4f))
        val result = veto.apply(faces, emptyList())
        assertEquals(faces, result.kept)
        assertEquals(0, result.removed)
    }

    @Test
    fun outOfRangeNumbersAreRefused() {
        assertFailsWith<IllegalArgumentException> { AnimalVeto(minAnimalConfidence = 1.5f, minFaceInside = 0.8f) }
        assertFailsWith<IllegalArgumentException> { AnimalVeto(minAnimalConfidence = 0.5f, minFaceInside = 0f) }
    }
}
