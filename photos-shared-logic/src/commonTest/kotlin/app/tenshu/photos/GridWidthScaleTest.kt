package app.tenshu.photos

import app.tenshu.photos.data.TenshuPhotosRepository
import app.tenshu.photos.ui.screens.feed.FeedDensity
import app.tenshu.photos.ui.screens.feed.MAX_BASE_TILE_DP
import app.tenshu.photos.ui.screens.feed.MAX_SCALE
import app.tenshu.photos.ui.screens.feed.columnsAt
import app.tenshu.photos.ui.screens.feed.gridWidthScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The grid's column rule (`photos-app.md` §33). */
class GridWidthScaleTest {

    @Test
    fun aPhoneKeepsTheGridItHad() {
        // ⚠⚠ The first thing the rule must not do is change the device it was designed on.
        for (width in listOf(320f, 360f, 411f, 480f)) {
            assertEquals(1, gridWidthScale(width), "at $width dp")
            assertEquals(3, FeedDensity.Mosaic.columnsAt(width))
            assertEquals(7, FeedDensity.Year.columnsAt(width))
        }
    }

    @Test
    fun aWiderGridMultipliesTheColumns_soNoTileOutgrowsItsCap() {
        assertEquals(1, gridWidthScale(3 * MAX_BASE_TILE_DP))
        assertEquals(2, gridWidthScale(3 * MAX_BASE_TILE_DP + 1f))

        val capped = 3 * MAX_BASE_TILE_DP * MAX_SCALE
        for (width in (200..capped.toInt() step 7).map { it.toFloat() }) {
            val tile = width / FeedDensity.Day.columnsAt(width)
            assertTrue(tile <= MAX_BASE_TILE_DP, "a $width dp grid drew $tile dp tiles")
        }
    }

    /**
     * ⚠⚠ Nine columns against 60-photograph pages re-cut every loaded line when the page above a
     * scrubber jump lands. The mosaic may only use a count the page size divides.
     */
    @Test
    fun theMosaicOnlyUsesColumnCountsThePageSizeDivides() {
        val page = TenshuPhotosRepository.DEFAULT_PAGE_SIZE
        val used = (200..6_000 step 13).map { FeedDensity.Mosaic.columnsAt(it.toFloat()) }.toSet()
        used.forEach {
            assertEquals(0, it % 3, "$it columns is not whole feature blocks")
            assertEquals(0, page % it, "$it columns does not divide the $page-photograph page")
        }
        assertTrue(9 !in used, "the width that needs nine columns gets twelve")
        assertEquals(12, FeedDensity.Mosaic.columnsAt(1_600f), "a maximised 1600 dp window")
    }

    /** The same for every density: Month at 25 columns on a grid past 2880 dp is the case to catch. */
    @Test
    fun everyDensityThatCanDivideThePageDoesAtEveryWidth() {
        val page = TenshuPhotosRepository.DEFAULT_PAGE_SIZE
        for (density in listOf(FeedDensity.Mosaic, FeedDensity.Day, FeedDensity.Month)) {
            (200..8_000 step 11).map { density.columnsAt(it.toFloat()) }.toSet().forEach {
                assertEquals(0, page % it, "$density at $it columns does not divide the $page-photograph page")
            }
        }
    }

    @Test
    fun anUnmeasuredGridIsAPhoneGrid() {
        assertEquals(1, gridWidthScale(0f))
        assertEquals(1, gridWidthScale(-1f))
    }
}
