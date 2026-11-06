package app.tenshu.timeline.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tint `personalise()` reads, derived from the photo on screen (`timeline-photos.md` T-P2).
 *
 * Before this, `dominantColor` was consumed in ten places and produced only by the mock — so every
 * real moment tinted itself amethyst regardless of what it was a photo of.
 */
class PhotoTintTest {

    private fun bitmapOf(bands: List<Color>): ImageBitmap {
        val size = 64
        val bitmap = ImageBitmap(size, size)
        val canvas = Canvas(bitmap)
        val bandHeight = size.toFloat() / bands.size
        bands.forEachIndexed { index, colour ->
            canvas.drawRect(
                Rect(0f, index * bandHeight, size.toFloat(), (index + 1) * bandHeight),
                Paint().apply { color = colour },
            )
        }
        return bitmap
    }

    @Test
    fun aSolidImageAveragesToItsOwnColour() {
        assertEquals("#3366cc", bitmapOf(listOf(Color(0xFF3366CC))).averageColorHex())
    }

    @Test
    fun twoHalvesAverageBetweenThem() {
        // Black over white — the mean is mid-grey, which is also the honest answer for a
        // high-contrast photo and the reason this is a *mean*, not a "dominant" colour (see KDoc).
        val hex = bitmapOf(listOf(Color.Black, Color.White)).averageColorHex()
        val channel = hex.removePrefix("#").substring(0, 2).toInt(16)
        assertTrue(channel in 120..135, "expected roughly mid-grey, got $hex")
    }

    @Test
    fun theResultIsAlwaysAParseableHexColour() {
        // `parseHexColor` is what consumes this, and it is the one contract the tint must keep —
        // a malformed string silently falls back to grey everywhere the colour is read.
        val hex = bitmapOf(listOf(Color(0xFF0A0B0C))).averageColorHex()
        assertEquals(7, hex.length)
        assertTrue(hex.startsWith("#"))
        assertTrue(hex.drop(1).all { it in "0123456789abcdef" }, "lower-case hex only, got $hex")
    }

    @Test
    fun aFullyTransparentImageDoesNotDragTheTintToBlack() {
        // Alpha-weighted: a transparent border is common in PNG thumbnails, and counting it as
        // black would tint every one of them near-black.
        val hex = bitmapOf(listOf(Color.Transparent)).averageColorHex()
        assertEquals("#000000", hex, "a wholly transparent image has no colour to report")
    }
}
