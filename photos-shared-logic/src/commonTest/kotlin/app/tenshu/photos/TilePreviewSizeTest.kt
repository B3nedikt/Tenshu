package app.tenshu.photos

import app.tenshu.photos.data.tilePreviewRequestPx
import org.tenshu.core.file.GetStoredThumbnail
import kotlin.test.Test
import kotlin.test.assertEquals

class TilePreviewSizeTest {

    private val large = GetStoredThumbnail.LARGE_PX
    private val hero = GetStoredThumbnail.XLARGE_PX

    @Test
    fun aTileTheLargeRungFillsAsksForTheLargeRung() {
        // 7 columns across a 1080 px phone, unknown shape (4:3 assumed).
        assertEquals(large, tilePreviewRequestPx(150))
        // The boundary itself: 256×192 still covers a 192 px square.
        assertEquals(large, tilePreviewRequestPx(192))
    }

    @Test
    fun aTileWiderThanTheLargeRungsShortEdgeAsksForTheHeroRung() {
        assertEquals(hero, tilePreviewRequestPx(193))
        // ⚠ The case the flat 256 px request got wrong: the default 3-across grid on a 3x phone.
        assertEquals(hero, tilePreviewRequestPx(410))
    }

    @Test
    fun theShortEdgeComesFromThePhotosOwnShape() {
        // A square photo's LARGE is 256×256: it fills a 240 px tile, which 4:3 would not.
        assertEquals(large, tilePreviewRequestPx(240, pixelWidth = 3000, pixelHeight = 3000))
        // A 16:9 video's LARGE is 256×144: a 160 px tile already needs the hero rung.
        assertEquals(hero, tilePreviewRequestPx(160, pixelWidth = 1920, pixelHeight = 1080))
        // A portrait screenshot, stored portrait or not, is the same shape either way round.
        assertEquals(
            tilePreviewRequestPx(140, pixelWidth = 1080, pixelHeight = 2340),
            tilePreviewRequestPx(140, pixelWidth = 2340, pixelHeight = 1080),
        )
        assertEquals(hero, tilePreviewRequestPx(140, pixelWidth = 1080, pixelHeight = 2340))
    }

    @Test
    fun anUnreadableShapeFallsBackToFourByThree() {
        assertEquals(large, tilePreviewRequestPx(192, pixelWidth = 0, pixelHeight = 1080))
        assertEquals(hero, tilePreviewRequestPx(193, pixelWidth = null, pixelHeight = 1080))
    }

    @Test
    fun anUnmeasuredTileAsksForTheSmallRung() {
        // A caller must not load before layout, but a zero must still not reach for the hero rung.
        assertEquals(large, tilePreviewRequestPx(0))
    }
}
