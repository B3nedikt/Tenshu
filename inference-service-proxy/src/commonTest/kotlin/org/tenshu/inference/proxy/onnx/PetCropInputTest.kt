package org.tenshu.inference.proxy.onnx

import org.tenshu.inference.proxy.AnimalSpecies
import org.tenshu.inference.proxy.DetectedAnimal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * [PetCropInput] is `m2-pet-embedding.py`'s `crop()` — the pixels PT-D11's 0.85 cut was measured on.
 *
 * ⚠⚠ The hashes are PIL's own output (`Image.resize((224, 224), BICUBIC)`, Pillow 12.3 and 13.0 agree)
 * over the synthetic pattern below, FNV-1a over the RGB bytes. A resize that is "nearly bicubic" —
 * float weights, no 8-bit intermediate, a different support — moves them.
 */
class PetCropInputTest {

    private fun pattern(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        (0xFF shl 24) or (((x * 7 + y * 3) % 256) shl 16) or (((x * x + y * 5) % 256) shl 8) or ((x * y) % 256)
    }

    private fun fnv(rgb: IntArray): ULong {
        var h = 0xcbf29ce484222325UL
        for (pixel in rgb) {
            for (shift in intArrayOf(16, 8, 0)) {
                h = h xor ((pixel shr shift) and 0xFF).toULong()
                h *= 0x100000001b3UL
            }
        }
        return h
    }

    @Test
    fun resizeIsPilBicubicByteForByte() {
        val cases = listOf(
            Triple(97, 61, 0xaf041ed37cc470d4UL), // upscale
            Triple(600, 400, 0xfa3c7b3821538395UL), // downscale, the support grows
            Triple(224, 224, 0x991d4722c57f3965UL), // identity
            Triple(1000, 37, 0xd4a2c26157a1e28aUL), // one axis down, one up
        )
        for ((w, h, expected) in cases) {
            val out = PetCropInput.resizeBicubic(pattern(w, h), w, h, 224, 224)
            assertEquals(expected, fnv(out), "${w}x$h")
        }
    }

    @Test
    fun theSquareIsTheLongerSideTimesTheMarginSlidInside() {
        // A 100×50 box centred at (500, 400) in a 1000×800 image: side 115, centred, truncated.
        val animal = DetectedAnimal(AnimalSpecies.CAT, 0.45f, 0.46875f, 0.55f, 0.53125f, 0.9f)
        assertContentEquals(intArrayOf(442, 342, 557, 457), PetCropInput.square(1000, 800, animal))

        // At the edge it slides in rather than shrinking …
        // ⚠ 0.125 is exact in binary: on JS a `Float` is a double, so `0.1f` is 0.1 there and its square
        // truncates to 114 where the JVM's float32 0.1 gives 115 — a literal's problem, not the crop's.
        val corner = DetectedAnimal(AnimalSpecies.DOG, 0f, 0f, 0.125f, 0.125f, 0.9f)
        assertContentEquals(intArrayOf(0, 0, 143, 143), PetCropInput.square(1000, 800, corner))

        // … and a box bigger than the image is cut to the shorter side, never past it.
        val whole = DetectedAnimal(AnimalSpecies.DOG, 0f, 0f, 1f, 1f, 0.9f)
        assertContentEquals(intArrayOf(100, 0, 900, 800), PetCropInput.square(1000, 800, whole))
    }
}
