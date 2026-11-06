package app.tenshu.photos

import app.tenshu.photos.data.photosWantedThumbnailClasses
import org.tenshu.core.file.GetStoredThumbnail
import kotlin.test.Test
import kotlin.test.assertEquals

/** The rungs Photos asks every device to backfill — one list for every shell since PIO-0. */
class WantedThumbnailClassesTest {

    private val heroes = setOf(GetStoredThumbnail.CLASS_XLARGE, GetStoredThumbnail.CLASS_XXLARGE)

    @Test
    fun the_viewer_rungs_are_always_declared() {
        // ⚠⚠ A warm phone never misses its preview cache, so the pull path never declares these
        // for it — without this line the hero rung is never backfilled where it is drawn daily.
        assertEquals(heroes, photosWantedThumbnailClasses(playVideosInGrid = false, motionPlaybackSupported = true))
        assertEquals(heroes, photosWantedThumbnailClasses(playVideosInGrid = false, motionPlaybackSupported = false))
    }

    @Test
    fun motion_needs_both_the_setting_and_a_player() {
        assertEquals(
            heroes + GetStoredThumbnail.CLASS_MOTION,
            photosWantedThumbnailClasses(playVideosInGrid = true, motionPlaybackSupported = true),
        )
        // A device that cannot play a clip must not make the realm mint one for it.
        assertEquals(heroes, photosWantedThumbnailClasses(playVideosInGrid = true, motionPlaybackSupported = false))
    }
}
