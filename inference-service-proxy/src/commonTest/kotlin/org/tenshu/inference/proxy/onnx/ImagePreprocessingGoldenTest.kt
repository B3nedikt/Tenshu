package org.tenshu.inference.proxy.onnx

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** FNV-1a 64 over each value's IEEE-754 bits. A fingerprint of a tensor, not a secret. */
internal fun tensorDigest(tensor: FloatArray): String {
    var hash = -0x340d631b7bdddcdbL
    tensor.forEach { value ->
        val bits: Int = value.toRawBits()
        for (shift in 0 until 32 step 8) {
            hash = hash xor ((bits ushr shift) and 0xFF).toLong()
            hash *= 0x100000001b3L
        }
    }
    return hash.toULong().toString(16).padStart(16, '0')
}

/**
 * The shared half of preprocessing, pinned as a digest (`ios-on-device-inference.md` §3.2, *The
 * runtime fingerprint*).
 *
 * ⚠⚠ **Why a digest and not a tolerance:** a stored vector is only comparable with a query if both
 * were made from the same transform, and the embedding store's stamp does not record the
 * transform. On iOS a hand-bumped `IOS_PREPROCESSING_REVISION` does; this test is what notices the
 * hand forgot. It runs on the JVM, so it is in the gate, where the iOS half
 * (`IosPreprocessingGoldenTest`) is not.
 *
 * ⚠ Float bits, so it is meaningful where `Float` is IEEE single precision: the JVM, Android and
 * Kotlin/Native. A browser computes in doubles; nothing embeds there.
 */
class ImagePreprocessingGoldenTest {

    private val side = 16

    /** Every channel value 0…255 appears, in an order that tells the channels and the rows apart. */
    private fun fixedPixels(): IntArray = IntArray(side * side) { i ->
        val red: Int = (i * 7 + 3) and 0xFF
        val green: Int = (255 - i) and 0xFF
        val blue: Int = (i * 13 + 101) and 0xFF
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    @Test
    fun theSiglipTensorOfAFixedImageHasNotMoved() {
        val tensor: FloatArray = ImagePreprocessing.preprocessPixels(fixedPixels(), side, ImagePreprocessing.Profile.SIGLIP)

        assertEquals(
            SIGLIP_DIGEST,
            tensorDigest(tensor),
            """
            ImagePreprocessing now turns the same pixels into a different tensor.
            Every vector already stored on a device was made with the old one, and nothing in the
            store's stamp says so. If the change is intended:
              1. bump IOS_PREPROCESSING_REVISION (inference-service-proxy/src/iosMain/…/coreml/IosImageDecoder.kt):
                 the iOS fingerprint then clears the stored vectors (ios-on-device-inference.md §3.2);
              2. Android and desktop have no such revision: their stores need the model version
                 bumped or an explicit rebase, or they keep the old vectors;
              3. record the new digest here, and in IosPreprocessingGoldenTest under the new revision.
            If it is not intended, the change is the bug.
            """.trimIndent(),
        )
    }

    @Test
    fun theLayoutIsChannelPlanesInRgbOrder() {
        val tensor: FloatArray = ImagePreprocessing.preprocessPixels(
            IntArray(side * side) { 0xFFFF0000.toInt() },
            side,
            ImagePreprocessing.Profile.SIGLIP,
        )
        val plane: Int = side * side

        assertEquals(1f, tensor[0])
        assertEquals(1f, tensor[plane - 1])
        assertEquals(-1f, tensor[plane])
        assertEquals(-1f, tensor[3 * plane - 1])
    }

    /** ⚠ A reused tensor must hold nothing of the photo before: every element is overwritten. */
    @Test
    fun aReusedTensorIsOverwrittenWhole() {
        val fresh: FloatArray = ImagePreprocessing.preprocessPixels(fixedPixels(), side)
        val reused = FloatArray(3 * side * side) { Float.NaN }

        val returned: FloatArray = ImagePreprocessing.preprocessPixelsInto(fixedPixels(), reused, side)

        assertSame(reused, returned)
        assertContentEquals(fresh, reused)
    }

    @Test
    fun aTensorOfTheWrongSizeIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            ImagePreprocessing.preprocessPixelsInto(fixedPixels(), FloatArray(3 * side * side - 1), side)
        }
    }

    private companion object {
        const val SIGLIP_DIGEST = "64eecf255f4578be"
    }
}
