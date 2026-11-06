package app.tenshu.photos.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.viewer.PhotoViewerScreen
import app.tenshu.photos.ui.screens.viewer.PhotoViewerUiState
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.emptyFlow

/**
 * The viewer's keyboard (`photos-app.md` §33): arrows page, Esc closes, delete trashes. ⚠ Every
 * test presses a key with no prior click, which is what a user does the moment the viewer opens.
 */
@OptIn(ExperimentalTestApi::class)
class PhotoViewerKeyboardTest {

    private fun photoAt(i: Int) = Photo(path = "/p$i.jpg", capturedAtMs = 1_000L - i, isVideo = false)

    private val photos = List(5) { photoAt(it) }

    @Test
    fun theArrowKeysPageThroughThePhotographs() = runComposeUiTest {
        val shown = mutableListOf<String>()
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = photos,
                    initialIndex = 2,
                    state = PhotoViewerUiState(photo = photoAt(2)),
                    loadHero = { _, _, _ -> emptyFlow() },
                    onBack = {},
                    onPhotoShown = { shown += it.path },
                )
            }
        }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()

        assertEquals(listOf("/p2.jpg", "/p3.jpg", "/p2.jpg", "/p1.jpg"), shown)
    }

    @Test
    fun escapeClosesTheViewer_andBothDeleteKeysTrashThePhotographOnScreen() = runComposeUiTest {
        var closed = 0
        val trashed = mutableListOf<String>()
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = photos,
                    initialIndex = 1,
                    state = PhotoViewerUiState(photo = photoAt(1)),
                    loadHero = { _, _, _ -> emptyFlow() },
                    onBack = { closed++ },
                    onTrash = { trashed += it.path },
                )
            }
        }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Backspace) }
        onRoot().performKeyInput { pressKey(Key.Delete) }
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertEquals(listOf("/p1.jpg", "/p1.jpg"), trashed, "a Mac's delete key sends Backspace")
        assertEquals(1, closed)
    }
}
