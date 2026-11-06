package app.tenshu.drive.ui.screens.filedetails

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.tenshu.drive.data.FileActivity
import app.tenshu.drive.data.FileActivityKind
import app.tenshu.drive.data.FileDetailsModel
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.ui.FakeFilesRepository
import app.tenshu.ui.theme.TenshuTheme
import app.tenshu.drive.composeVmTest
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertTrue

class FileDetailsScreenTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rendersMetadataAndActivityFromTheRepository() = composeVmTest { vms ->
        val repo = FakeFilesRepository(
            fileDetailsResult = FileDetailsModel(
                file = FileModel.File(
                    path = "/photos/sunset.jpg",
                    name = "sunset.jpg",
                    subLine = "",
                    previewIcon = FileIcon.GenericFile,
                ),
                size = "1.2 MB",
                created = "Jan 1, 2024, 09:00",
                lastModified = "Mar 3, 2024, 14:25",
            ),
            fileActivityResult = listOf(
                FileActivity(FileActivityKind.Renamed, "old.jpg → sunset.jpg", 1_709_475_900_000, "d1", deviceName = "iPhone"),
                FileActivity(FileActivityKind.Created, null, 1_704_099_600_000, "d1"),
            ),
        )

        setContent {
            TenshuTheme {
                FileDetailsScreen(
                    viewModel = vms.own(FileDetailsViewModel("/photos/sunset.jpg", repo)),
                    onBack = {},
                )
            }
        }

        // The metadata stream resolves asynchronously into the VM state.
        waitUntil {
            onAllNodesWithText("1.2 MB").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithText("Details").assertIsDisplayed()
        onNodeWithText("1.2 MB").assertIsDisplayed()
        onNodeWithText("Jan 1, 2024, 09:00").assertIsDisplayed()
        onNodeWithText("Mar 3, 2024, 14:25").assertIsDisplayed()

        // Activity rows render per subtype.
        onNodeWithText("Activity").assertIsDisplayed()
        onNodeWithText("Renamed").assertIsDisplayed()
        onNodeWithText("old.jpg → sunset.jpg").assertIsDisplayed()
        // The resolved device name renders; an unresolved row falls back to the raw id.
        onNodeWithText("on iPhone").assertIsDisplayed()
        onNodeWithText("on d1").assertIsDisplayed()
        // "Created" appears twice: the metadata "Created" label + the activity row.
        assertTrue(
            onAllNodesWithText("Created").fetchSemanticsNodes().size >= 2,
            "the Created activity row renders alongside the Created metadata label",
        )

        assertTrue(
            onAllNodesWithText("sunset.jpg").fetchSemanticsNodes().isNotEmpty(),
            "file name should be rendered",
        )
    }

    /**
     * When the preview arrives, the header swaps the category icon for the
     * actual image (content description "File preview"); a `null` preview keeps
     * the icon. This is the user-visible behaviour: icon first, then the image.
     *
     * The bytes ride the details stream itself now — `File.Preview(480, 480)` is
     * a DEFERRED projection (`core-deferred-properties.md`): the row emits with
     * `previewBytes` null, then the stream patches it non-null. The screen no
     * longer issues a separate `getPreview` loader call (that mechanism is gone);
     * the header just reacts to the model's `previewBytes`. Modelled here by a
     * details result already carrying the (patched) bytes.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun headerShowsTheDecodedPreviewImage() = composeVmTest { vms ->
        val repo = FakeFilesRepository(
            fileDetailsResult = imageDetails(previewBytes = onePixelPng()),
        )

        setContent {
            TenshuTheme {
                FileDetailsScreen(
                    viewModel = vms.own(FileDetailsViewModel("/photos/sunset.jpg", repo)),
                    onBack = {},
                )
            }
        }

        waitUntil {
            onAllNodesWithContentDescription("File preview").fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithContentDescription("File preview")[0].assertIsDisplayed()
    }

    private fun imageDetails(previewBytes: ByteArray? = null) = FileDetailsModel(
        file = FileModel.File(
            path = "/photos/sunset.jpg",
            name = "sunset.jpg",
            subLine = "",
            previewIcon = FileIcon.GenericFile,
            previewBytes = previewBytes,
        ),
        size = "1.2 MB",
        created = "Jan 1, 2024, 09:00",
        lastModified = "Mar 3, 2024, 14:25",
    )

    /** A minimal valid PNG so `decodeToImageBitmap` (Skia) returns a bitmap. */
    private fun onePixelPng(): ByteArray =
        Surface.makeRasterN32Premul(1, 1)
            .makeImageSnapshot()
            .encodeToData(EncodedImageFormat.PNG)!!
            .bytes
}
