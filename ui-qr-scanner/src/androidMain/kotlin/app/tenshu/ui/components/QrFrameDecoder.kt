package app.tenshu.ui.components

import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

/**
 * Decodes a QR code from one camera frame's luminance plane, with ZXing — pure Java, on the
 * device, no network and no Play services. It replaced ML Kit, whose bundled barcode model logs
 * every scan session to Google (`firebaselogging.googleapis.com`, via `datatransport`) and whose
 * `transport-backend-cct` merged `INTERNET` into Drive's manifest.
 *
 * A camera frame's `Y` plane (CameraX `YUV_420_888`) *is* the greyscale image ZXing wants, so no
 * colour conversion and no rotation: QR detection finds the code at any angle.
 *
 * ⚠ Never throws: the scanner runs inside Control's pairing wizard, and an unexpected ZXing or
 * buffer exception must cost one frame, not the process. The first one is reported through
 * [onUnexpectedFailure]; later ones are dropped silently, since every frame would repeat it.
 *
 * Not thread-safe (the reader and the luma buffer are reused between calls); the analyzer calls
 * it from its own single-thread executor.
 */
internal class QrFrameDecoder(
    private val onUnexpectedFailure: (RuntimeException) -> Unit = { e ->
        Log.w("QrFrameDecoder", "QR frame decode failed; skipping frames that fail this way", e)
    },
) {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        // Scan every row for finder patterns rather than skipping: costs a few ms on an analysis
        // frame, and finds a small or distant code that the default pass misses.
        DecodeHintType.TRY_HARDER to true,
    )
    private var luma = ByteArray(0)
    private var reported = false

    /**
     * Decodes one camera plane: [plane] holds [height] rows [rowStride] bytes apart, one sample
     * every [pixelStride] bytes (1 for a `YUV_420_888` `Y` plane); the last row may stop short of
     * [rowStride].
     *
     * @return the code's text, or null when the frame holds no readable QR code — or when decoding
     *   failed unexpectedly (reported once, see the class doc).
     */
    fun decodeFrame(plane: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int): String? =
        try {
            luma = compactLuma(plane, rowStride, pixelStride, width, height, luma)
            decode(luma, width, height)
        } catch (e: RuntimeException) {
            if (!reported) {
                reported = true
                onUnexpectedFailure(e)
            }
            null
        }

    /** [luma] is `width × height` packed rows: ZXing then reads it in place, without a copy. */
    private fun decode(luma: ByteArray, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            // NotFound / Checksum / Format: no code in this frame, or not yet in focus.
            null
        } finally {
            reader.reset()
        }
    }
}

/**
 * The plane's samples as `width × height` packed bytes, reusing [reuse] when it is the right size.
 * The camera's buffer is direct and only valid until its frame is closed, so it must be copied
 * anyway; dropping the row padding while copying lets ZXing use the array as it is (with
 * `dataWidth == width` its `getMatrix()` returns the array instead of copying it again).
 */
internal fun compactLuma(
    plane: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
    reuse: ByteArray,
): ByteArray {
    val out = if (reuse.size == width * height) reuse else ByteArray(width * height)
    val src = plane.duplicate()
    if (pixelStride == 1) {
        for (y in 0 until height) {
            src.position(y * rowStride)
            src.get(out, y * width, width)
        }
    } else {
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) out[y * width + x] = src.get(row + x * pixelStride)
        }
    }
    return out
}
