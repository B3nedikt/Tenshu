package app.tenshu.photos

import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.data.ExifReadResult
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoDetails
import app.tenshu.photos.model.PhotoPlace
import app.tenshu.photos.ui.screens.viewer.AboutRow
import app.tenshu.photos.ui.screens.viewer.AboutSettings
import app.tenshu.photos.ui.screens.viewer.AboutSheetContent
import app.tenshu.photos.ui.screens.viewer.PhotoViewerUiState
import org.tenshu.core.file.MediaMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the About sheet says, decided once for both shells. */
class AboutSheetContentTest {

    private val photo = Photo("/Backup/IMG_1.jpg", 1_000L, isVideo = false, contentId = "c1")

    private fun content(state: PhotoViewerUiState) = AboutSheetContent.of(state) { "taken $it" }

    @Test
    fun beforeAnyReadItStillSaysWhatItKnows_thePathAndNothingInvented() {
        val about = content(PhotoViewerUiState(photo = photo))

        assertEquals("taken 1000", about.title)
        assertEquals("IMG_1.jpg", about.fileName)
        assertEquals(listOf(AboutRow("Path", "/Backup/IMG_1.jpg")), about.details, "only the path — no empty rows")
        assertTrue(about.albums.isEmpty())
        assertNull(about.place)
        assertEquals(AboutSettings.Reading, about.settings, "reading, which shows nothing — not a spinner")
    }

    @Test
    fun theDetailsAreTheKnownOnesInOrder_andThePathIsAlwaysLast() {
        val details = PhotoDetails(
            path = photo.path, capturedAtMs = 1_000L, sizeBytes = 2_500_000L,
            media = MediaMetadata(pixelWidth = 4032, pixelHeight = 3024, cameraMake = "Apple", cameraModel = "iPhone 16 Pro"),
        )
        val about = content(PhotoViewerUiState(photo = photo, details = details))

        assertEquals(listOf("Dimensions", "Camera", "Size", "Path"), about.details.map { it.label })
        assertEquals(details.megapixels, about.details.first().suffix, "the megapixels ride on the dimensions")
    }

    @Test
    fun anAlbumOfOneIsAnItem_andManyAreItems() {
        val one = AlbumSummary("a", "Holiday", createdAtMs = 1, photoCount = 1, coverPath = null)
        val many = AlbumSummary("b", "Family", createdAtMs = 2, photoCount = 40, coverPath = null)
        val about = content(PhotoViewerUiState(photo = photo, albums = listOf(one, many)))

        assertEquals(listOf("Holiday  ·  1 item", "Family  ·  40 items"), about.albums.map { it.line })
    }

    @Test
    fun twoAlbumsWithTheSameNameAndCountStayTwoRows() {
        // ⚠ The words are the same, so only the id tells the rows apart.
        val a = AlbumSummary("a", "Trip", createdAtMs = 1, photoCount = 5, coverPath = null)
        val b = AlbumSummary("b", "Trip", createdAtMs = 2, photoCount = 5, coverPath = null)
        val albums = content(PhotoViewerUiState(photo = photo, albums = listOf(a, b))).albums

        assertEquals(albums[0].line, albums[1].line, "precondition: identical lines")
        assertEquals(listOf("a", "b"), albums.map { it.albumId }, "two distinct identities")
    }

    @Test
    fun aPlaceIsItsNameAndCity_withoutSayingTheSameThingTwice() {
        val named = PhotoPlace("p", "Eiffel Tower", "Paris", 0, 0)
        val cityOnly = PhotoPlace("q", "Paris", "Paris", 0, 0)
        assertEquals("Eiffel Tower, Paris", content(PhotoViewerUiState(photo = photo, place = named)).place)
        assertEquals("Paris", content(PhotoViewerUiState(photo = photo, place = cityOnly)).place)
    }

    @Test
    fun theSettingsSectionHasFourStates_andOnlyAReadIsRows() {
        fun settings(exif: ExifReadResult?) = content(PhotoViewerUiState(photo = photo, exif = exif)).settings

        assertEquals(AboutSettings.Reading, settings(null))
        assertTrue(settings(ExifReadResult.NotOnDevice) is AboutSettings.NotOnDevice, "a fact, said in one line")
        assertEquals(AboutSettings.Silent, settings(ExifReadResult.Unreachable), "the banner says that, not this panel")
        assertEquals(AboutSettings.Silent, settings(ExifReadResult.Failed("x")))
        val read = settings(ExifReadResult.Read(MediaMetadata(isoSpeed = 100, lensModel = "Wide")))
        assertEquals(listOf("ISO", "Lens"), (read as AboutSettings.Rows).rows.map { it.label })
        assertEquals(
            AboutSettings.Rows(emptyList()),
            settings(ExifReadResult.Read(MediaMetadata())),
            "a stripped photograph says little, and so does the sheet",
        )
    }
}
