package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailViewModel
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
 * An abandoned write must not keep the screen's view model alive (`photos-ios.md` §8.16 note 15).
 *
 * ⚠⚠ A Binder call that never returns keeps its closure alive for good, and a closure that touches
 * a field of the view model keeps the whole view model — once per retry. JVM-only: it takes a
 * garbage collector to see what a stuck call still holds.
 */
class AbandonedWriteRetentionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** A repository whose renames never return and ignore cancellation — a lost Binder reply. */
    private class StuckRenames(val hung: CompletableDeferred<Unit>) : PhotosRepository by MockPhotosRepository() {
        override suspend fun renameAlbum(albumId: String, name: String): Boolean {
            withContext(NonCancellable) { hung.await() }
            return true
        }
    }

    /** Builds a screen, abandons a rename on it, closes it — and returns only a weak handle to it. */
    private suspend fun TestScope.abandonOne(repo: PhotosRepository, albumId: String): WeakReference<AlbumDetailViewModel> {
        val vms = OwnedViewModels()
        val vm = vms.own(AlbumDetailViewModel(repo, albumId, "Holiday", editTimeoutMs = 1_000))
        testScheduler.advanceUntilIdle()
        vm.rename("Summer") {}
        testScheduler.advanceUntilIdle()                    // both windows pass; the rename is abandoned
        vms.closeAll()
        return WeakReference(vm)
    }

    @Test
    fun anAbandonedWriteDoesNotRetainTheViewModel() = runTest(dispatcher) {
        val hung = CompletableDeferred<Unit>()
        val repo = StuckRenames(hung)
        val albumId = repo.createAlbum("Holiday")!!

        val ref = abandonOne(repo, albumId)
        repeat(6) {
            System.gc()
            Thread.sleep(60)
        }
        assertNull(ref.get(), "the stuck call holds only its own closure, never the screen")
        hung.complete(Unit)
    }
}
