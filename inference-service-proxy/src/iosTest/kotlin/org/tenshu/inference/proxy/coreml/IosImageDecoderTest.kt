package org.tenshu.inference.proxy.coreml

import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [IosImageDecoder] on the simulator, against JPEGs written in the test
 * (`ios-on-device-inference.md` §1.2, §5 PR-1).
 */
class IosImageDecoderTest {

    private val red = 0xE01010
    private val green = 0x10C010
    private val blue = 0x1010E0
    private val white = 0xF0F0F0

    /** Stored quadrants: red top-left, green top-right, blue bottom-left, white bottom-right. */
    private fun quadrants(orientation: Int?): ByteArray =
        testJpeg(width = 200, height = 100, orientation = orientation) { x, y ->
            when {
                x < 100 && y < 50 -> red
                y < 50 -> green
                x < 100 -> blue
                else -> white
            }
        }

    private fun assertColour(expected: Int, argb: IntArray, size: Int, x: Int, y: Int, what: String) {
        val actual: Int = argb[y * size + x]
        val close: Boolean = listOf(16, 8, 0).all { shift ->
            abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF)) <= 40
        }
        assertTrue(close, "$what at ($x, $y): expected #${expected.toString(16)}, got #${(actual and 0xFFFFFF).toString(16)}")
    }

    @Test
    fun anUntaggedJpegKeepsItsStoredLayout() {
        val size = 32
        val argb: IntArray = assertNotNull(IosImageDecoder.decode(quadrants(orientation = null), size, centreCrop = false))

        assertColour(red, argb, size, 8, 8, "top-left")
        assertColour(green, argb, size, 24, 8, "top-right")
        assertColour(blue, argb, size, 8, 24, "bottom-left")
        assertColour(white, argb, size, 24, 24, "bottom-right")
    }

    @Test
    fun anExifSixJpegIsDecodedUpright() {
        // Orientation 6: the stored top row is the picture's right-hand side, so the picture as a
        // person sees it is the stored pixels turned 90° clockwise.
        val size = 32
        val argb: IntArray = assertNotNull(IosImageDecoder.decode(quadrants(orientation = 6), size, centreCrop = false))

        assertColour(blue, argb, size, 8, 8, "top-left")
        assertColour(red, argb, size, 24, 8, "top-right")
        assertColour(white, argb, size, 8, 24, "bottom-left")
        assertColour(green, argb, size, 24, 24, "bottom-right")
    }

    /** 4:1, green on the left quarter, red across the middle half, blue on the right quarter. */
    private fun bands(): ByteArray = testJpeg(width = 400, height = 100) { x, _ ->
        when {
            x < 100 -> green
            x < 300 -> red
            else -> blue
        }
    }

    @Test
    fun aWideImageIsSquashedNotCropped() {
        val size = 32
        val argb: IntArray = assertNotNull(IosImageDecoder.decode(bands(), size, centreCrop = false))

        // A centre crop would be red from edge to edge: the outer quarters only survive a squash.
        assertColour(green, argb, size, 2, 16, "left edge")
        assertColour(red, argb, size, 16, 16, "centre")
        assertColour(blue, argb, size, 29, 16, "right edge")
    }

    @Test
    fun centreCropKeepsOnlyTheMiddleSquare() {
        val size = 32
        val argb: IntArray = assertNotNull(IosImageDecoder.decode(bands(), size, centreCrop = true))

        assertColour(red, argb, size, 2, 16, "left edge")
        assertColour(red, argb, size, 29, 16, "right edge")
    }

    /** `AndroidImageDecoder.kt:22-23`, copied: the rule [decodeLongEdge] must agree with below its cap. */
    private fun androidDecodedSize(width: Int, height: Int, size: Int): Pair<Int, Int> {
        var sample = 1
        while (minOf(width, height) / (sample * 2) >= size * 2) sample *= 2
        return width / sample to height / sample
    }

    @Test
    fun theDecodeSizeIsAndroidsShortEdgeRule() {
        val side = 256
        // A 12 MP camera photo, both ways up; a small file; a 6:1 panorama, small and large.
        listOf(4032 to 3024, 3024 to 4032, 640 to 480, 3072 to 512, 12288 to 2048, 8064 to 6048, 511 to 300)
            .forEach { (width, height) ->
                val (androidWidth, androidHeight) = androidDecodedSize(width, height, side)
                assertEquals(maxOf(androidWidth, androidHeight), decodeLongEdge(width, height, side), "$width × $height")
            }
        assertEquals(1008, decodeLongEdge(4032, 3024, side))
        assertEquals(3072, decodeLongEdge(12288, 2048, side))
    }

    @Test
    fun anOrdinaryPhotoIsDecodedAtAndroidsSize() {
        val jpeg: ByteArray = testJpeg(width = 1600, height = 1200) { x, _ -> if (x < 800) red else blue }

        // Halved once: 1200 / 2 = 600 is still two sides, 1200 / 4 = 300 is not.
        assertEquals(800 to 600, IosImageDecoder.decodedDimensions(jpeg, 256))
        val argb: IntArray = assertNotNull(IosImageDecoder.decode(jpeg, 256, centreCrop = false))
        assertColour(red, argb, 256, 64, 128, "left half")
        assertColour(blue, argb, 256, 192, 128, "right half")
    }

    @Test
    fun aWidePanoramaIsNeverUpsampledBeforeTheSquash() {
        val side = 256
        val jpeg: ByteArray = testJpeg(width = 3072, height = 512) { x, _ ->
            when {
                x < 768 -> green
                x < 2304 -> red
                else -> blue
            }
        }

        val (width, height) = assertNotNull(IosImageDecoder.decodedDimensions(jpeg, side))

        // 6:1. Bounding the long edge at two sides would leave 512 × 85.
        assertEquals(3072 to 512, width to height)
        assertTrue(minOf(width, height) >= side, "the short edge ($height) must not be below the model's side")
        val argb: IntArray = assertNotNull(IosImageDecoder.decode(jpeg, side, centreCrop = false))
        assertColour(green, argb, side, 16, 128, "left edge")
        assertColour(blue, argb, side, 240, 128, "right edge")
    }

    @Test
    fun anExtremeStripIsCappedOnItsLongEdge() {
        val side = 256
        val cap: Int = LONG_EDGE_CAP_SIDES * side
        val jpeg: ByteArray = testJpeg(width = 8000, height = 200) { x, _ -> if (x < 4000) green else blue }

        val (width, height) = assertNotNull(IosImageDecoder.decodedDimensions(jpeg, side))

        // 40:1, past the 16:1 where the short edge can stay at the model's side.
        assertEquals(cap, width)
        assertEquals(102, height)
        assertEquals(256 * 256 * 256, cap * (4 * side) * 4, "the stated bound: 16.8 MB of RGBA at side 256")
        assertTrue(width.toLong() * height * 4 <= cap.toLong() * (4 * side) * 4)
        assertEquals(side * side, assertNotNull(IosImageDecoder.decode(jpeg, side, centreCrop = false)).size)
    }

    @Test
    fun theCapHoldsForAnyShape() {
        val side = 256
        val cap: Int = LONG_EDGE_CAP_SIDES * side
        // The short edge stays at the model's side up to 16:1, and the decoded image inside the bound.
        assertEquals(cap, decodeLongEdge(16 * 1024, 1024, side))
        assertEquals(cap, decodeLongEdge(1000, 20_000, side))
        assertEquals(cap, decodeLongEdge(100_000, 6_000, side))
        listOf(16 * 1024 to 1024, 20_000 to 1000, 100_000 to 6_000, 65_535 to 2).forEach { (long, short) ->
            val decodedLong: Int = decodeLongEdge(long, short, side)
            val decodedShort: Long = short.toLong() * decodedLong / long
            assertTrue(decodedLong <= cap && decodedShort < 4 * side, "$long × $short")
        }
        assertTrue(1024L * decodeLongEdge(16 * 1024, 1024, side) / (16 * 1024) >= side)
    }

    @Test
    fun theOutputIsExactlySizeSquaredOpaquePixels() {
        val jpeg: ByteArray = quadrants(orientation = null)

        listOf(16, 32, 256).forEach { size ->
            val argb: IntArray = assertNotNull(IosImageDecoder.decode(jpeg, size, centreCrop = false))
            assertEquals(size * size, argb.size, "side $size")
            assertTrue(argb.all { (it ushr 24) == 0xFF }, "side $size: every pixel is opaque ARGB")
        }
    }

    @Test
    fun theOutputIsWhatTheSharedArithmeticTakes() {
        val side: Int = 256
        val argb: IntArray = assertNotNull(IosImageDecoder.decode(bands(), side, centreCrop = false))

        val chw: FloatArray = ImagePreprocessing.preprocessPixels(argb, side, ImagePreprocessing.Profile.SIGLIP)

        assertEquals(3 * side * side, chw.size)
        // Row 128, column 128 is red: the red plane near +1, the green and blue planes near -1.
        val at: Int = 128 * side + 128
        assertTrue(chw[at] > 0.5f && chw[side * side + at] < -0.5f && chw[2 * side * side + at] < -0.5f)
    }

    @Test
    fun bytesThatAreNotAnImageDecodeToNull() {
        assertNull(IosImageDecoder.decode(ByteArray(4096) { (it * 7).toByte() }, 32, centreCrop = false))
        assertNull(IosImageDecoder.decode("not a photograph".encodeToByteArray(), 32, centreCrop = false))
        assertNull(IosImageDecoder.decode(ByteArray(0), 32, centreCrop = false))
    }

    @Test
    fun aJpegCutShortOfItsPixelsDecodesToNullOrToAFullSquare() {
        // A truncated download. ImageIO may salvage it or refuse it; it must not return a short array.
        val jpeg: ByteArray = quadrants(orientation = null)

        val argb: IntArray? = IosImageDecoder.decode(jpeg.copyOf(jpeg.size / 4), 32, centreCrop = false)

        assertTrue(argb == null || argb.size == 32 * 32)
    }
}
