package app.tenshu.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/*
 * Turning a preview frame into something Compose can draw. Two shapes, because the two
 * backends deliver different things (`desktop-qr-scanning.md` §13 D2).
 *
 * Lifted out of the composable so it can be tested directly — a render path that silently
 * produces a null bitmap looks identical to "the camera hasn't started yet" on screen.
 */

/** Decode a JPEG preview frame — the Swift helper's shape (it must encode to cross a pipe). */
internal fun decodeJpegFrame(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/**
 * Raster a raw BGRA preview frame — the in-process shape. No encode/decode round-trip: the
 * bytes come straight from the `CVPixelBuffer` copy, so Skia reads them as-is.
 *
 * ⚠ [bytesPerRow] is the stride and is **not** necessarily `width * 4`; CoreVideo pads rows
 * for alignment, and passing `width * 4` here silently shears the image on any device whose
 * width isn't already aligned. It is a parameter precisely so it can't be assumed.
 *
 * Alpha is [ColorAlphaType.OPAQUE]: camera frames have no meaningful alpha channel, and
 * claiming premultiplied would invite a needless conversion.
 */
internal fun decodeBgraFrame(
    bytes: ByteArray,
    width: Int,
    height: Int,
    bytesPerRow: Int,
): ImageBitmap? {
    if (width <= 0 || height <= 0 || bytesPerRow < width * BYTES_PER_PIXEL) return null
    if (bytes.size < bytesPerRow * height) return null
    return runCatching {
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        Image.makeRaster(info, bytes, bytesPerRow).toComposeImageBitmap()
    }.getOrNull()
}

private const val BYTES_PER_PIXEL = 4
