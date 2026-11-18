package org.tenshu.inference.proxy.onnx

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.AnimalSpecies
import org.tenshu.inference.proxy.AnimalVeto
import org.tenshu.inference.proxy.DetectedAnimal
import org.tenshu.inference.proxy.DetectedFace
import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.FaceLandmarks
import org.tenshu.inference.proxy.FacePoint
import org.tenshu.inference.proxy.PixelAnimalDetector

/**
 * PT-D9 on Android (`photos-pets.md` §10.1): [MediaPipeFaceBackend]'s veto guards over a fake
 * detector — the same rules as the iOS backend's `on…`/`off…` cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaPipeFaceAnimalVetoTest {

    private var animals: List<DetectedAnimal>? = emptyList()
    private var present = true
    private var requests = 0
    private var seen: Pair<Int, Int>? = null

    private val detector = object : PixelAnimalDetector {
        override fun available(): Boolean = present
        override fun detect(argb: IntArray, width: Int, height: Int): List<DetectedAnimal>? {
            requests++
            seen = width to height
            assertEquals(width * height, argb.size)
            return animals
        }
    }

    private fun backend(veto: AnimalVeto?) = MediaPipeFaceBackend(
        context = ApplicationProvider.getApplicationContext(),
        locateDetector = { "/detector.task" },
        locateEmbedder = { "/embedder.onnx" },
        animalVeto = veto,
        animalDetector = if (veto == null) null else detector,
    )

    private val bitmap: Bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)

    private val face = DetectedFace(
        box = FaceBox(0.3f, 0.3f, 0.4f, 0.4f),
        landmarks = FaceLandmarks(FacePoint(0.32f, 0.32f), FacePoint(0.38f, 0.32f), FacePoint(0.35f, 0.35f), FacePoint(0.33f, 0.38f), FacePoint(0.37f, 0.38f)),
    )
    private val catAroundTheFace = DetectedAnimal(AnimalSpecies.CAT, 0.2f, 0.2f, 0.6f, 0.6f, 0.9f)

    @Test
    fun offTheDetectorIsNeverAskedAndTheStampIsTheStrategys() {
        val off = backend(null)
        val result = checkNotNull(off.vetoAnimals(listOf(face), bitmap))
        assertEquals(listOf(face), result.kept)
        assertEquals(0, requests)
        assertEquals(22, off.provenance.labelSetVersion)
    }

    @Test
    fun onAFaceInsideACatIsDroppedOnTheSameDecode() {
        animals = listOf(catAroundTheFace)
        val on = backend(AnimalVeto.Proposed)
        val result = checkNotNull(on.vetoAnimals(listOf(face), bitmap))
        assertTrue(result.kept.isEmpty())
        assertEquals(1, result.removed)
        assertEquals(40 to 30, seen)
        assertEquals(72, on.provenance.labelSetVersion)
    }

    /** ⚠⚠ The stamp says the veto ran: a detector that could not run is Failed, never "keep every face". */
    @Test
    fun onADetectorThatCannotRunIsFailed() {
        animals = null
        assertNull(backend(AnimalVeto.Proposed).vetoAnimals(listOf(face), bitmap))
    }

    @Test
    fun onAPhotoWithNoFacesNeverAsks() {
        animals = listOf(catAroundTheFace)
        assertEquals(0, checkNotNull(backend(AnimalVeto.Proposed).vetoAnimals(emptyList(), bitmap)).removed)
        assertEquals(0, requests)
    }

    /**
     * ⚠⚠ The detector is its own download (`photos-pets.md` §10.3). Without its file the backend still
     * serves People, as the plain pipeline under the plain stamp, and never asks; with it, the stamp
     * moves — which is what re-indexes the library once — and moves back if the file goes.
     */
    @Test
    fun onWithoutADetectorFileIsThePlainPipelineUnderThePlainStamp() {
        present = false
        animals = listOf(catAroundTheFace)
        val on = backend(AnimalVeto.Proposed)
        assertTrue(on.supports(InferenceCapability.FACE_CLUSTERING))
        assertEquals(22, on.provenance.labelSetVersion)
        assertEquals(listOf(face), checkNotNull(on.vetoAnimals(listOf(face), bitmap)).kept)
        assertEquals(0, requests)

        present = true
        assertEquals(72, on.provenance.labelSetVersion)
        assertEquals(1, checkNotNull(on.vetoAnimals(listOf(face), bitmap)).removed)

        present = false
        assertEquals(22, on.provenance.labelSetVersion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aVetoWithoutADetectorIsRefused() {
        MediaPipeFaceBackend(
            context = ApplicationProvider.getApplicationContext(),
            locateDetector = { null },
            locateEmbedder = { null },
            animalVeto = AnimalVeto.Proposed,
        )
    }
}
