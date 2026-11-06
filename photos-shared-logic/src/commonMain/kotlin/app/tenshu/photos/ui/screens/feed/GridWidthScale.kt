package app.tenshu.photos.ui.screens.feed

import app.tenshu.photos.data.TenshuPhotosRepository
import kotlin.math.ceil

/**
 * How many times its phone column count a photo grid [widthDp] wide draws (`photos-app.md` §33).
 *
 * ⚠⚠ **Width, not window, and measured where the grid is.** The grid's own constraints already
 * exclude the navigation rail, a split-screen neighbour and a freeform window's frame, so the same
 * rule serves a phone, a tablet, split screen and a desktop window without knowing which it is on.
 *
 * ⚠⚠ **A multiple of the base count, never an arbitrary one.** The mosaic's feature draws one
 * photograph over 2×2 cells beside two stacked ones, and it has to consume exactly the photographs a
 * row would or a page landing moves every line below it (`FeedLayout`). `k` features across a line
 * take `3k` photographs over `2 × 3k` cells only when the line is `3k` wide. The other densities scale
 * by the same factor, so zooming out keeps the ratio it has on a phone.
 *
 * ⚠⚠ **And a count the page size divides** ([pageSize]). The loaded window starts a fresh line, so
 * a page landing above it re-cuts every line it drew unless the page is whole lines — the premise
 * the three-column mosaic already rested on. Nine columns against 60-photograph pages re-phase the
 * grid on every page above a scrubber jump, so a width that needs `k = 3` gets the next scale that
 * divides: 4. ⚠ Aligned against the density's OWN count ([baseColumns]): Month at 25 columns fails
 * the same way otherwise. Where no scale up to [MAX_SCALE] fits, the largest one that does is used;
 * the 7-column year view divides 60 at no scale, so it keeps the width's own `k` and re-phases.
 *
 * ⚠ Why it exists: a fixed three columns in a 1600 dp window is a 530 dp tile, over 1000 px on a
 * 2x panel, and a 2×2 feature twice that. No stored thumbnail class is that large, so every tile
 * was an upscale that grew with the window (`drive-synced-thumbnails.md` §17). Capping the tile at
 * [MAX_BASE_TILE_DP] keeps a feature within the 1024 px hero rung on a 2x display — up to
 * [MAX_SCALE] times the base count, past which tiles are allowed to grow again.
 */
fun gridWidthScale(
    widthDp: Float,
    baseColumns: Int = BASE_COLUMNS,
    pageSize: Int = TenshuPhotosRepository.DEFAULT_PAGE_SIZE,
): Int {
    // ⚠ The need is measured against the three-column tile cap at every density, so all of them want
    // the same factor and zooming out keeps its phone ratio wherever alignment allows.
    val needed = if (widthDp <= 0f) 1 else minOf(MAX_SCALE, maxOf(1, ceil(widthDp / (BASE_COLUMNS * MAX_BASE_TILE_DP)).toInt()))
    val aligned = (1..MAX_SCALE).filter { pageSize % (baseColumns * it) == 0 }
    if (aligned.isEmpty()) return needed
    return aligned.firstOrNull { it >= needed } ?: aligned.last()
}

/**
 * A collection grid's column count (an album, Favourites, a place…) for a grid [widthDp] wide — three
 * on a phone, more on a wider grid. Compose's `WidthScaledGridCells(3)` and the iOS `PhotoGrid` both
 * ask this. ⚠ A function rather than the call, because Kotlin's default arguments do not cross to Swift.
 */
fun collectionGridColumns(widthDp: Float): Int = BASE_COLUMNS * gridWidthScale(widthDp)

/** The feed's column count at this density for a grid [widthDp] wide. See [gridWidthScale]. */
fun FeedDensity.columnsAt(widthDp: Float): Int = columns * gridWidthScale(widthDp, baseColumns = columns)

/**
 * The widest a tile gets at the three-column densities before the grid adds columns. ⚠ 240 dp is
 * 480 px on a 2x panel and a 2×2 feature ~960 px — inside the 1024 px hero rung. A phone (~400 dp)
 * never reaches it, so a phone keeps exactly the grid it had.
 */
const val MAX_BASE_TILE_DP: Float = 240f

/** The most a grid multiplies its base count by: 15 columns at the three-column densities. */
const val MAX_SCALE: Int = 5

private const val BASE_COLUMNS = 3
