package org.tenshu.file.proxy.jvm

import org.tenshu.core.file.GetPreview
import org.tenshu.file.proxy.ExifUpright
import org.tenshu.file.proxy.ThumbnailLadderRenderer
import org.tenshu.file.proxy.applyTurn
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The JVM's renders come out **upright** for a JPEG carrying an EXIF orientation (2026-11-01).
 * `ImageIO` ignores the tag, so before this every rung minted on a desktop from a portrait JPEG lay
 * on its side — and a rung is synced, so every device showed it that way.
 *
 * The picture is four flat quadrants, so a turn of any kind moves a known colour to a known corner
 * and survives JPEG.
 */
class JvmUprightRenderTest {

    @Test
    fun `the decode turns every orientation upright`() {
        for (orientation in 1..8) {
            val decoded = assertNotNull(JvmImageDecoder.Default.decode(cameraJpeg(orientation)), "orientation $orientation")
            assertEquals(WIDTH to HEIGHT, decoded.width to decoded.height, "orientation $orientation: shown size")
            assertQuadrants(decoded, "orientation $orientation")
        }
    }

    @Test
    fun `a minted rung is upright - the half-turned selfie and the portrait photo`() {
        val rung = ThumbnailLadderRenderer.Rung(
            px = 32,
            format = GetPreview.Params.FORMAT_JPEG,
            qualities = listOf(null),
            maxBytes = 1_000_000,
        )
        for (orientation in listOf(3, 6)) {
            val outcome = JvmThumbnailLadderRenderer().render("IMG.jpg", cameraJpeg(orientation), listOf(rung))
            val encoded = assertIs<ThumbnailLadderRenderer.Outcome.Encoded>(assertNotNull(outcome).single())
            val image = assertNotNull(ImageIO.read(encoded.bytes.inputStream()))
            assertEquals(32 to 16, image.width to image.height, "orientation $orientation: rung size")
            assertQuadrants(image, "orientation $orientation rung")
        }
    }

    /**
     * The JPEG a camera writes for [orientation]: the upright picture laid out the way that tag
     * describes, plus the tag. Built with the inverse turn through the test reference [applyTurn] —
     * the table is pinned against the spec by `ExifUprightTest`; what this test owns is that the JVM
     * reads the tag and its `AffineTransform` applies it.
     */
    private fun cameraJpeg(orientation: Int): ByteArray {
        val upright = IntArray(WIDTH * HEIGHT) { i -> quadrantColour(i % WIDTH, i / WIDTH, WIDTH, HEIGHT) }
        val inverse = when (orientation) {
            6 -> 8
            8 -> 6
            else -> orientation // 1–5 and 7 undo themselves
        }
        val stored = applyTurn(upright, WIDTH, HEIGHT, ExifUpright.turnFor(inverse))
        val swaps = ExifUpright.swapsAxes(orientation)
        val storedWidth = if (swaps) HEIGHT else WIDTH
        val storedHeight = if (swaps) WIDTH else HEIGHT
        val image = BufferedImage(storedWidth, storedHeight, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, storedWidth, storedHeight, stored, 0, storedWidth)
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        return withOrientationTag(jpeg, orientation)
    }

    /** [jpeg] with an APP1/Exif segment holding only IFD0's Orientation, right after SOI. */
    private fun withOrientationTag(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, // big-endian header, IFD0 at 8
            0, 1, // one entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, // Orientation, SHORT, 1
            0, 0, 0, 0, // no next IFD
        )
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun assertQuadrants(image: BufferedImage, what: String) {
        val w = image.width
        val h = image.height
        for ((x, y) in listOf(w / 4 to h / 4, 3 * w / 4 to h / 4, w / 4 to 3 * h / 4, 3 * w / 4 to 3 * h / 4)) {
            val expected = quadrantColour(x, y, w, h)
            val actual = image.getRGB(x, y)
            assertEquals(nearest(expected), nearest(actual), "$what at ($x, $y)")
        }
    }

    private fun quadrantColour(x: Int, y: Int, w: Int, h: Int): Int = when {
        y < h / 2 && x < w / 2 -> RED
        y < h / 2 -> GREEN
        x < w / 2 -> BLUE
        else -> WHITE
    }

    /** JPEG shifts colours a little; snap to the closest of the four. */
    private fun nearest(argb: Int): Int = listOf(RED, GREEN, BLUE, WHITE).minBy { c ->
        listOf(16, 8, 0).sumOf { shift -> kotlin.math.abs(((argb shr shift) and 0xFF) - ((c shr shift) and 0xFF)) }
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 32
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
