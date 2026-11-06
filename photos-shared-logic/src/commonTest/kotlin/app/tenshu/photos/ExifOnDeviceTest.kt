package app.tenshu.photos

import app.tenshu.photos.data.ExifReadResult
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.apertureLabel
import app.tenshu.photos.model.exposureBiasLabel
import app.tenshu.photos.model.exposureLabel
import app.tenshu.photos.model.focalLengthLabel
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
import kotlin.test.assertNull
import org.tenshu.viewmodel.test.vmTest

/**
 * The on-device EXIF read and how it reads as lines (`photos-viewer-about-screen.md` PH-11b).
 *
 * ⚠⚠ The costly half of this feature is a *read of the file's bytes*, so the tests that matter are
 * the ones about **when it is not fired at all**.
 */
class ExifOnDeviceTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(path: String = "/a.jpg") =
        Photo(path, 1_000L, isVideo = false, contentId = "c-a")

    /** Counts the byte-reading call, so "did it read?" is a fact rather than an inference. */
    private class CountingRepository(
        private val delegate: PhotosRepository,
        private val answer: ExifReadResult,
    ) : PhotosRepository by delegate {
        var exifReads = 0
            private set

        override suspend fun photographicSettings(path: String): ExifReadResult {
            exifReads++
            return answer
        }
    }

    private fun repo(answer: ExifReadResult = ExifReadResult.NotOnDevice) =
        CountingRepository(MockPhotosRepository(), answer)

    @Test
    fun openingTheViewerReadsNoBytes() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The read costs a file. Firing it on every viewer open would put that on the app's most
        // used screen for a panel most people never open.
        val repository = repo()
        val vm = vms.own(PhotoViewerViewModel(repository, photo()))
        testScheduler.advanceUntilIdle()

        assertEquals(0, repository.exifReads)
        assertNull(vm.state.value.exif)
    }

    @Test
    fun swipingWithTheSheetClosedReadsNoBytes() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The one that would hurt most: a byte read per swipe, on the app's most repeated gesture.
        val repository = repo()
        val vm = vms.own(PhotoViewerViewModel(repository, photo("/a.jpg")))
        testScheduler.advanceUntilIdle()

        vm.onPhotoShown(photo("/b.jpg"))
        vm.onPhotoShown(photo("/c.jpg"))
        testScheduler.advanceUntilIdle()

        assertEquals(0, repository.exifReads)
    }

    @Test
    fun theSheetReadsOnceWhenOpened() = vmTest(dispatcher) { vms ->
        val repository = repo(ExifReadResult.Read(MediaMetadata(isoSpeed = 64)))
        val vm = vms.own(PhotoViewerViewModel(repository, photo()))
        testScheduler.advanceUntilIdle()

        vm.toggleInfo()
        testScheduler.advanceUntilIdle()

        assertEquals(1, repository.exifReads)
        assertEquals(64, (vm.state.value.exif as ExifReadResult.Read).media.isoSpeed)
    }

    @Test
    fun reopeningTheSheetDoesNotReadAgain() = vmTest(dispatcher) { vms ->
        val repository = repo(ExifReadResult.Read(MediaMetadata(isoSpeed = 64)))
        val vm = vms.own(PhotoViewerViewModel(repository, photo()))
        testScheduler.advanceUntilIdle()

        vm.toggleInfo(); testScheduler.advanceUntilIdle()
        vm.toggleInfo(); testScheduler.advanceUntilIdle()
        vm.toggleInfo(); testScheduler.advanceUntilIdle()

        assertEquals(1, repository.exifReads, "the file does not change while the viewer is open")
    }

    @Test
    fun theOpenSheetRereadsForTheNextPhotograph() = vmTest(dispatcher) { vms ->
        val repository = repo(ExifReadResult.Read(MediaMetadata(isoSpeed = 64)))
        val vm = vms.own(PhotoViewerViewModel(repository, photo("/a.jpg")))
        vm.toggleInfo()
        testScheduler.advanceUntilIdle()
        assertEquals(1, repository.exifReads)

        vm.onPhotoShown(photo("/b.jpg"))
        // ⚠⚠ Before the read lands: the previous photograph's settings must not survive the swipe.
        // An aperture from the last picture under this one's name describes a photo never taken.
        assertNull(vm.state.value.exif)
        testScheduler.advanceUntilIdle()

        assertEquals(2, repository.exifReads)
    }

    @Test
    fun notOnDeviceIsAnAnswerAndNotAFailure() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The state the whole design exists to produce: the read refused *before* spending
        // anything, and the panel has something true to say.
        val repository = repo(ExifReadResult.NotOnDevice)
        val vm = vms.own(PhotoViewerViewModel(repository, photo()))
        vm.toggleInfo()
        testScheduler.advanceUntilIdle()

        assertEquals(ExifReadResult.NotOnDevice, vm.state.value.exif)
    }

    // ── the lines (PH-11b presentation) ──────────────────────────────────────────────────────

    @Test
    fun shortExposuresAreFractionsAndLongOnesAreNot() {
        // ⚠ "0.004 s" is right and unreadable to the only people who look at this row.
        assertEquals("1/250 s", MediaMetadata(exposureTimeSeconds = 0.004).exposureLabel)
        assertEquals("2.5 s", MediaMetadata(exposureTimeSeconds = 2.5).exposureLabel)
        assertEquals("30 s", MediaMetadata(exposureTimeSeconds = 30.0).exposureLabel)
        assertNull(MediaMetadata().exposureLabel)
    }

    @Test
    fun apertureDropsATrailingZero() {
        assertEquals("f/1.8", MediaMetadata(fNumber = 1.8).apertureLabel)
        assertEquals("f/2", MediaMetadata(fNumber = 2.0).apertureLabel, "not f/2.0")
        assertNull(MediaMetadata().apertureLabel)
    }

    @Test
    fun focalLengthCarriesBothNumbersWhenBothAreKnown() {
        // ⚠⚠ On a phone the true and equivalent focal lengths differ by ~5×. Printing only one
        // silently answers a different question than the reader asked.
        assertEquals("26 mm", MediaMetadata(focalLengthMm = 26.0).focalLengthLabel)
        assertEquals(
            "6.9 mm (24 mm equivalent)",
            MediaMetadata(focalLengthMm = 6.86, focalLength35mm = 24).focalLengthLabel,
        )
        assertNull(MediaMetadata().focalLengthLabel)
    }

    @Test
    fun zeroExposureBiasIsRenderedRatherThanSuppressed() {
        // ⚠⚠ 0 EV is the default and by far the commonest reading. A field that vanishes exactly
        // when it holds its usual value looks broken rather than absent.
        assertEquals("0 EV", MediaMetadata(exposureBiasEv = 0.0).exposureBiasLabel)
        assertEquals("+0.3 EV", MediaMetadata(exposureBiasEv = 0.33).exposureBiasLabel)
        assertEquals("−1 EV", MediaMetadata(exposureBiasEv = -1.0).exposureBiasLabel)
        assertNull(MediaMetadata().exposureBiasLabel, "absent is still absent")
    }
}
