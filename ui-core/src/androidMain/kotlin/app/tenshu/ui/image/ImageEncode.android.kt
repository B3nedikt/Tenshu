package app.tenshu.ui.image

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

/**
 * Android `actual`: `Bitmap.compress`. It writes a bare JFIF/PNG/WebP with no EXIF, which is the
 * contract (see the `expect`).
 */
actual fun ImageBitmap.encodeToBytes(format: ImageEncodeFormat, quality: Int): ByteArray {
    val encodeQuality = encodeQualityFor(format, quality)
    val source = asAndroidBitmap()
    // ⚠ A HARDWARE bitmap lives in GPU memory; copy it to the heap rather than depend on
    // `compress` reading it back. `decodeToImageBitmap` never makes one, a Compose `GraphicsLayer`
    // capture can.
    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw ImageEncodeException("Could not copy a hardware bitmap for encoding")
    } else {
        source
    }
    val out = ByteArrayOutputStream()
    val ok = try {
        bitmap.compress(format.toCompressFormat(), encodeQuality, out)
    } catch (e: RuntimeException) {
        throw ImageEncodeException("Bitmap.compress threw for $format", e)
    } finally {
        if (bitmap !== source) bitmap.recycle()
    }
    if (!ok || out.size() == 0) throw ImageEncodeException("Bitmap.compress refused $format")
    return out.toByteArray()
}

@Suppress("DEPRECATION")
private fun ImageEncodeFormat.toCompressFormat(): Bitmap.CompressFormat = when (this) {
    ImageEncodeFormat.JPEG -> Bitmap.CompressFormat.JPEG
    ImageEncodeFormat.PNG -> Bitmap.CompressFormat.PNG
    // ⚠ Below API 30 the only WebP is the deprecated `WEBP`, which goes **lossless at quality
    // 100** — `encodeQualityFor` caps WebP at 99, so it never gets there.
    ImageEncodeFormat.WEBP ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP
}
