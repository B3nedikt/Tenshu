package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.duplicates.DuplicatesViewModel
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
 * An abandoned Duplicates write must not keep the screen's view model alive — `TrashRetentionTest`'s
 * rule (`photos-ios.md` §8.16 note 15) for the third user of `boundedWrite`.
 */
class DuplicatesRetentionTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Trashes that never return and ignore cancellation — a lost Binder reply. */
    private class StuckTrashes(
        photos: List<Photo>,
        val hung: CompletableDeferred<Unit>,
    ) : PhotosRepository by MockPhotosRepository(photos) {
        var sent = 0

        override suspend fun trash(photo: Photo): Boolean {
            sent++
            withContext(NonCancellable) { hung.await() }
            return true
        }
    }

    private suspend fun TestScope.abandonOne(repo: PhotosRepository): WeakReference<DuplicatesViewModel> {
        val vms = OwnedViewModels()
        val vm = vms.own(DuplicatesViewModel(repo, timeoutMs = 1_000))
        testScheduler.advanceUntilIdle()
        val state = vm.state.value
        val group = state.groups.single()
        vm.keepOnly(state.confirmation(group, group.photos.first())!!)
        testScheduler.advanceUntilIdle()                    // both windows pass; the trash is abandoned
        vms.closeAll()
        return WeakReference(vm)
    }

    @Test
    fun anAbandonedTrashDoesNotRetainTheViewModel() = runTest(dispatcher) {
        val hung = CompletableDeferred<Unit>()
        val repo = StuckTrashes(
            listOf(
                Photo("/p1.jpg", 1L, isVideo = false, contentId = "c1", contentHash = "h1"),
                Photo("/p2.jpg", 2L, isVideo = false, contentId = "c2", contentHash = "h1"),
            ),
            hung,
        )

        val ref = abandonOne(repo)
        assertTrue(repo.sent == 1, "precondition: the write was sent and is stuck")
        repeat(6) {
            System.gc()
            Thread.sleep(60)
        }
        assertNull(ref.get(), "the stuck call holds only its own closure, never the screen")
        hung.complete(Unit)
    }
}
