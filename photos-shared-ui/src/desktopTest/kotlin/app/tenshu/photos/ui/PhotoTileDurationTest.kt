package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.components.PHOTO_TILE_DURATION_TAG
import app.tenshu.photos.ui.components.PhotoTile
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test

/**
 * The tile's duration badge (`photos-video-support.md` M1).
 *
 * ⚠⚠ **The two absent cases are the point.** A badge test that only asserts the positive case
 * passes just as well on a tile that draws the badge unconditionally, and the unknown-duration
 * video is precisely the row that must show *no* badge rather than `0:00` — a distinction no
 * positive assertion can make.
 *
 * ⚠ What this cannot see: whether the scrim actually makes the glyph legible over a bright
 * photograph. That is the reason the decoration changed, and it is a looking-at-it question — the
 * on-device pass is what checks it, not this.
 *
 * ⚠ `useUnmergedTree` throughout, and not optional: the tile's `combinedClickable` merges its
 * descendants into one node, exactly as `PhotoTile`'s own KDoc warns, so the badge is unreachable
 * in the merged tree. `PhotosFeedScreenTest` reaches the selection mark the same way.
 */
class PhotoTileDurationTest {

    private val video = Photo(
        path = "/Backup/Phone/Camera/VID_1001.mp4",
        capturedAtMs = 1_700_000_000_000L,
        isVideo = true,
        durationMs = 83_000L,
        contentId = "c1",
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a video with a known duration shows it`() = runDesktopComposeUiTest {
        setContent {
            TenshuTheme {
                PhotoTile(photo = video, loadPreview = { _, _ -> null }, onClick = {})
            }
        }

        onNodeWithTag(PHOTO_TILE_DURATION_TAG, useUnmergedTree = true).assertTextEquals("1:23")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a still shows no badge`() = runDesktopComposeUiTest {
        setContent {
            TenshuTheme {
                PhotoTile(
                    photo = video.copy(isVideo = false, durationMs = null),
                    loadPreview = { _, _ -> null },
                    onClick = {},
                )
            }
        }

        onNodeWithTag(PHOTO_TILE_DURATION_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * ⚠ The regression this pins: a video uploaded before the duration was stamped must keep the
     * bare play glyph. Rendering `0:00` would state a fact about the file that nobody knows.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a video with an unknown duration shows no badge`() = runDesktopComposeUiTest {
        setContent {
            TenshuTheme {
                PhotoTile(photo = video.copy(durationMs = null), loadPreview = { _, _ -> null }, onClick = {})
            }
        }

        onNodeWithTag(PHOTO_TILE_DURATION_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an hour-long video keeps the hours field`() = runDesktopComposeUiTest {
        setContent {
            TenshuTheme {
                PhotoTile(
                    photo = video.copy(durationMs = 3_723_000L),
                    loadPreview = { _, _ -> null },
                    onClick = {},
                )
            }
        }

        onNodeWithTag(PHOTO_TILE_DURATION_TAG, useUnmergedTree = true).assertTextEquals("1:02:03")
    }
}
