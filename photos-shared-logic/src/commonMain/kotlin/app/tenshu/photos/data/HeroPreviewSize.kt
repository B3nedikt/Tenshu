package app.tenshu.photos.data

import org.tenshu.core.file.GetStoredThumbnail
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The preview size the full-screen viewer asks [PhotosRepository.preview] for: the photograph's long
 * edge as it is actually drawn — fitted into a viewport [viewportWidthPx] × [viewportHeightPx]
 * **physical** pixels — rounded up to a [HERO_BUCKET_PX] step (`photos-app.md` §34).
 *
 * ⚠⚠ **Measured, never a constant.** The viewer asked for a flat 1024 px, which on a 3456 × 2234
 * retina panel is a ~3× upscale of the hero rung however the window is sized, and gets worse as the
 * window grows. The size a device can actually serve is the repository's and the proxy's business —
 * the stored rungs, a render from a local original — and it cannot choose well from a number that
 * says nothing about the screen.
 *
 * ⚠ **Never below the hero rung** ([GetStoredThumbnail.XLARGE_PX]): below it the repository would
 * take the `LARGE` rung for a full-screen picture, and a small window would swap to a softer image
 * than the one it already had. **Never above the photograph's own long edge**, when known: a render
 * cannot add pixels, so asking for more only costs a device that holds the original a render.
 * **Never above [MAX_HERO_PX].**
 *
 * ⚠ Stepped, so a window drag re-asks at a handful of sizes rather than at every pixel — the caller
 * keys its load on this value.
 *
 * ⚠⚠ [pixelWidth] and [pixelHeight] are the file's stored dimensions, **before EXIF orientation**, and
 * a rotated photograph fitted on the stored axes can be understated as well as overstated: a stored
 * 4032 × 3024 frame shown as portrait in a 1440 × 3200 viewport is drawn 1920 px tall, while fitting
 * it as stored gives 1440. So both orientations are fitted and the larger is asked
 * for — an overstatement costs bytes, an understatement costs sharpness.
 */
fun heroPreviewRequestPx(
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    pixelWidth: Int? = null,
    pixelHeight: Int? = null,
): Int {
    if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return GetStoredThumbnail.XLARGE_PX
    val w = pixelWidth?.takeIf { it > 0 }
    val h = pixelHeight?.takeIf { it > 0 }
    val drawn = if (w == null || h == null) {
        max(viewportWidthPx, viewportHeightPx).toDouble()
    } else {
        val asStored = min(viewportWidthPx.toDouble() / w, viewportHeightPx.toDouble() / h)
        val rotated = min(viewportWidthPx.toDouble() / h, viewportHeightPx.toDouble() / w)
        min(max(w, h) * max(asStored, rotated), max(w, h).toDouble())
    }
    val stepped = ceil(drawn / HERO_BUCKET_PX).toInt() * HERO_BUCKET_PX
    return stepped.coerceIn(GetStoredThumbnail.XLARGE_PX, MAX_HERO_PX)
}

/** The step a viewer's request is rounded up to. */
const val HERO_BUCKET_PX: Int = 512

/**
 * The largest request. ⚠ Above a 5K panel's long edge, where a render from the original becomes a
 * multi-megabyte encode per page for a difference nobody can see at fit scale.
 */
const val MAX_HERO_PX: Int = 4096
