package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoDetails
import app.tenshu.photos.ui.screens.viewer.PhotoViewerScreen
import app.tenshu.photos.ui.screens.viewer.PhotoViewerUiState
import app.tenshu.ui.theme.TenshuTheme
import org.tenshu.core.file.MediaMetadata
import kotlin.test.Test
import kotlinx.coroutines.flow.emptyFlow

/**
 * What the viewer's info sheet renders (`photos-app.md` S3 / PH-7).
 *
 * ⚠⚠ The load-bearing test is the empty one. Most files in an existing realm predate file-service
 * v8 and carry no metadata at all, so "the sheet with nothing to say" is the *common* case, not the
 * edge — and it must read as a short panel rather than a broken one.
 */
@OptIn(ExperimentalTestApi::class)
class PhotoViewerInfoSheetTest {

    private val path = "/Backup/Phone/Camera/IMG_0421.jpg"

    // ⚠ The sheet's photo is the one the pager is on, so the list is a single row here — this file
    // is about what the panel says, and `PhotoViewerPagerTest` is about which photo it says it of.
    private fun photo(at: String) = Photo(path = at, capturedAtMs = 0L, isVideo = false)

    private fun screen(state: PhotoViewerUiState) = @androidx.compose.runtime.Composable {
        TenshuTheme {
            PhotoViewerScreen(
                photos = listOf(state.photo),
                initialIndex = 0,
                state = state,
                loadHero = { _, _, _ -> emptyFlow() },
                onBack = {},
                onToggleInfo = {},
            )
        }
    }

    @Test
    fun theSheetIsClosedUntilItIsAskedFor() = runComposeUiTest {
        // ⚠⚠ **`assertIsNotDisplayed`, not `assertDoesNotExist`, and the change is the point of
        // §11.** The panel is no longer an overlay that is absent until summoned — it is the next
        // thing down the page's own scrolling column, so it is composed all along and simply below
        // the fold. Asserting absence would now be asserting an implementation detail that is
        // deliberately no longer true; asserting it is not *displayed* is what the reader of this
        // screen actually experiences, and it holds under either architecture.
        setContent(screen(PhotoViewerUiState(photo = photo(path))))
        waitForIdle()

        // ⚠ `useUnmergedTree`: with the merged tree `onNodeWithText` resolves to an ANCESTOR
        // whose bounds are the whole screen, so `assertIsNotDisplayed` asks "is this page on
        // screen" (yes) rather than "is this row on screen" (no) — and passes for the wrong
        // reason in one direction or fails for the wrong reason in the other.
        onNodeWithText("Path", useUnmergedTree = true).assertIsNotDisplayed()
    }

    @Test
    fun anOpenSheetShowsWhatTheFileSaid() = runComposeUiTest {
        setContent(
            screen(
                PhotoViewerUiState(
                    photo = photo(path),
                    infoOpen = true,
                    details = PhotoDetails(
                        path = path,
                        capturedAtMs = 1_700_000_000_000L,
                        sizeBytes = 2_400_000,
                        media = MediaMetadata(
                            pixelWidth = 4032, pixelHeight = 3024, orientation = 6,
                            cameraMake = "Apple", cameraModel = "iPhone 15 Pro",
                        ),
                    ),
                )
            )
        )

        // ⚠ 3024 × 4032, not 4032 × 3024: orientation 6 means the stored pixels are a quarter turn
        // from how they are shown, and the sheet reports what the user is looking at.
        waitUntil { onAllNodesWithText("3024 × 4032  (12.2 MP)").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Apple iPhone 15 Pro").assertExists()
        onNodeWithText("2.4 MB").assertExists()
    }

    @Test
    fun aFileThatSaidNothingShowsAShortSheetRatherThanUnknowns() = runComposeUiTest {
        setContent(
            screen(
                PhotoViewerUiState(
                    photo = photo(path),
                    infoOpen = true,
                    details = PhotoDetails(path = path, capturedAtMs = null, sizeBytes = null),
                )
            )
        )

        waitUntil { onAllNodesWithText("Path").fetchSemanticsNodes().isNotEmpty() }
        // ⚠⚠ Absent prints nothing. A row reading "Camera: Unknown" would assert something the
        // file does not know, and every pre-v8 upload is in exactly this state.
        onNodeWithText("Dimensions").assertDoesNotExist()
        onNodeWithText("Camera").assertDoesNotExist()
        onNodeWithText("Size").assertDoesNotExist()
        onNodeWithText("Length").assertDoesNotExist()
    }

    @Test
    fun theSheetOpensBeforeTheReadLands() = runComposeUiTest {
        // Opening the panel must not feel like loading a screen: the path is known without any
        // read, so it is shown immediately and the rest fills in.
        setContent(screen(PhotoViewerUiState(photo = photo(path), infoOpen = true)))

        waitUntil { onAllNodesWithText("IMG_0421.jpg").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Path").assertExists()
    }

    @Test
    fun aVideoShowsItsLength() = runComposeUiTest {
        setContent(
            screen(
                PhotoViewerUiState(
                    photo = photo("/Backup/Phone/Camera/VID_0007.mp4"),
                    infoOpen = true,
                    details = PhotoDetails(
                        path = "/Backup/Phone/Camera/VID_0007.mp4",
                        capturedAtMs = null,
                        sizeBytes = null,
                        media = MediaMetadata(pixelWidth = 1920, pixelHeight = 1080, durationMs = 83_000),
                    ),
                )
            )
        )

        waitUntil { onAllNodesWithText("1:23").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("1920 × 1080  (2.1 MP)").assertExists()
    }
}
