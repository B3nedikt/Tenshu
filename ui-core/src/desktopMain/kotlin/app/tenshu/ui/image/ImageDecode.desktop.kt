package app.tenshu.ui.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.tenshu.macos.MacImageDecoder
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.roundToInt

/**
 * Decode via Skia, falling back to macOS's own ImageIO codecs for anything Skia turns down.
 *
 * The fallback exists for HEIC — Skiko's Skia build carries no HEIF/HEVC codec, so iPhone photos
 * fail here even though macOS decodes them natively (see [MacImageDecoder]). It is deliberately
 * keyed on *Skia failing* rather than on a format list: ImageIO reads a good deal Skia doesn't
 * (exotic TIFFs, camera raw, AVIF on recent macOS), and this way each of them is covered without
 * anyone having to enumerate it. The common case costs nothing — JPEG and PNG succeed on the
 * first line and never spawn anything.
 */
actual fun decodeToImageBitmap(data: ByteArray, maxEdge: Int): ImageBitmap? {
    if (data.isEmpty()) return null
    // ⚠ The resize is inside the same `try` as the decode: `Surface.makeRasterN32Premul` throws on
    // an allocation failure, and every caller of this function is written against `null`, not an
    // exception. (The iOS actual already had it inside its own guard.)
    return try {
        skiaDecode(data)?.let { return it.downscaledTo(maxEdge) }
        // Skia said no. On a Mac, ask ImageIO — it re-encodes to PNG/JPEG, which Skia then reads
        // happily.
        val transcoded = MacImageDecoder.decode(data) ?: return null
        skiaDecode(transcoded)?.downscaledTo(maxEdge)
    } catch (e: Exception) {
        null
    }
}

private fun skiaDecode(data: ByteArray): ImageBitmap? = try {
    Image.makeFromEncoded(data).toComposeImageBitmap()
} catch (e: Exception) {
    null
}

/**
 * Skia decodes whole images, so the ceiling is applied afterwards. That does not save the *peak* the
 * way Android's `inSampleSize` does, but it is the **retained** size that matters here: the viewer's
 * pager keeps three pages composed, so holding each at the size it is drawn rather than at the rung's
 * own 2048 px is the difference between ~24 MiB and ~48 MiB of live bitmaps
 * (`drive-xxlarge-thumbnail-rung.md` §4.5).
 */
private fun ImageBitmap.downscaledTo(maxEdge: Int): ImageBitmap {
    val edge = maxOf(width, height)
    if (edge <= maxEdge || maxEdge <= 0) return this
    val scale = maxEdge.toFloat() / edge
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    val source = Image.makeFromBitmap(asSkiaBitmap())
    val surface = Surface.makeRasterN32Premul(targetWidth, targetHeight)
    var snapshot: Image? = null
    try {
        surface.canvas.drawImageRect(
            image = source,
            src = Rect.makeWH(width.toFloat(), height.toFloat()),
            dst = Rect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()),
            // ⚠⚠ **Explicit, because skiko's default is point sampling.** The two-argument
            // `drawImageRect` passes `SamplingMode.DEFAULT`, which is
            // `FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)` — so a 2048 px rung drawn at
            // 1536 px would simply drop every fourth row and column: aliased edges and moiré on
            // fine texture, on the one surface this phase exists to make sharper. `LINEAR` is
            // bilinear, which is what Android's `createScaledBitmap(..., filter = true)` does, so
            // the platforms agree.
            samplingMode = SamplingMode.LINEAR,
            paint = null,
            strict = true,
        )
        // ⚠⚠ The snapshot is closed too, and that is the half that matters: it carries a
        // target-size pixel buffer, so closing only the `Surface` would free the wrapper and leave
        // ~45 MB of natives per photograph to skiko's cleaner — the very thing this block claims to
        // prevent. Safe because `toComposeImageBitmap()` is `SkiaBackedImageBitmap(toBitmap())`,
        // a full copy into an independent allocation, not a view onto these pixels.
        snapshot = surface.makeImageSnapshot()
        return snapshot.toComposeImageBitmap()
    } finally {
        // ⚠ Skiko natives are freed by a cleaner otherwise — per photograph, in a pager, that is
        // native memory held at GC's convenience.
        snapshot?.close()
        source.close()
        surface.close()
    }
}
