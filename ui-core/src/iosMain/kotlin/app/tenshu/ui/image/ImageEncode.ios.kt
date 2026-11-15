package app.tenshu.ui.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * iOS `actual`: Skia's own encoders, which write no EXIF (see the `expect`). The desktop actual is
 * the same code; the two source sets share no Skia parent, which is why `ImageDecode` is
 * duplicated the same way.
 */
actual fun ImageBitmap.encodeToBytes(format: ImageEncodeFormat, quality: Int): ByteArray {
    val encodeQuality = encodeQualityFor(format, quality)
    val image = Image.makeFromBitmap(asSkiaBitmap())
    try {
        val data = try {
            image.encodeToData(format.toSkia(), encodeQuality)
        } catch (e: Exception) {
            throw ImageEncodeException("Skia threw encoding $format", e)
        } ?: throw ImageEncodeException("Skia refused $format")
        try {
            return data.bytes.also { if (it.isEmpty()) throw ImageEncodeException("Skia wrote no $format bytes") }
        } finally {
            data.close()
        }
    } finally {
        image.close()
    }
}

private fun ImageEncodeFormat.toSkia(): EncodedImageFormat = when (this) {
    ImageEncodeFormat.JPEG -> EncodedImageFormat.JPEG
    ImageEncodeFormat.PNG -> EncodedImageFormat.PNG
    ImageEncodeFormat.WEBP -> EncodedImageFormat.WEBP
}
