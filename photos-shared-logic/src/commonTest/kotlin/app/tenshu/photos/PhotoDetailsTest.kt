package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoDetails
import app.tenshu.photos.ui.screens.viewer.PhotoViewerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.core.file.MediaMetadata
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * The viewer's info sheet (`photos-app.md` S3 / PH-7).
 *
 * ⚠⚠ Every formatter here has the same rule and it is the one under test: **absent prints
 * nothing**, never "Unknown" and never a zero. A photo whose EXIF a messaging app stripped
 * genuinely has no camera, and a line that says otherwise asserts something the row does not know.
 */
class PhotoDetailsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun details(
        media: MediaMetadata = MediaMetadata(),
        sizeBytes: Long? = null,
    ) = PhotoDetails("/Backup/Phone/IMG_0421.jpg", capturedAtMs = 1_700_000_000_000L, sizeBytes, media)

    @Test
    fun aPortraitPhotoIsNotReportedAsLandscape() {
        // ⚠⚠ Orientation 6 is what every portrait iPhone photo carries: the pixels are stored
        // landscape and the viewer rotates them. Printing the stored numbers would tell the user
        // their portrait photo is 4032 wide.
        val portrait = details(MediaMetadata(pixelWidth = 4032, pixelHeight = 3024, orientation = 6))

        assertEquals("3024 × 4032", portrait.dimensions)
        assertEquals("12.2 MP", portrait.megapixels)
    }

    @Test
    fun aMirroredPhotoKeepsItsAxes() {
        // 2 is a horizontal flip, not a quarter turn — the axes do not move.
        val mirrored = details(MediaMetadata(pixelWidth = 4032, pixelHeight = 3024, orientation = 2))
        assertEquals("4032 × 3024", mirrored.dimensions)
    }

    @Test
    fun aCameraThatNamesItselfTwiceIsPrintedOnce() {
        // ⚠ "Canon EOS 5D" beside make "Canon" is what the file really contains. Printing both
        // gives "Canon Canon EOS 5D". The stored values stay verbatim; only the line is tidied.
        assertEquals(
            "Canon EOS 5D",
            details(MediaMetadata(cameraMake = "Canon", cameraModel = "Canon EOS 5D")).camera,
        )
        assertEquals(
            "Apple iPhone 15 Pro",
            details(MediaMetadata(cameraMake = "Apple", cameraModel = "iPhone 15 Pro")).camera,
        )
        assertEquals("Apple", details(MediaMetadata(cameraMake = "Apple")).camera)
        assertEquals("iPhone 15 Pro", details(MediaMetadata(cameraModel = "iPhone 15 Pro")).camera)
        assertNull(details().camera)
    }

    @Test
    fun everyFormatterIsSilentRatherThanZero() {
        val nothing = details()

        assertNull(nothing.dimensions)
        assertNull(nothing.megapixels)
        assertNull(nothing.camera)
        assertNull(nothing.duration)
        assertNull(nothing.size)
    }

    @Test
    fun aThumbnailDoesNotClaimZeroMegapixels() {
        // ⚠ "0.0 MP" reads as a bug. The exact pixel count is already on the line above it.
        val tiny = details(MediaMetadata(pixelWidth = 64, pixelHeight = 64))

        assertEquals("64 × 64", tiny.dimensions)
        assertNull(tiny.megapixels)
    }

    @Test
    fun aVideosLengthReadsAsAClock() {
        assertEquals("0:07", details(MediaMetadata(durationMs = 7_000)).duration)
        assertEquals("1:23", details(MediaMetadata(durationMs = 83_000)).duration)
        assertEquals("1:02:03", details(MediaMetadata(durationMs = 3_723_000)).duration)
        assertNull(details(MediaMetadata(durationMs = 0)).duration)
    }

    @Test
    fun sizeUsesThePhonesOwnUnits() {
        // ⚠ Powers of 1000, matching what a phone's storage screen shows. A file that reads
        // 2.4 MB in one place and 2.3 MiB in another looks like two files.
        assertEquals("512 B", details(sizeBytes = 512).size)
        assertEquals("2.4 MB", details(sizeBytes = 2_400_000).size)
        assertEquals("1.5 GB", details(sizeBytes = 1_500_000_000).size)
    }

    // ── the viewer's lazy read ───────────────────────────────────────────────────────────────

    @Test
    fun theSheetReadsOnlyWhenItIsOpened() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The read is a search of the realm for one row. Paying it on every viewer open would
        // make the most-used screen slower for a panel most people never see.
        val repo = CountingRepository(MockPhotosRepository(listOf(photo())))
        val vm = vms.own(PhotoViewerViewModel(repo, photo()))
        testScheduler.advanceUntilIdle()

        assertEquals(0, repo.detailReads, "opening the viewer must not read metadata")
        assertFalse(vm.state.value.infoOpen)

        vm.toggleInfo()
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.infoOpen)
        assertEquals(1, repo.detailReads)
        assertEquals("/Backup/Phone/p0.jpg", vm.state.value.details?.path)
    }

    @Test
    fun reopeningTheSheetDoesNotReadAgain() = vmTest(dispatcher) { vms ->
        val repo = CountingRepository(MockPhotosRepository(listOf(photo())))
        val vm = vms.own(PhotoViewerViewModel(repo, photo()))
        testScheduler.advanceUntilIdle()

        vm.toggleInfo(); testScheduler.advanceUntilIdle()
        vm.toggleInfo(); testScheduler.advanceUntilIdle()
        vm.toggleInfo(); testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.infoOpen)
        assertEquals(1, repo.detailReads, "the metadata does not change while the viewer is open")
    }

    @Test
    fun aFileWithNoStoredMetadataLeavesTheSheetEmptyRatherThanBroken() = vmTest(dispatcher) { vms ->
        // Every file uploaded before file-service v8 is in this state, and there are more of those
        // than of the other kind.
        val repo = MockPhotosRepository(listOf(photo()))
        val vm = vms.own(PhotoViewerViewModel(repo, photo()))
        testScheduler.advanceUntilIdle()
        vm.toggleInfo()
        testScheduler.advanceUntilIdle()

        val shown = vm.state.value.details
        assertEquals("/Backup/Phone/p0.jpg", shown?.path)
        assertNull(shown?.dimensions)
        assertNull(shown?.camera)
    }

    @Test
    fun swipingWithTheSheetClosedReadsNothing() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The lazy read is what makes the pager affordable: a metadata read per swipe would put
        // a realm search on the app's most repeated gesture, for a panel most people never open.
        val repo = CountingRepository(MockPhotosRepository(photos()))
        val vm = vms.own(PhotoViewerViewModel(repo, photo()))
        testScheduler.advanceUntilIdle()

        vm.onPhotoShown(photoAt(1))
        vm.onPhotoShown(photoAt(2))
        testScheduler.advanceUntilIdle()

        assertEquals(0, repo.detailReads)
        assertEquals("/Backup/Phone/p2.jpg", vm.state.value.path)
    }

    @Test
    fun theOpenSheetFollowsTheSwipe() = vmTest(dispatcher) { vms ->
        val repo = CountingRepository(MockPhotosRepository(photos()))
        val vm = vms.own(PhotoViewerViewModel(repo, photo()))
        vm.toggleInfo()
        testScheduler.advanceUntilIdle()
        assertEquals("/Backup/Phone/p0.jpg", vm.state.value.details?.path)

        vm.onPhotoShown(photoAt(1))
        testScheduler.advanceUntilIdle()

        assertEquals(2, repo.detailReads, "an open sheet re-reads for the photo now on screen")
        assertEquals("/Backup/Phone/p1.jpg", vm.state.value.details?.path)
    }

    @Test
    fun theSheetNeverShowsOnePhotosMetadataUnderAnothersName() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The sheet takes the name from `path` and the camera from `details`. If the second
        // outlives the first by even one frame it asserts something neither row says — so the move
        // clears it, and the re-read fills it back in.
        val repo = CountingRepository(MockPhotosRepository(photos()))
        val vm = vms.own(PhotoViewerViewModel(repo, photo()))
        vm.toggleInfo()
        testScheduler.advanceUntilIdle()

        vm.onPhotoShown(photoAt(1))
        // ⚠ Before the read lands, deliberately: this is the frame the bug would live in.
        assertEquals("/Backup/Phone/p1.jpg", vm.state.value.path)
        assertNull(vm.state.value.details, "the previous photo's metadata must not survive the swipe")
    }

    private fun photo() = Photo("/Backup/Phone/p0.jpg", 1_000L, isVideo = false, contentId = "c0")

    private fun photos() = List(3) { i -> photoAt(i) }

    private fun photoAt(i: Int) =
        Photo("/Backup/Phone/p$i.jpg", 1_000L - i, isVideo = false, contentId = "c$i")

    /** Counts metadata reads, so "did it read?" is a fact rather than an inference. */
    private class CountingRepository(
        private val delegate: MockPhotosRepository,
    ) : app.tenshu.photos.data.PhotosRepository by delegate {
        var detailReads = 0
            private set

        override suspend fun details(path: String): PhotoDetails? {
            detailReads++
            return delegate.details(path)
        }
    }
}
