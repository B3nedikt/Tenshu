package app.tenshu.photos.data

import org.tenshu.core.file.GetStoredThumbnail

/**
 * The preview size a square, centre-cropped tile drawn [tileEdgePx] **physical** pixels across asks
 * [PhotosRepository.preview] for: the `LARGE` rung while that rung can fill the tile, the hero rung
 * once it cannot (`drive-synced-thumbnails.md` §16).
 *
 * ⚠⚠ **Physical pixels, measured — never a constant.** The grid used to ask for a flat 256 px on the
 * premise that a 3-across phone tile is "~130 px". That is 130 **dp**: on a 3x phone the tile is
 * ~400 px, and a retina desktop draws the same fixed three columns several times larger. Once the
 * resolver began answering 256 px requests with the 256 px class, every tile was a 2x upscale.
 *
 * ⚠ What fills a crop is the class's **short** edge, so the photo's shape decides: `LARGE` is
 * 256×256 for a square photo, 256×192 at 4:3, 256×144 at 16:9 and 256×118 for a phone screenshot.
 * [pixelWidth] and [pixelHeight] give that ratio (a rotation does not change it); without them the
 * camera default, 4:3, is assumed.
 *
 * ⚠ Only two answers, so the value is stable while a tile resizes — a caller keys its load and its
 * cache on it, and a size that tracked every pixel of a window drag would re-decode on each one.
 */
fun tilePreviewRequestPx(tileEdgePx: Int, pixelWidth: Int? = null, pixelHeight: Int? = null): Int =
    if (tileEdgePx <= largeFillsTilePx(pixelWidth, pixelHeight)) GetStoredThumbnail.LARGE_PX
    else GetStoredThumbnail.XLARGE_PX

/** The widest square tile the `LARGE` rung fills without stretching: its short edge for this shape. */
internal fun largeFillsTilePx(pixelWidth: Int?, pixelHeight: Int?): Int {
    val w = pixelWidth?.takeIf { it > 0 }
    val h = pixelHeight?.takeIf { it > 0 }
    if (w == null || h == null) return GetStoredThumbnail.LARGE_PX * 3 / 4
    return (GetStoredThumbnail.LARGE_PX.toLong() * minOf(w, h) / maxOf(w, h)).toInt()
}
