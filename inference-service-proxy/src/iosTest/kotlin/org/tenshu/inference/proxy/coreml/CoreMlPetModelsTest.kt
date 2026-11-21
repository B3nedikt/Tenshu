package org.tenshu.inference.proxy.coreml

import org.tenshu.inference.proxy.AnimalSpecies
import org.tenshu.inference.proxy.onnx.AnimalDetectorModel
import org.tenshu.inference.proxy.onnx.PetEmbedderModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pets' Core ML runners (`photos-pets.md` PT-4b) over a fake session: the ONNX twins' contract, on iOS. */
class CoreMlPetModelsTest {

    private val queries = AnimalDetectorModel.QUERIES
    private val classes = AnimalDetectorModel.CLASSES

    /** One confident cat at query 7, centred box 0.5/0.5, 0.2 × 0.4; every other logit very negative. */
    private fun oneCat(): Map<String, FloatArray> {
        val dets = FloatArray(queries * 4) { 0.5f }
        dets[7 * 4] = 0.5f; dets[7 * 4 + 1] = 0.5f; dets[7 * 4 + 2] = 0.2f; dets[7 * 4 + 3] = 0.4f
        val labels = FloatArray(queries * classes) { -10f }
        labels[7 * classes + AnimalDetectorModel.COCO_CAT] = 4f
        return mapOf("dets" to dets, "labels" to labels)
    }

    private class FakeSession(
        override val compiledPath: String,
        val all: () -> Map<String, FloatArray>,
        val one: () -> FloatArray = { FloatArray(PetEmbedderModel.WIDTH) { 1f } },
    ) : CoreMlSessionPort {
        var closed = false
        override fun predict(floats: FloatArray): FloatArray = one()
        override fun predict(ids: IntArray): FloatArray = error("not a text tower")
        override fun predictAll(floats: FloatArray): Map<String, FloatArray> = all()
        override fun close() { closed = true }
    }

    @Test
    fun theDetectorReadsBothOutputsThroughAndroidsDecoder() {
        val detector = CoreMlAnimalDetector(locate = { "/a.mlmodelc" }, opener = { path, _ -> FakeSession(path, ::oneCat) })
        val animals = assertNotNull(detector.detect(IntArray(10 * 10), 10, 10))
        val cat = animals.single()
        assertEquals(AnimalSpecies.CAT, cat.species)
        assertEquals(0.4f, cat.left, 1e-5f)
        assertEquals(0.3f, cat.top, 1e-5f)
        assertTrue(cat.confidence > 0.95f)
    }

    @Test
    fun aRunThatFailsIsNull_neverNoAnimals() {
        val detector = CoreMlAnimalDetector(
            locate = { "/a.mlmodelc" },
            opener = { path, _ -> FakeSession(path, { throw CoreMlPredictionException("boom") }) },
        )
        assertNull(detector.detect(IntArray(4), 2, 2))
        assertNull(CoreMlAnimalDetector(locate = { null }).detect(IntArray(4), 2, 2), "no model is null, not empty")
    }

    @Test
    fun anotherModelAtAPathIsLatched_aLoadFailureIsNot() {
        var path = "/a.mlmodelc"
        var opens = 0
        var reject = true
        val embedder = CoreMlPetEmbedder(locate = { path }, opener = { p, _ ->
            opens++
            if (reject) throw CoreMlRejectedModelException("not DINOv2") else FakeSession(p, { emptyMap() })
        })
        assertNull(embedder.embed(FloatArray(3 * 224 * 224)))
        assertFalse(embedder.available(), "the rejected path is latched")
        assertNull(embedder.embed(FloatArray(3 * 224 * 224)))
        assertEquals(1, opens, "never reopened")

        path = "/b.mlmodelc"
        reject = false
        assertNotNull(embedder.embed(FloatArray(3 * 224 * 224)), "a new path is a new artifact")

        var failing = true
        val flaky = CoreMlPetEmbedder(locate = { "/c.mlmodelc" }, opener = { p, _ ->
            if (failing) throw CoreMlLoadException("memory") else FakeSession(p, { emptyMap() })
        })
        assertNull(flaky.embed(FloatArray(3 * 224 * 224)))
        assertTrue(flaky.available(), "a load failure is not a property of the artifact")
        failing = false
        assertNotNull(flaky.embed(FloatArray(3 * 224 * 224)))
    }

    @Test
    fun theDetectorSpecWantsBothOutputs_andCountsEach() {
        val spec = CoreMlTowerSpec.animalDetector()
        val input = mapOf("input" to CoreMlInputDescription(listOf(1, 3, 384, 384), CoreMlElementType.FLOAT32))
        assertNull(spec.rejectionFor(CoreMlModelShape(input, setOf("dets", "labels"))))
        assertNotNull(spec.rejectionFor(CoreMlModelShape(input, setOf("dets"))), "without labels it is another model")
        assertNull(spec.unusableExtraReason("labels", FloatArray(queries * classes)))
        assertNotNull(spec.unusableExtraReason("labels", FloatArray(10)))
        assertNotNull(spec.unusableExtraReason("labels", FloatArray(queries * classes) { Float.NaN }))
    }
}
