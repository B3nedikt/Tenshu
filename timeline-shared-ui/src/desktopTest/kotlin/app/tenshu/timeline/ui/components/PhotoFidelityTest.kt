package app.tenshu.timeline.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.tenshu.timeline.model.Photo
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which photo surfaces may spend the hero rung, and what size they ask for.
 *
 * ⚠⚠ **Both directions matter.** A hero that stopped spending it would silently go back to being a
 * 3-5× upscale of the 256 px `LARGE` class — the defect this work exists to fix — and a feed row
 * that *started* spending it would pull ~139 KB per row a scroll passes, which is the trap
 * `timeline-photos.md` §5 warns about. Neither shows up as a failure anywhere else: both render a
 * photograph, and only the bandwidth differs.
 */
@OptIn(ExperimentalTestApi::class)
class PhotoFidelityTest {

    private val photo = Photo(dominantColor = "#123456", uri = "/Backup/Phone/Camera/IMG_1.jpg")

    /** Records which loader ran and at what size. Both return `null` — the call is the assertion. */
    private class Recorder {
        var cached: Pair<Int, Int>? = null
        var hero: Pair<Int, Int>? = null

        val loaders = PhotoByteLoaders(
            cached = { _, w, h -> cached = w to h; null },
            hero = { _, w, h -> hero = w to h; null },
        )
    }

    private fun record(content: @androidx.compose.runtime.Composable () -> Unit): Recorder {
        val recorder = Recorder()
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent {
                TenshuTheme {
                    CompositionLocalProvider(LocalPhotoBytes provides recorder.loaders) {
                        content()
                    }
                }
            }
            waitForIdle()
        }
        return recorder
    }

    @Test
    fun aFeedFrameReadsOnlyWhatThisDeviceAlreadyHas() {
        val recorder = record {
            PhotoFrame(photo, Modifier.size(width = 300.dp, height = 200.dp))
        }

        assertNotNull(recorder.cached, "a feed frame still resolves its own photo")
        assertNull(
            recorder.hero,
            "a feed frame must never pull the hero rung — a day's scroll is dozens of photos",
        )
    }

    @Test
    fun aDetailHeroSpendsTheHeroRung() {
        val recorder = record { DetailHero(photo) }

        assertNotNull(
            recorder.hero,
            "a hero is one photo on a deliberately-opened screen — the one surface allowed to spend it",
        )
        assertNull(recorder.cached, "and it must not also pay for the cached read")
    }

    @Test
    fun aHeroGivenAnAlreadyDecodedImageReadsNothing() {
        // The moment detail decodes its own bytes (it needs the tint), and passes the bitmap in.
        // Resolving a second copy here would double every hero's cost on the one screen that
        // already paid.
        val recorder = record {
            DetailHero(photo, image = androidx.compose.ui.graphics.ImageBitmap(4, 4))
        }

        assertNull(recorder.cached)
        assertNull(recorder.hero)
    }

    @Test
    fun theRequestIsASquareThatCoversTheFrame() {
        val recorder = record {
            PhotoFrame(photo, Modifier.size(width = 300.dp, height = 200.dp))
        }
        val (width, height) = assertNotNull(recorder.cached)

        // ⚠ Square of the LONGEST edge, not the frame's shape: the frame crops while `GetPreview`
        // fits, so a (300, 200) request would return a 3:2 photo fitted to 300×200 — and a portrait
        // one to 133×200, which the crop then triples. See `previewRequestPx`.
        assertEquals(width, height, "the request must be square")
        assertTrue(width >= 300, "and at least the frame's longest edge, got $width")
        assertEquals(0, width % PREVIEW_BUCKET_PX, "and bucketed, got $width")
    }

    @Test
    fun requestSizesAreBucketedAndClamped() {
        // Bucketing is what stops a desktop window drag from re-requesting on every frame and
        // filling the proxy's size-keyed PreviewCache with one entry per pixel width.
        assertEquals(PREVIEW_BUCKET_PX, previewRequestPx(1), "below a bucket, ask for the floor")
        assertEquals(PREVIEW_BUCKET_PX, previewRequestPx(PREVIEW_BUCKET_PX))
        assertEquals(512, previewRequestPx(PREVIEW_BUCKET_PX + 1), "round up, never down")
        assertEquals(512, previewRequestPx(512))
        assertEquals(768, previewRequestPx(700))
        assertEquals(MAX_PREVIEW_PX, previewRequestPx(MAX_PREVIEW_PX + 5000), "clamped at the top")
    }
}
