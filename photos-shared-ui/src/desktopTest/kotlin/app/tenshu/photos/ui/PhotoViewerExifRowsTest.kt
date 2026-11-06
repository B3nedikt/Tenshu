package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.photos.data.ExifReadResult
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.viewer.PhotoViewerScreen
import app.tenshu.photos.ui.screens.viewer.PhotoViewerUiState
import app.tenshu.ui.theme.TenshuTheme
import org.tenshu.core.file.MediaMetadata
import kotlin.test.Test
import kotlinx.coroutines.flow.emptyFlow

/**
 * What the info sheet says about how a photograph was taken (PH-11b).
 *
 * ⚠⚠ Three of these are about **absence**, because absence is the common case: most files in a real
 * realm are not on the device you are holding, and a panel that silently shows nothing for them
 * reads as a parser that does not work.
 */
@OptIn(ExperimentalTestApi::class)
class PhotoViewerExifRowsTest {

    private val path = "/Backup/Phone/Camera/IMG_0011.jpeg"

    private fun screen(exif: ExifReadResult?) = @androidx.compose.runtime.Composable {
        TenshuTheme {
            val photo = Photo(path, 0L, isVideo = false)
            PhotoViewerScreen(
                photos = listOf(photo),
                initialIndex = 0,
                state = PhotoViewerUiState(photo = photo, infoOpen = true, exif = exif),
                loadHero = { _, _, _ -> emptyFlow() },
                onBack = {},
                onToggleInfo = {},
            )
        }
    }

    @Test
    fun theSettingsRenderWhenTheyWereRead() = runComposeUiTest {
        setContent(
            screen(
                ExifReadResult.Read(
                    MediaMetadata(
                        exposureTimeSeconds = 0.004,
                        fNumber = 1.8,
                        isoSpeed = 64,
                        focalLengthMm = 6.86,
                        focalLength35mm = 24,
                        lensModel = "iPhone 15 Pro back camera",
                        exposureBiasEv = 0.0,
                    )
                )
            )
        )

        waitUntil { onAllNodesWithText("1/250 s").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("f/1.8").assertExists()
        onNodeWithText("64").assertExists()
        onNodeWithText("6.9 mm (24 mm equivalent)").assertExists()
        onNodeWithText("iPhone 15 Pro back camera").assertExists()
        // ⚠ 0 EV is rendered, not suppressed — the default reading must not look like absence.
        onNodeWithText("0 EV").assertExists()
    }

    @Test
    fun aPhotoWhoseBytesAreElsewhereSaysSo() = runComposeUiTest {
        // ⚠⚠ The state PH-11b's gate exists to produce. It is a fact about *this device*, not a
        // failure, and hiding it would make the panel look broken for most of a real library.
        setContent(screen(ExifReadResult.NotOnDevice))

        waitUntil {
            onAllNodesWithText("Camera settings need the original, which isn't on this device.")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun anUnreadSheetShowsNoSettingsAndNoExcuse() = runComposeUiTest {
        // ⚠ `null` is "still reading". It must not render the not-on-device line, which would flash
        // a claim that is not yet known to be true.
        setContent(screen(null))

        waitUntil { onAllNodesWithText("Path").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Camera settings need the original, which isn't on this device.")
            .assertDoesNotExist()
        onNodeWithText("Exposure").assertDoesNotExist()
    }

    @Test
    fun anUnreachableProxySaysNothingHereRatherThanTwice() = runComposeUiTest {
        // ⚠⚠ The shell's banner already says the proxy is down. Saying it again in a metadata panel,
        // in different words, reads to a user as two separate problems.
        setContent(screen(ExifReadResult.Unreachable))

        waitUntil { onAllNodesWithText("Path").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Camera settings need the original, which isn't on this device.")
            .assertDoesNotExist()
        onNodeWithText("Exposure").assertDoesNotExist()
    }

    @Test
    fun aStrippedPhotographAddsNoRowsAtAll() = runComposeUiTest {
        // ⚠ `Read` with nothing in it is the messaging-app case. It must add no heading and no
        // blank rows — the same "omit rather than print Unknown" rule PH-7's sheet follows.
        setContent(screen(ExifReadResult.Read(MediaMetadata())))

        waitUntil { onAllNodesWithText("Path").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Exposure").assertDoesNotExist()
        onNodeWithText("ISO").assertDoesNotExist()
    }
}
