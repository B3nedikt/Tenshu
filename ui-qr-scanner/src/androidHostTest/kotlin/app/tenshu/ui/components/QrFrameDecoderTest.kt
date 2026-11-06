package app.tenshu.ui.components

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The analyzer's decode path ([QrFrameDecoder.decodeFrame]), fed planes shaped like CameraX's
 * `YUV_420_888` luminance plane: a direct buffer holding a 1280x720 frame whose rows are padded
 * past the width (`rowStride` > `width`, as camera HALs do) and whose last row stops at the width,
 * with a generated QR code at camera-ish contrast, size, angle and noise.
 */
class QrFrameDecoderTest {

    // Shaped like a real pairing URI (`PairingUri`): a bech32 payload plus both ids.
    private val pairingUri = "tenshu://pair?v=1&d=tp1" + "qpzry9x8gf2tvdw0s3jn54khce6mua7l".repeat(5) +
        "&ownerId=owner-0f3c9a1e-7b2d-4c55-9e1a-2f6d8b4c0a17&deviceId=device-8e21b7c4-55a0-4f3e-b1d2-9c7a6e0f4b83"

    private val inviteLink = "https://tenshu.app/invite#k=Zm9vYmFyYmF6cXV4Zm9vYmFyYmF6cXV4&r=mailbox-42"

    /** Fails the test on an unexpected exception instead of swallowing it as "no code". */
    private fun strictDecoder() = QrFrameDecoder(onUnexpectedFailure = { throw AssertionError(it) })

    @Test
    fun decodesAnUprightCodeInAPaddedFrame() {
        val frame = Frame.render(pairingUri, moduleSize = 5, angleDegrees = 0.0)
        assertEquals(pairingUri, frame.decodeWith(strictDecoder()))
    }

    @Test
    fun decodesARotatedLowContrastNoisyCode() {
        val frame = Frame.render(
            pairingUri, moduleSize = 4, angleDegrees = 23.0,
            dark = 70, light = 180, noise = 25,
        )
        assertEquals(pairingUri, frame.decodeWith(strictDecoder()))
    }

    @Test
    fun decodesACodeHeldSideways() {
        // The camera sensor's frame is landscape while the phone is held portrait: the code
        // arrives rotated by 90°, and no rotation is applied before decoding.
        val frame = Frame.render(inviteLink, moduleSize = 6, angleDegrees = 90.0, noise = 10)
        assertEquals(inviteLink, frame.decodeWith(strictDecoder()))
    }

    @Test
    fun decodesAOneModuleQuietZoneOnADarkSurround() {
        // What the web renders (web/apex QrDisplay, web/drive + web/contacts QrImage:
        // `qrcode` with `margin: 1`, black on white) shown on a dark-mode page: ZXing must find
        // the finder patterns with a single light module between them and the dark surround.
        // The QR spec asks for 4; a phone pointed at a laptop screen sees 1.
        for (data in listOf(pairingUri, inviteLink)) {
            val frame = Frame.render(
                data, moduleSize = 5, angleDegrees = 7.0, margin = 1,
                dark = 25, light = 225, background = 30, noise = 12,
            )
            assertEquals(data, frame.decodeWith(strictDecoder()))
        }
    }

    @Test
    fun oneDecoderReadsFrameAfterFrame() {
        // The analyzer keeps one decoder for the scanner's lifetime: a frame with no code must
        // not leave state behind that breaks the next one.
        val decoder = strictDecoder()
        val empty = Frame.blank()
        val code = Frame.render(inviteLink, moduleSize = 5, angleDegrees = 0.0)
        assertNull(empty.decodeWith(decoder))
        assertEquals(inviteLink, code.decodeWith(decoder))
        assertNull(empty.decodeWith(decoder))
        assertEquals(inviteLink, code.decodeWith(decoder))
    }

    @Test
    fun returnsNullForAFrameWithNoCode() {
        assertNull(Frame.blank(noise = 120).decodeWith(strictDecoder()))
    }

    @Test
    fun compactionDropsRowPaddingAndHonoursPixelStride() {
        val width = 3
        val height = 2
        // Row stride 5, last row short (no trailing padding), as a camera plane ends.
        val padded = byteArrayOf(1, 2, 3, 99, 99, 4, 5, 6)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), compactLuma(ByteBuffer.wrap(padded), 5, 1, width, height, ByteArray(0)).toList())
        // Pixel stride 2 (interleaved samples), row stride 6.
        val interleaved = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), compactLuma(ByteBuffer.wrap(interleaved), 6, 2, width, height, ByteArray(0)).toList())
    }

    @Test
    fun anUnexpectedFailureIsSwallowedAndReportedOnce() {
        // A plane shorter than its declared geometry makes the copy throw — the stand-in for any
        // unexpected ZXing or buffer exception. It must cost the frame, never the process.
        val reported = mutableListOf<RuntimeException>()
        val decoder = QrFrameDecoder(onUnexpectedFailure = { reported += it })
        val tooShort = ByteBuffer.allocateDirect(100)
        assertNull(decoder.decodeFrame(tooShort, 1280, 1, 1280, 720))
        assertNull(decoder.decodeFrame(tooShort, 1280, 1, 1280, 720))
        assertEquals(1, reported.size)
        assertIs<RuntimeException>(reported.single())
        // And the decoder still works afterwards.
        val code = Frame.render(inviteLink, moduleSize = 5, angleDegrees = 0.0)
        assertEquals(inviteLink, code.decodeWith(decoder))
    }

    private class Frame(val plane: ByteBuffer, val rowStride: Int, val width: Int, val height: Int) {
        fun decodeWith(decoder: QrFrameDecoder): String? =
            decoder.decodeFrame(plane, rowStride, 1, width, height)

        companion object {
            private const val WIDTH = 1280
            private const val HEIGHT = 720
            private const val ROW_STRIDE = 1344 // padded rows, as camera HALs deliver them

            fun blank(noise: Int = 0): Frame {
                val random = Random(7)
                return frame { x, y -> jitter(150, noise, random) }
            }

            fun render(
                text: String,
                moduleSize: Int,
                angleDegrees: Double,
                margin: Int = 4,
                dark: Int = 20,
                light: Int = 235,
                // Default: a little darker than the code's white quiet zone, like a desk.
                background: Int = (light - 60).coerceAtLeast(0),
                noise: Int = 0,
            ): Frame {
                val matrix: BitMatrix = QRCodeWriter().encode(
                    text, BarcodeFormat.QR_CODE, 0, 0,
                    mapOf(EncodeHintType.MARGIN to margin),
                )
                val side = matrix.width * moduleSize
                val angle = Math.toRadians(angleDegrees)
                val (c, s) = cos(angle) to sin(angle)
                val (cx, cy) = WIDTH / 2.0 to HEIGHT / 2.0
                val random = Random(11)
                return frame { x, y ->
                    // Inverse-rotate the frame pixel into the code's own coordinates.
                    val dx = x - cx
                    val dy = y - cy
                    val u = (dx * c + dy * s + side / 2.0)
                    val v = (-dx * s + dy * c + side / 2.0)
                    val level = if (u < 0 || v < 0 || u >= side || v >= side) {
                        background
                    } else if (matrix[(u / moduleSize).toInt(), (v / moduleSize).toInt()]) {
                        dark
                    } else {
                        light
                    }
                    jitter(level, noise, random)
                }
            }

            /**
             * A direct buffer laid out as a camera plane: [ROW_STRIDE]-byte rows with garbage in
             * the padding, and a last row that ends at [WIDTH].
             */
            private fun frame(pixel: (Int, Int) -> Int): Frame {
                val bytes = ByteArray(ROW_STRIDE * (HEIGHT - 1) + WIDTH)
                for (y in 0 until HEIGHT) for (x in 0 until ROW_STRIDE) {
                    val i = y * ROW_STRIDE + x
                    if (i >= bytes.size) break
                    bytes[i] = if (x < WIDTH) pixel(x, y).toByte() else (x * 37 + y).toByte()
                }
                val plane = ByteBuffer.allocateDirect(bytes.size).put(bytes)
                plane.rewind()
                return Frame(plane, ROW_STRIDE, WIDTH, HEIGHT)
            }

            private fun jitter(level: Int, noise: Int, random: Random): Int =
                if (noise == 0) level else (level + (random.nextDouble(-1.0, 1.0) * noise).roundToInt()).coerceIn(0, 255)
        }
    }
}
