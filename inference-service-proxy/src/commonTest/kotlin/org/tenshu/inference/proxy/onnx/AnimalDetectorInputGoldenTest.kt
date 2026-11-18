package org.tenshu.inference.proxy.onnx

import org.tenshu.inference.proxy.AnimalSpecies
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ [AnimalDetectorInput.resize] against the export's reference `resize()` in
 * `scripts/inference/export-animal-detector.py`, pixel for pixel: the self-check's numbers hold only for
 * the pixels it saw. Regenerate both goldens from that script if either side changes.
 */
class AnimalDetectorInputGoldenTest {

    /** The export's synthetic image: `(x·7 + y·3, x² + y, x·y·5) mod 256`. */
    private fun synthetic(width: Int, height: Int) = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        (0xFF shl 24) or (((x * 7 + y * 3) % 256) shl 16) or (((x * x + y) % 256) shl 8) or ((x * y * 5) % 256)
    }

    @Test
    fun aDownscaleMatchesTheExportsReference() {
        assertContentEquals(DOWN, AnimalDetectorInput.resize(synthetic(53, 31), 53, 31, 12))
    }

    @Test
    fun anUpscaleMatchesTheExportsReference() {
        assertContentEquals(UP, AnimalDetectorInput.resize(synthetic(5, 4), 5, 4, 8))
    }

    @Test
    fun eachOutputsWeightsSumToOne() {
        for ((inputs, outputs) in listOf(2048 to 384, 1536 to 384, 384 to 384, 100 to 384)) {
            AnimalDetectorInput.weights(inputs, outputs).forEach { (start, w) ->
                assertTrue(start >= 0 && start + w.size <= inputs)
                assertEquals(1.0, w.sum(), 1e-9)
            }
        }
    }

    @Test
    fun theTensorIsChwImageNetNormalised() {
        val white = IntArray(4 * 4) { 0xFFFFFFFF.toInt() }
        val tensor = AnimalDetectorInput.tensor(white, 4, 4)
        val plane = AnimalDetectorModel.SIDE * AnimalDetectorModel.SIDE
        assertEquals(3 * plane, tensor.size)
        assertEquals((1f - 0.485f) / 0.229f, tensor[0], 1e-5f)
        assertEquals((1f - 0.456f) / 0.224f, tensor[plane], 1e-5f)
        assertEquals((1f - 0.406f) / 0.225f, tensor[2 * plane], 1e-5f)
    }

    private companion object {
        val DOWN = intArrayOf(
        0x12080B, 0x2E2A20, 0x4D7338, 0x6C834E, 0x8B7259, 0xAA7762, 0xC9724E, 0xE3655E,
        0x5B7D69, 0x25987F, 0x447C96, 0x606D8F, 0x190A24, 0x352C66, 0x547591, 0x738588,
        0x92747F, 0xB17A8A, 0xD07472, 0xD46789, 0x377F7B, 0x2C9A6C, 0x4B7EA7, 0x6770C1,
        0x210D3F, 0x3D2F97, 0x5C7879, 0x7B8883, 0x9A7783, 0xB87C7E, 0xD7777D, 0xB26983,
        0x208281, 0x349D7A, 0x53816F, 0x6F72B0, 0x290F5A, 0x453186, 0x637B85, 0x828A7F,
        0xA17A88, 0xC07F77, 0xDF7980, 0x906C7A, 0x1F8580, 0x3C9F76, 0x5B8375, 0x77759F,
        0x301265, 0x4C3480, 0x6B7D7F, 0x8A8D7B, 0xA97C83, 0xC88181, 0xDF7C72, 0x576F7E,
        0x258785, 0x44A271, 0x628680, 0x7E7791, 0x381463, 0x54377A, 0x738072, 0x928F79,
        0xB17F81, 0xD08474, 0xD37E7F, 0x33717B, 0x2D8283, 0x4B9E7D, 0x6A887A, 0x867A8D,
        0x401774, 0x5C398D, 0x7B8286, 0x999286, 0xB9818F, 0xD78684, 0xB88183, 0x247486,
        0x346E8A, 0x538E80, 0x728B7A, 0x8E7C8C, 0x481A69, 0x643C86, 0x828586, 0xA1948D,
        0xC08479, 0xDE8984, 0x84837F, 0x1E768F, 0x3C6F7E, 0x5B907F, 0x7A8E83, 0x967F8D,
        0x4F1C73, 0x6B3E75, 0x8A887E, 0xA9977E, 0xC8877B, 0xE28C80, 0x598677, 0x257982,
        0x447282, 0x63907D, 0x81887E, 0x9D827E, 0x571F82, 0x734187, 0x928A7D, 0xB19A78,
        0xD0898E, 0xD48E80, 0x378967, 0x2C7C75, 0x4B747B, 0x6A8A7F, 0x896880, 0xA58493,
        0x5F2155, 0x7B4372, 0x9A8D78, 0xB99C83, 0xD88773, 0xB0896C, 0x1F8B81, 0x347E86,
        0x53777D, 0x72817F, 0x91668C, 0xAD879B, 0x66245F, 0x824689, 0xA18F79, 0xC09F81,
        0xDF7884, 0x93736C, 0x1E8E95, 0x3B8084, 0x5A7985, 0x795E7E, 0x98637B, 0xB4898D,
        )
        val UP = intArrayOf(
        0x000000, 0x030000, 0x070100, 0x0C0300, 0x100600, 0x150900, 0x190D00, 0x1C1000,
        0x010000, 0x040101, 0x080101, 0x0D0302, 0x110603, 0x150904, 0x1A0D04, 0x1D1005,
        0x020100, 0x050102, 0x0A0204, 0x0E0406, 0x120609, 0x17090B, 0x1B0E0D, 0x1E110F,
        0x040100, 0x070203, 0x0B0207, 0x10040B, 0x14070E, 0x180A12, 0x1D0E16, 0x201119,
        0x050200, 0x080204, 0x0D0309, 0x11050F, 0x150714, 0x1A0A1A, 0x1E0F1F, 0x211223,
        0x070200, 0x0A0305, 0x0E030C, 0x130513, 0x17081A, 0x1B0B21, 0x200F28, 0x23122D,
        0x080300, 0x0B0306, 0x10040F, 0x140617, 0x180820, 0x1D0B28, 0x211031, 0x241337,
        0x090300, 0x0C0307, 0x100410, 0x150619, 0x190923, 0x1E0C2C, 0x221035, 0x25133C,
        )
    }
}

class RfDetrOutputTest {

    private val queries = AnimalDetectorModel.QUERIES
    private val classes = AnimalDetectorModel.CLASSES

    private fun outputs(vararg set: Triple<Int, Int, Float>): Pair<FloatArray, FloatArray> {
        val dets = FloatArray(queries * 4)
        val logits = FloatArray(queries * classes) { -10f }
        set.forEach { (query, category, logit) ->
            logits[query * classes + category] = logit
            dets[query * 4] = 0.5f; dets[query * 4 + 1] = 0.4f; dets[query * 4 + 2] = 0.2f; dets[query * 4 + 3] = 0.6f
        }
        return dets to logits
    }

    @Test
    fun aConfidentCatQueryBecomesATopLeftBox() {
        val (dets, logits) = outputs(Triple(7, AnimalDetectorModel.COCO_CAT, 3f))
        val animal = RfDetrOutput.animals(dets, logits, floor = 0.3f).single()
        assertEquals(AnimalSpecies.CAT, animal.species)
        assertEquals(0.4f, animal.left, 1e-6f)
        assertEquals(0.1f, animal.top, 1e-6f)
        assertEquals(0.6f, animal.right, 1e-6f)
        assertEquals(0.7f, animal.bottom, 1e-6f)
        assertEquals(0.9526f, animal.confidence, 1e-3f)
    }

    /** ⚠ Category ids, not the 80-class index: id 16 is `bird`, never a cat. */
    @Test
    fun onlyCocoCatAndDogIdsCount() {
        val (dets, logits) = outputs(Triple(1, 16, 5f), Triple(2, 1, 5f), Triple(3, AnimalDetectorModel.COCO_DOG, 2f))
        assertEquals(listOf(AnimalSpecies.DOG), RfDetrOutput.animals(dets, logits, floor = 0.3f).map { it.species })
    }

    @Test
    fun belowTheFloorIsNothing() {
        val (dets, logits) = outputs(Triple(1, AnimalDetectorModel.COCO_CAT, -2f))
        assertTrue(RfDetrOutput.animals(dets, logits, floor = 0.3f).isEmpty())
    }
}
