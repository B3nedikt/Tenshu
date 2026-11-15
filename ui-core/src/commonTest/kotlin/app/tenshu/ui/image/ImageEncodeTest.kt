package app.tenshu.ui.image

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decode → encode → decode, on every target `commonTest` runs on (desktop, and the iOS simulator
 * through `iosTestCheck`). There is no Android host test for `:ui-core`, so the Android actual is
 * covered by the smoke gate only.
 *
 * ⚠⚠ The orientation tests are the point. They pin both halves of the `encodeToBytes` contract:
 * the decode hands over **upright** pixels for a rotated EXIF source, and the encode writes them
 * **without** an orientation tag, so a second decode does not turn them again.
 */
class ImageEncodeTest {

    @Test
    fun aRotatedExifSourceDecodesUpright() {
        val bitmap = assertNotNull(decodeToImageBitmap(ROTATED_EXIF_6_JPEG))
        assertUprightFixture(bitmap, "the decode")
    }

    @Test
    fun anEncodedJpegOfARotatedSourceIsStillUprightWhenDecodedAgain() {
        val upright = assertNotNull(decodeToImageBitmap(ROTATED_EXIF_6_JPEG))

        val bytes = upright.encodeToBytes(ImageEncodeFormat.JPEG, quality = 95)

        assertTrue(bytes.isJpeg(), "JPEG asked for, JPEG written")
        assertFalse(bytes.hasExifSegment(), "no EXIF is carried — so no orientation tag either")
        assertUprightFixture(assertNotNull(decodeToImageBitmap(bytes)), "the re-decode")
    }

    @Test
    fun pngRoundTripsLosslessly() {
        val source = quadrants()

        val decoded = assertNotNull(decodeToImageBitmap(source.encodeToBytes(ImageEncodeFormat.PNG)))

        assertEquals(source.width, decoded.width)
        assertEquals(source.height, decoded.height)
        assertContentEquals(source.pixels(), decoded.pixels(), "PNG is lossless")
    }

    @Test
    fun webpRoundTrips() {
        val bytes = quadrants().encodeToBytes(ImageEncodeFormat.WEBP, quality = 90)

        assertTrue(bytes.isWebp(), "WebP asked for, WebP written")
        val decoded = assertNotNull(decodeToImageBitmap(bytes))
        assertEquals(8, decoded.width)
        assertEquals(8, decoded.height)
        assertTrue(decoded.pixelAt(1, 1).isNear(RED), "top-left quadrant survives a lossy encode")
    }

    @Test
    fun webpAtQualityHundredStaysLossy() {
        // The contract is lossy only. ⚠ Runs on Skia only, which stays lossy at 100 even uncapped
        // (measured); the cap exists for Android's pre-30 `WEBP`, which no test here reaches.
        val bytes = quadrants().encodeToBytes(ImageEncodeFormat.WEBP, quality = 100)

        // Skia may wrap it in VP8X + ALPH, so look for the chunk rather than at a fixed offset.
        val chunks = bytes.decodeToString(12, bytes.size)
        assertTrue("VP8 " in chunks, "a lossy VP8 chunk")
        assertFalse("VP8L" in chunks, "not a lossless VP8L one")
    }

    @Test
    fun jpegDropsAlphaToBlack() {
        val transparent = ImageBitmap(4, 4) // fully transparent

        val decoded = assertNotNull(decodeToImageBitmap(transparent.encodeToBytes(ImageEncodeFormat.JPEG)))

        assertTrue(decoded.pixelAt(2, 2).isNear(Color.Black), "the documented JPEG alpha rule")
    }

    @Test
    fun qualityOutsideZeroToHundredIsRejected() {
        val bitmap = ImageBitmap(2, 2)
        assertFailsWith<IllegalArgumentException> { bitmap.encodeToBytes(ImageEncodeFormat.JPEG, quality = 101) }
        assertFailsWith<IllegalArgumentException> { bitmap.encodeToBytes(ImageEncodeFormat.JPEG, quality = -1) }
    }

    /** 16×32, red on top, blue below — `ROTATED_EXIF_6_JPEG` the way it is meant to be seen. */
    private fun assertUprightFixture(bitmap: ImageBitmap, what: String) {
        assertEquals(16, bitmap.width, "$what: the 90° tag swaps the edges")
        assertEquals(32, bitmap.height, "$what: the 90° tag swaps the edges")
        // Sample well inside each half, away from the JPEG blur at the seam.
        assertTrue(bitmap.pixelAt(8, 6).isNear(RED), "$what: red is on top (turned clockwise)")
        assertTrue(bitmap.pixelAt(8, 25).isNear(BLUE), "$what: blue is below")
    }

    /** 8×8: red, green / blue, white. Non-uniform, so a lossless round trip has something to keep. */
    private fun quadrants(): ImageBitmap {
        val bitmap = ImageBitmap(8, 8)
        val canvas = Canvas(bitmap)
        listOf(RED to Offset(0f, 0f), GREEN to Offset(4f, 0f), BLUE to Offset(0f, 4f), Color.White to Offset(4f, 4f))
            .forEach { (color, at) -> canvas.drawRect(at, Size(4f, 4f), color) }
        return bitmap
    }

    private fun Canvas.drawRect(at: Offset, size: Size, color: Color) =
        drawRect(at.x, at.y, at.x + size.width, at.y + size.height, Paint().apply { this.color = color })

    private fun ImageBitmap.pixels(): IntArray = IntArray(width * height).also { readPixels(it) }

    private fun ImageBitmap.pixelAt(x: Int, y: Int): Color =
        Color(IntArray(1).also { readPixels(it, startX = x, startY = y, width = 1, height = 1) }[0])

    private fun Color.isNear(other: Color, tolerance: Float = 0.2f): Boolean =
        kotlin.math.abs(red - other.red) < tolerance &&
            kotlin.math.abs(green - other.green) < tolerance &&
            kotlin.math.abs(blue - other.blue) < tolerance

    private fun ByteArray.isJpeg() = size > 2 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()

    private fun ByteArray.isWebp() =
        size > 12 && decodeToString(0, 4) == "RIFF" && decodeToString(8, 12) == "WEBP"

    /** An APP1 segment whose payload starts `Exif\0\0`, anywhere before the scan. */
    private fun ByteArray.hasExifSegment(): Boolean {
        var i = 2
        while (i + 4 < size && this[i] == 0xFF.toByte()) {
            val marker = this[i + 1].toInt() and 0xFF
            if (marker == 0xDA) return false // start of scan: no more metadata segments
            val length = ((this[i + 2].toInt() and 0xFF) shl 8) or (this[i + 3].toInt() and 0xFF)
            if (marker == 0xE1 && i + 10 <= size && decodeToString(i + 4, i + 8) == "Exif") return true
            i += 2 + length
        }
        return false
    }

    private companion object {
        val RED = Color(0xFFE61414)
        val GREEN = Color(0xFF14E614)
        val BLUE = Color(0xFF1414E6)
    }
}
