package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.photos.model.MotionTurn
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.components.MOTION_FAILED_TURN_MS
import app.tenshu.photos.ui.components.MOTION_TILE_PLAYER_TAG
import app.tenshu.photos.ui.components.PhotoTile
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tile's half of M5 (`photos-video-support.md` §5.2): the turn is a **keyed input**, and the
 * tile draws a player only while it holds one.
 *
 * ⚠ What this can and cannot see. On the JVM that runs `desktopTest` there is no clip player — the
 * macOS bridge needs a JDK the test toolchain does not have, and Linux has nothing — so the
 * desktop actual takes its "could not open" path. That is the branch worth pinning here anyway:
 * it must still hand over, after a nominal turn, or one bad platform would stall every video on
 * screen. Whether frames actually move is the device pass's business.
 *
 * ⚠ `useUnmergedTree`: the tile's `combinedClickable` merges its descendants, as `PhotoTile`'s own
 * KDoc warns.
 */
@OptIn(ExperimentalTestApi::class)
class PhotoTileMotionTest {

    private val video = Photo(
        path = "/Camera/VID_1001.mp4",
        capturedAtMs = 1_700_000_000_000L,
        isVideo = true,
        durationMs = 6_000L,
        contentId = "c1",
    )

    private val clip = ByteArray(2_048) { 7 }

    @Test
    fun `a tile holding the turn draws the player`() = runDesktopComposeUiTest {
        setContent {
            TenshuTheme {
                PhotoTile(
                    photo = video,
                    loadPreview = { _, _ -> null },
                    onClick = {},
                    motion = MotionTurn(path = video.path, clip = clip, serial = 1),
                )
            }
        }
        onNodeWithTag(MOTION_TILE_PLAYER_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a tile without the turn draws no player`() = runDesktopComposeUiTest {
        setContent {
            TenshuTheme {
                PhotoTile(photo = video, loadPreview = { _, _ -> null }, onClick = {})
            }
        }
        onNodeWithTag(MOTION_TILE_PLAYER_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * ⚠⚠ The contract that keeps a rotation alive on a platform with no player: a turn that cannot
     * be played still ends — once, and not at once. Reporting immediately would spin the rotation
     * through every visible video as fast as it can recompose; never reporting would stall it on
     * the first one.
     */
    @Test
    fun `a turn that cannot be played still hands over after a nominal turn`() = runDesktopComposeUiTest {
        mainClock.autoAdvance = false
        var finished = 0
        setContent {
            TenshuTheme {
                PhotoTile(
                    photo = video,
                    loadPreview = { _, _ -> null },
                    onClick = {},
                    motion = MotionTurn(path = video.path, clip = clip, serial = 1),
                    onMotionFinished = { finished++ },
                )
            }
        }
        mainClock.advanceTimeBy(MOTION_FAILED_TURN_MS / 2)
        assertEquals(0, finished, "not at once")
        mainClock.advanceTimeBy(MOTION_FAILED_TURN_MS)
        assertEquals(1, finished, "once, after the nominal turn")
        mainClock.advanceTimeBy(MOTION_FAILED_TURN_MS * 2)
        assertEquals(1, finished, "and never again for the same turn")
    }
}
