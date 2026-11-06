package app.tenshu.ui.image

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⚠⚠ **The Skia downscale path, which nothing else executes.** Every other decode test in this
 * repo feeds a 1×1 PNG, so `downscaledTo` returns its receiver on the first line and the whole
 * resize — the `Surface`, the snapshot, the `close()` calls — is never run. This file exists to
 * run it, because the failure mode there is a native use-after-free rather than a wrong answer.
 *
 * Specifically: the resize returns `surface.makeImageSnapshot().toComposeImageBitmap()` and then
 * closes the `Surface` in a `finally`. That is only safe if the snapshot owns (or shares by
 * refcount) its pixels rather than borrowing the surface's. Reading a pixel back after the close
 * is what proves it, and a mistake here would be a crash on the viewer's own path
 * (`drive-xxlarge-thumbnail-rung.md` §4.5).
 */
class DesktopDownscaleTest {

    @Test
    fun aSourceOverTheCeilingIsResizedAndStillReadableAfterTheSurfaceIsClosed() {
        val bitmap = assertNotNull(
            decodeToImageBitmap(gradientPng(width = 800, height = 600), maxEdge = 200),
            "an 800 px source must decode",
        )

        assertEquals(200, bitmap.width, "the long edge is the ceiling asked for")
        assertEquals(150, bitmap.height, "and the aspect ratio is kept")

        // ⚠ The point of the test: touch the pixels *after* `downscaledTo`'s `finally` has run.
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(pixels)
        assertTrue(pixels.any { it != 0 }, "the snapshot must survive its surface being closed")
    }

    @Test
    fun aSourceUnderTheCeilingIsUntouched() {
        val bitmap = assertNotNull(decodeToImageBitmap(gradientPng(120, 90), maxEdge = 4096))
        assertEquals(120, bitmap.width)
        assertEquals(90, bitmap.height)
    }

    /** ⚠ Non-uniform, so a resize that dropped the pixels entirely would not read as "any != 0". */
    private fun gradientPng(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            image.setRGB(x, y, ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) or 0x40)
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
