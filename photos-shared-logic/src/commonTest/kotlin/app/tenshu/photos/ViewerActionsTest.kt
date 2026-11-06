package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.viewer.PhotoViewerViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.tenshu.viewmodel.test.vmTest

/**
 * The viewer's menu writes: trash and add to album.
 *
 * ⚠⚠ The load-bearing tests are the refusals: a viewer that closed on a trash that never happened
 * is the `/Trash` incident `TrashViewModel` records.
 */
class ViewerActionsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(path: String, contentId: String = "c$path") = Photo(path, 1_000L, isVideo = false, contentId = contentId)

    /** Records which photographs were written, and waits for [release] before answering. */
    private class Recording(
        private val answer: Boolean = true,
        private val delegate: PhotosRepository = MockPhotosRepository(),
    ) : PhotosRepository by delegate {
        val release = CompletableDeferred<Unit>().apply { complete(Unit) }
        var gate: CompletableDeferred<Unit> = release
        val trashed = mutableListOf<String>()
        val added = mutableListOf<Pair<String, String>>()
        override suspend fun trash(photo: Photo): Boolean {
            gate.await()
            trashed += photo.path
            return answer
        }
        override suspend fun addToAlbum(albumId: String, photo: Photo): Boolean {
            added += albumId to photo.path
            return answer
        }
    }

    @Test
    fun aTrashThatHappenedSaysSo() = vmTest(dispatcher) { vms ->
        val repository = Recording(answer = true)
        val vm = vms.own(PhotoViewerViewModel(repository, photo("/a.jpg")))
        var result: Boolean? = null
        vm.trash { result = it }
        testScheduler.advanceUntilIdle()
        assertEquals(true, result)
        assertEquals(listOf("/a.jpg"), repository.trashed)
    }

    @Test
    fun aRefusedTrashSaysSo_soTheViewerStaysOpen() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The /Trash incident: an unreachable proxy refuses every write.
        val repository = MockPhotosRepository(outcome = PhotoPageResult.Unreachable)
        val vm = vms.own(PhotoViewerViewModel(repository, photo("/a.jpg")))
        var result: Boolean? = null
        vm.trash { result = it }
        testScheduler.advanceUntilIdle()
        assertEquals(false, result)
    }

    @Test
    fun itTrashesThePhotographOnScreen_notTheSeed() = vmTest(dispatcher) { vms ->
        val repository = Recording()
        val vm = vms.own(PhotoViewerViewModel(repository, photo("/tapped.jpg")))
        vm.onPhotoShown(photo("/swiped-to.jpg"))
        vm.trash {}
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("/swiped-to.jpg"), repository.trashed)
    }

    @Test
    fun aSecondTrashWhileTheFirstRunsIsDropped() = vmTest(dispatcher) { vms ->
        val repository = Recording()
        repository.gate = CompletableDeferred()
        val vm = vms.own(PhotoViewerViewModel(repository, photo("/a.jpg")))
        val results = mutableListOf<Boolean>()
        vm.trash { results += it }
        testScheduler.advanceUntilIdle()
        vm.trash { results += it }
        repository.gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("/a.jpg"), repository.trashed, "moved ONCE")
        assertEquals(listOf(true), results)
    }

    @Test
    fun theViewerCanSeeAWriteIsRunning_soItCanStopOfferingActionsItWouldDrop() = vmTest(dispatcher) { vms ->
        // ⚠ A dropped write never calls back, so the shells must disable the menu.
        val repository = Recording()
        repository.gate = CompletableDeferred()
        val vm = vms.own(PhotoViewerViewModel(repository, photo("/a.jpg")))
        assertEquals(false, vm.state.value.writing)
        vm.trash {}
        testScheduler.advanceUntilIdle()
        assertEquals(true, vm.state.value.writing, "busy while the move runs")
        repository.gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(false, vm.state.value.writing, "and free again after")
    }

    @Test
    fun addingToAnAlbumReportsWhatHappened() = vmTest(dispatcher) { vms ->
        val refusing = Recording(answer = false)
        val vm = vms.own(PhotoViewerViewModel(refusing, photo("/a.jpg")))
        var result: Boolean? = null
        vm.addToAlbum("album-1", photo("/a.jpg")) { result = it }
        testScheduler.advanceUntilIdle()
        assertEquals(false, result)
        assertEquals(listOf("album-1" to "/a.jpg"), refusing.added)
    }

    @Test
    fun theSubtitleNamesTheCity_elseThePlace() {
        val paris = app.tenshu.photos.model.PhotoPlace("p", "Eiffel Tower", "Paris", 0, 0)
        val nowhere = app.tenshu.photos.model.PhotoPlace("q", "Grandma's", "", 0, 0)
        assertEquals("Paris", paris.displayName)
        assertEquals("Grandma's", nowhere.displayName, "a place with no city is still named, not blank")
    }

    @Test
    fun theAlbumPickFilesThePhotographTheSheetOpenedFor_notTheOneOnScreenNow() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A live refresh deletes the photograph while the sheet is open and the viewer moves to
        // its neighbour — reading the state at pick time would file the neighbour.
        val repository = Recording()
        val vm = vms.own(PhotoViewerViewModel(repository, photo("/a.jpg")))
        val openedFor = vm.state.value.photo
        vm.onPhotoShown(photo("/neighbour.jpg"))
        vm.addToAlbum("album-1", openedFor) {}
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("album-1" to "/a.jpg"), repository.added)
    }
}
