package org.tenshu.inference.proxy.coreml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.tenshu.inference.proxy.PetOutcome
import org.tenshu.inference.proxy.onnx.AnimalDetectorModel
import org.tenshu.inference.proxy.onnx.PetEmbedderModel
import org.tenshu.inference.proxy.vision.FaceImage
import org.tenshu.inference.proxy.vision.FaceImageDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The iOS pet backend's rules around every load (`photos-pets.md` PT-4b2): served or nothing, every open recorded. */
class CoreMlPetBackendTest {

    private val queries = AnimalDetectorModel.QUERIES
    private val classes = AnimalDetectorModel.CLASSES

    private fun oneCat(): Map<String, FloatArray> {
        val dets = FloatArray(queries * 4) { 0.5f }
        dets[0] = 0.5f; dets[1] = 0.5f; dets[2] = 0.3f; dets[3] = 0.3f
        val labels = FloatArray(queries * classes) { -10f }
        labels[AnimalDetectorModel.COCO_CAT] = 4f
        return mapOf("dets" to dets, "labels" to labels)
    }

    private inner class Session(override val compiledPath: String) : CoreMlSessionPort {
        override fun predict(floats: FloatArray): FloatArray = FloatArray(PetEmbedderModel.WIDTH) { 1f }
        override fun predict(ids: IntArray): FloatArray = error("pixels")
        override fun predictAll(floats: FloatArray): Map<String, FloatArray> = oneCat()
        override fun close() {}
    }

    private val decoder = FaceImageDecoder { bytes, _ ->
        if (bytes.decodeToString() == "image") FaceImage(64, 64, IntArray(64 * 64) { 0xFF808080.toInt() }) else null
    }

    private var ready = true
    private val recorded = mutableListOf<String>()
    private var opens = 0

    private fun backend(onPredictReady: () -> Unit = {}) = CoreMlPetBackend(
        ready = { ready },
        locateDetector = { "/detector.mlmodelc" },
        locateEmbedder = { "/embedder.mlmodelc" },
        decoder = decoder,
        opener = { path, _ -> opens++; onPredictReady(); Session(path) },
        recordedOpen = { role, opening -> recorded += role; opening() },
        lane = Dispatchers.Default,
    )

    @Test
    fun aPhotographWithACatGivesOneTemplate_andEachModelIsOpenedOnceThroughTheRecord() = runBlocking {
        val backend = backend()
        val found = assertIs<PetOutcome.Found>(backend.findPets("image".encodeToByteArray(), null))
        assertEquals(1, found.pets.size)
        backend.findPets("image".encodeToByteArray(), null)
        assertEquals(listOf("animalDetector", "petEmbedder"), recorded, "every open recorded, and only once")
        assertEquals(2, opens)
    }

    @Test
    fun notServedIsNothing() = runBlocking {
        ready = false
        val backend = backend()
        assertFalse(backend.available())
        assertEquals(PetOutcome.Failed, backend.findPets("image".encodeToByteArray(), null))
        assertTrue(recorded.isEmpty(), "nothing opened while not served")
    }

    @Test
    fun aTeardownUnderTheWorkRecordsNothing() = runBlocking {
        // The readiness stops serving while the models load: the photograph must not be recorded.
        val backend = backend(onPredictReady = { ready = false })
        assertEquals(PetOutcome.Failed, backend.findPets("image".encodeToByteArray(), null))
    }

    @Test
    fun undecodableBytesAreUnreadable() = runBlocking {
        assertEquals(PetOutcome.Unreadable, backend().findPets("pdf".encodeToByteArray(), null))
    }

    @Test
    fun aRejectedModelIsLatched() = runBlocking {
        var attempts = 0
        val backend = CoreMlPetBackend(
            ready = { true },
            locateDetector = { "/detector.mlmodelc" },
            locateEmbedder = { "/embedder.mlmodelc" },
            decoder = decoder,
            opener = { path, spec ->
                attempts++
                if (spec.role == "animalDetector") throw CoreMlRejectedModelException("not RF-DETR") else Session(path)
            },
            lane = Dispatchers.Default,
        )
        assertEquals(PetOutcome.Failed, backend.findPets("image".encodeToByteArray(), null))
        assertFalse(backend.available(), "the rejected detector path is latched")
        assertEquals(PetOutcome.Failed, backend.findPets("image".encodeToByteArray(), null))
        assertEquals(1, attempts, "never reopened")
    }
}
