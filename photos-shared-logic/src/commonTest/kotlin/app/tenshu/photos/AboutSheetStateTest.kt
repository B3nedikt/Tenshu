package app.tenshu.photos

import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.viewer.PhotoViewerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * What the About sheet knows (`photos-viewer-about-screen.md` PH-10).
 *
 * ⚠ The album row is the one new *read* in this phase; everything else the sheet shows was already
 * on the state. So these are about when that read fires, and what it does across a swipe.
 */
class AboutSheetStateTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(path: String = "/a.jpg", contentId: String = "c-a") =
        Photo(path, 1_000L, isVideo = false, contentId = contentId)

    private fun album(name: String, count: Int = 28) =
        AlbumSummary("album-$name", name, createdAtMs = 0L, photoCount = count, coverPath = "/cover.jpg")

    private class CountingRepository(
        private val delegate: PhotosRepository,
        private val answer: List<AlbumSummary>,
    ) : PhotosRepository by delegate {
        var albumReads = 0
            private set

        override suspend fun albumsFor(contentId: String): List<AlbumSummary> {
            albumReads++
            return if (contentId.isEmpty()) emptyList() else answer
        }
    }

    private fun repo(answer: List<AlbumSummary> = listOf(album("Trip to France"))) =
        CountingRepository(MockPhotosRepository(), answer)

    @Test
    fun openingTheViewerReadsNoAlbums() = vmTest(dispatcher) { vms ->
        // ⚠ Same laziness as every other read the sheet owns — the panel is opened on a minority of
        // the photographs anyone looks at.
        val repository = repo()
        val vm = vms.own(PhotoViewerViewModel(repository, photo()))
        testScheduler.advanceUntilIdle()

        assertEquals(0, repository.albumReads)
        assertNull(vm.state.value.albums)
    }

    @Test
    fun theSheetReadsTheAlbumsWhenOpened() = vmTest(dispatcher) { vms ->
        val repository = repo()
        val vm = vms.own(PhotoViewerViewModel(repository, photo()))
        testScheduler.advanceUntilIdle()

        vm.toggleInfo()
        testScheduler.advanceUntilIdle()

        assertEquals(1, repository.albumReads)
        assertEquals(listOf("Trip to France"), vm.state.value.albums?.map { it.name })
    }

    @Test
    fun theAlbumsFollowTheSwipe() = vmTest(dispatcher) { vms ->
        val repository = repo()
        val vm = vms.own(PhotoViewerViewModel(repository, photo("/a.jpg")))
        vm.toggleInfo()
        testScheduler.advanceUntilIdle()
        assertEquals(1, repository.albumReads)

        vm.onPhotoShown(photo("/b.jpg", contentId = "c-b"))
        // ⚠⚠ Cleared before the next read lands. "Trip to France" printed under a photograph that is
        // not in it is a membership claim the realm never made.
        assertNull(vm.state.value.albums)
        testScheduler.advanceUntilIdle()

        assertEquals(2, repository.albumReads)
    }

    @Test
    fun aPhotoInNoAlbumReadsAsEmptyNotAsUnread() = vmTest(dispatcher) { vms ->
        // ⚠ `null` and `emptyList()` render the same — no section — but only one of them is a fact,
        // and the state must be able to tell them apart for the section ever to be trusted.
        val repository = repo(answer = emptyList())
        val vm = vms.own(PhotoViewerViewModel(repository, photo()))
        vm.toggleInfo()
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList(), vm.state.value.albums)
    }

    @Test
    fun aPhotoWithNoContentIdIsInNoAlbumByConstruction() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Membership is keyed on `contentId` (`photos-app.md` §11.2), so a file predating content
        // addressing cannot be in an album at all. The repository short-circuits rather than
        // spending two realm reads to be told a certainty.
        val repository = repo()
        val vm = vms.own(PhotoViewerViewModel(repository, photo(contentId = "")))
        vm.toggleInfo()
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList(), vm.state.value.albums)
    }

    @Test
    fun openingTheSheetForcesTheChromeBackAndIsWhatLocksThePager() = vmTest(dispatcher) { vms ->
        // ⚠⚠ `infoOpen` is what the pager reads to lock itself. If it could be true while the chrome
        // is hidden, the user would be looking at a locked photograph with no visible way out.
        val vm = vms.own(PhotoViewerViewModel(repo(), photo()))
        testScheduler.advanceUntilIdle()

        vm.toggleChrome()
        vm.toggleInfo()

        assertTrue(vm.state.value.infoOpen)
        assertTrue(vm.state.value.chromeVisible)
    }
}
