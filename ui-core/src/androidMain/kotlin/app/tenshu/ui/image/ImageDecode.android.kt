package app.tenshu.ui.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/**
 * Decode a byte payload into an `ImageBitmap`, **downsampling** if the source resolution would
 * produce a bitmap larger than Android's hard `Canvas.MAX_BITMAP_SIZE` (~100 MB).
 *
 * Why this matters: `BitmapFactory.decodeByteArray(data, 0, data.size)` always decodes to full
 * source resolution. A 12 MP phone JPEG (4032×3024) is ~48 MB at `ARGB_8888`; an 18+ MP shot
 * (6500×6500) is ~168 MB. `androidx.compose` ultimately renders via
 * `RecordingCanvas.throwIfCannotDraw`, which throws
 * `RuntimeException: Canvas: trying to draw too large(N bytes) bitmap.` the moment anything above
 * ~100 MB hits the canvas — a `FATAL EXCEPTION` that kills the app as the user opens the image.
 *
 * The fix is the standard two-pass `BitmapFactory` pattern:
 * 1. **Bounds pass** with `inJustDecodeBounds = true` — reads the header for dimensions without
 *    allocating pixel memory.
 * 2. **Compute `inSampleSize`** with [decodeSampleSize], which balances two things that are easy to
 *    confuse: it will not land *below* [maxEdge] (the size the caller draws at, where undershooting
 *    means handing back an upscale), and it will not decode *above* [MAX_DECODE_EDGE] (the hard
 *    memory bound, which wins). Sampling is powers of two, so the remainder is an exact scale.
 * 3. **Decode pass**, then [Bitmap.createScaledBitmap] for that remainder, recycling the
 *    intermediate so the *retained* cost is the drawn size rather than the source's.
 *
 * ⚠⚠ [MAX_DECODE_EDGE] = 4096 → 4096×4096 `ARGB_8888` ≈ 64 MB, under the cap with headroom for a
 * pinch-zoom display transform. **A caller that knows the size it draws should pass it**; the
 * default is a bound, not a target, and passing a bound as a target is what briefly made this decode
 * four times more expensive than the naive path it replaced (`DecodeSampleSizeTest`).
 *
 * Small payloads cost nothing extra: a 100×100 cached thumbnail's bounds pass returns small
 * dimensions, `inSampleSize` stays 1, and the decode is identical to the naive path.
 */
actual fun decodeToImageBitmap(data: ByteArray, maxEdge: Int): ImageBitmap? =
    decodeStored(data, maxEdge)?.upright(exifOrientation(data), strict = false)?.asImageBitmap()

/**
 * ⚠⚠ The one place the raw fallback below is wrong: a bitmap that will be **stored**. When the
 * rotated copy cannot be allocated this answers `null` rather than the stored-order bitmap, which
 * a viewer may show for a moment but an edit would save sideways for ever.
 */
actual fun decodeToUprightImageBitmap(data: ByteArray, maxEdge: Int): ImageBitmap? =
    decodeStored(data, maxEdge)?.upright(exifOrientation(data), strict = true)?.asImageBitmap()

/**
 * ⚠⚠ **`BitmapFactory` ignores EXIF orientation** — for HEIC too — while Skia, which decodes on
 * desktop and iOS, applies it. So an original opened in Drive's viewer lay on its side (a portrait
 * photo) or upside down (a half-turned selfie) on Android only (2026-11-01). Thumbnail rungs carry no
 * tag and pass through untouched; an iPhone-minted rung carries 1, which changes nothing either.
 */
private fun exifOrientation(data: ByteArray): Int = runCatching {
    ExifInterface(ByteArrayInputStream(data))
        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

/**
 * This bitmap the way [orientation] says it is shown: mirrored first when the tag says so, then
 * turned clockwise. Itself when nothing changes. Applied after the downsample, so it costs the
 * drawn size, never the source's.
 */
private fun Bitmap.upright(orientation: Int, strict: Boolean): Bitmap? {
    val (mirror, degrees) = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> true to 0f
        ExifInterface.ORIENTATION_ROTATE_180 -> false to 180f
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> true to 180f
        ExifInterface.ORIENTATION_TRANSPOSE -> true to 270f
        ExifInterface.ORIENTATION_ROTATE_90 -> false to 90f
        ExifInterface.ORIENTATION_TRANSVERSE -> true to 90f
        ExifInterface.ORIENTATION_ROTATE_270 -> false to 270f
        else -> return this
    }
    val matrix = Matrix().apply {
        if (mirror) postScale(-1f, 1f)
        postRotate(degrees)
    }
    // ⚠ `Throwable`, not `Exception`: the failure this guards is an `OutOfMemoryError`.
    val turned = try {
        Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    } catch (e: Throwable) {
        null
    } ?: return if (strict) { recycle(); null } else this
    if (turned !== this) recycle()
    return turned
}

private fun decodeStored(data: ByteArray, maxEdge: Int): Bitmap? {
    // Bounds pass — no pixel allocation.
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    val sourceWidth = bounds.outWidth
    val sourceHeight = bounds.outHeight
    if (sourceWidth <= 0 || sourceHeight <= 0) {
        // Header parse failed (corrupt / not an image). Fall through to the naive decode so the
        // null behaviour matches what callers have always seen.
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    // ⚠⚠ Sampling stops one step ABOVE [maxEdge] and the exact remainder is a scale — see
    // [decodeSampleSize]. Sampling all the way down would land a 2048 px rung at 1024 for a 1440 px
    // viewport, i.e. hand the viewer an upscale of a picture it already had sharp.
    val decode = BitmapFactory.Options().apply {
        inSampleSize = decodeSampleSize(maxOf(sourceWidth, sourceHeight), maxEdge)
    }
    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, decode) ?: return null

    val decodedEdge = maxOf(bitmap.width, bitmap.height)
    if (decodedEdge <= maxEdge) return bitmap

    val scale = maxEdge.toFloat() / decodedEdge
    val scaled = Bitmap.createScaledBitmap(
        bitmap,
        // ⚠ Rounded, not truncated: truncation can land a pixel short of the requested edge, which
        // makes the "never below what you draw at" rule a near-miss rather than a rule.
        (bitmap.width * scale).roundToInt().coerceAtLeast(1),
        (bitmap.height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
    // ⚠ The sampled bitmap is a distinct allocation once `createScaledBitmap` returns a new one;
    // letting it go here is what keeps the *retained* cost at the drawn size rather than the rung's.
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}


