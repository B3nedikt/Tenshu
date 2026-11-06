package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.trash.TrashViewModel
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

/**
 * An abandoned Trash write must not keep the Trash's view model alive — `AbandonedWriteRetentionTest`'s
 * rule (`photos-ios.md` §8.16 note 15) for the second user of `boundedWrite`.
 */
class TrashRetentionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Restores that never return and ignore cancellation — a lost Binder reply. */
    private class StuckRestores(val hung: CompletableDeferred<Unit>) : PhotosRepository by MockPhotosRepository() {
        override suspend fun restore(contentId: String): Boolean {
            withContext(NonCancellable) { hung.await() }
            return true
        }
    }

    private suspend fun TestScope.abandonOne(repo: PhotosRepository): WeakReference<TrashViewModel> {
        val vms = OwnedViewModels()
        val vm = vms.own(TrashViewModel(repo, timeoutMs = 1_000))
        testScheduler.advanceUntilIdle()
        vm.restore("c1")
        testScheduler.advanceUntilIdle()                    // both windows pass; the restore is abandoned
        vms.closeAll()
        return WeakReference(vm)
    }

    @Test
    fun anAbandonedRestoreDoesNotRetainTheViewModel() = runTest(dispatcher) {
        val hung = CompletableDeferred<Unit>()
        val repo = StuckRestores(hung)
        repo.trash(Photo("/p1.jpg", 1L, isVideo = false, contentId = "c1"))

        val ref = abandonOne(repo)
        repeat(6) {
            System.gc()
            Thread.sleep(60)
        }
        assertNull(ref.get(), "the stuck call holds only its own closure, never the screen")
        hung.complete(Unit)
    }
}
