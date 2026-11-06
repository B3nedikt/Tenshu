package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.search.PhotoSearchViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.tenshu.viewmodel.test.OwnedViewModels
import java.lang.ref.WeakReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A search whose scan never returns must not keep the Search screen's view model alive —
 * `TrashRetentionTest`'s rule (`photos-ios.md` §8.16 note 15) for `PhotoSearchViewModel`, which
 * abandons a scan every time a newer query supersedes it.
 */
class SearchRetentionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** A name scan that never returns and ignores cancellation — a lost Binder reply. */
    private class StuckScan(val hung: CompletableDeferred<Unit>) :
        PhotosRepository by MockPhotosRepository(listOf(Photo("/dog.jpg", 1L, isVideo = false, contentId = "c1"))) {
        var sent = 0

        override suspend fun searchByNameRead(query: String): List<Photo>? {
            sent++
            withContext(NonCancellable) { hung.await() }
            return emptyList()
        }
    }

    private suspend fun TestScope.abandonOne(repo: PhotosRepository): WeakReference<PhotoSearchViewModel> {
        val vms = OwnedViewModels()
        val vm = vms.own(PhotoSearchViewModel(repo, timeoutMs = 1_000))
        vm.onQueryChanged("dog")
        testScheduler.advanceUntilIdle()                    // the bound passes; the scan is abandoned
        vms.closeAll()
        return WeakReference(vm)
    }

    @Test
    fun anAbandonedScanDoesNotRetainTheViewModel() = runTest(dispatcher) {
        val hung = CompletableDeferred<Unit>()
        val repo = StuckScan(hung)

        val ref = abandonOne(repo)
        assertTrue(repo.sent == 1, "precondition: the scan was sent and is stuck")
        repeat(6) {
            System.gc()
            Thread.sleep(60)
        }
        assertNull(ref.get(), "the stuck scan holds only its own closure, never the screen")
        hung.complete(Unit)
    }
}
