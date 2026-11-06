package app.tenshu.drive.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.ui.theme.TenshuTheme
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.Test

/**
 * Guards the preview-reactivity bug: the row used to load its thumbnail once and
 * cache the result (`produceState(key1 = item.path)`), so a preview that became
 * resolvable *after* the row's first emit (it materialises over the realm / once
 * the file's on-demand bytes arrive) never showed until the user switched screens
 * and back. With `produceState` also keyed on `item.previewBytes`, a model
 * re-emitted with now-available preview bytes (the `getFilesPaged` projection
 * delivers them on the reactive stream; the UI decodes them) swaps the category
 * icon for the image in place.
 */
class HorizontalFileItemPreviewReactivityTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun projectedPreviewArrivingLaterSwapsIconForImage() = runComposeUiTest {
        var item by mutableStateOf(
            FileUiModel(
                path = "/photo.jpg",
                name = "photo.jpg",
                subLine = "",
                previewIcon = FileIcon.Image,
                previewBytes = null,
            )
        )

        setContent {
            TenshuTheme {
                HorizontalFileItem(
                    item = item,
                    onItemClicked = {},
                    onItemLongClicked = {},
                    onToggleSelection = {},
                    onMoreIconClicked = {},
                )
            }
        }

        // Before the preview lands: category icon, no image.
        onNodeWithContentDescription("File icon").assertExists()
        onNodeWithContentDescription("File preview").assertDoesNotExist()

        // The projection re-emits the SAME path with the preview bytes. The old
        // path-keyed one-shot loader would not re-run here — keying on
        // `previewBytes` is what makes the swap reactive. The UI decodes the
        // bytes (off-thread), so wait for the swap rather than asserting eagerly.
        item = item.copy(previewBytes = onePixelPng())

        waitUntil {
            onAllNodesWithContentDescription("File preview").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("File preview").assertIsDisplayed()
        onNodeWithContentDescription("File icon").assertDoesNotExist()
    }

    /** A minimal valid PNG so `decodeToImageBitmap` (Skia) returns a bitmap. */
    private fun onePixelPng(): ByteArray =
        Surface.makeRasterN32Premul(1, 1)
            .makeImageSnapshot()
            .encodeToData(EncodedImageFormat.PNG)!!
            .bytes
}
