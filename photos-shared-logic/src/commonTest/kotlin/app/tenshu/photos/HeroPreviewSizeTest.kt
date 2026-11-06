package app.tenshu.photos

import app.tenshu.photos.data.HERO_BUCKET_PX
import app.tenshu.photos.data.MAX_HERO_PX
import app.tenshu.photos.data.heroPreviewRequestPx
import org.tenshu.core.file.GetStoredThumbnail
import kotlin.test.Test
import kotlin.test.assertEquals

/** The viewer's request size (`photos-app.md` §34). */
class HeroPreviewSizeTest {

    @Test
    fun aRetinaWindowAsksForTheEdgeThePhotographIsDrawnAt() {
        // The reported panel, maximised: a 4:3 landscape photograph is height-limited at 2234 px,
        // so drawn 2979 px wide — stepped up to 3072. A constant 1024 was a ~3× upscale of it.
        assertEquals(3072, heroPreviewRequestPx(3456, 2234, pixelWidth = 4000, pixelHeight = 3000))
        // ⚠ A portrait one is drawn 2234 px tall — but the row carries no orientation, so the stored
        // shape could be a rotated landscape frame drawn 2979 px wide, and the larger fit is asked.
        assertEquals(3072, heroPreviewRequestPx(3456, 2234, pixelWidth = 3000, pixelHeight = 4000))
    }

    /**
     * ⚠⚠ Stored dimensions are pre-EXIF-orientation. A stored landscape frame shown as portrait in
     * a portrait viewport is drawn 1920 px tall; fitting it as stored gives 1440 and asks for 1536,
     * leaving the viewer to upscale.
     */
    @Test
    fun aRotatedPhotographIsNeverAskedForSmallerThanItIsDrawn() {
        assertEquals(2048, heroPreviewRequestPx(1440, 3200, pixelWidth = 4032, pixelHeight = 3024))
        assertEquals(2048, heroPreviewRequestPx(1440, 3200, pixelWidth = 3024, pixelHeight = 4032))
    }

    @Test
    fun anUnknownShapeAsksForTheViewportsLongEdge() {
        assertEquals(3584, heroPreviewRequestPx(3456, 2234))
    }

    @Test
    fun neverBelowTheHeroRung_neverAboveThePhotograph_neverAboveTheCap() {
        // A phone: a portrait photograph fitted into 1080 × 2400 is 1440 px tall.
        assertEquals(1536, heroPreviewRequestPx(1080, 2400, pixelWidth = 3000, pixelHeight = 4000))
        // A small window must not swap the picture for the `LARGE` rung.
        assertEquals(GetStoredThumbnail.XLARGE_PX, heroPreviewRequestPx(400, 300, pixelWidth = 4000, pixelHeight = 3000))
        // A render cannot add pixels: an 1100 px photograph on a large panel asks for its own size.
        assertEquals(1536, heroPreviewRequestPx(3456, 2234, pixelWidth = 1100, pixelHeight = 800))
        assertEquals(MAX_HERO_PX, heroPreviewRequestPx(7680, 4320, pixelWidth = 12000, pixelHeight = 9000))
        assertEquals(GetStoredThumbnail.XLARGE_PX, heroPreviewRequestPx(0, 0))
    }

    @Test
    fun aWindowDragAsksAtSteps_notAtEveryPixel() {
        val sizes = (1_100..2_048).map { heroPreviewRequestPx(it, it) }.toSet()
        assertEquals(setOf(1_536, 2_048), sizes)
        sizes.forEach { assertEquals(0, it % HERO_BUCKET_PX) }
    }
}
