package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.duplicates.DuplicatesViewModel
import app.tenshu.photos.ui.screens.feed.FeedError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * The Duplicates review surface (`photos-app.md` S4 / PH-D2 / PH-6).
 *
 * ⚠⚠ The tests are written around the one property that matters: **the copy the user keeps is
 * never the one that goes.** Everything else here is presentation; that one is the difference
 * between a cleanup tool and a data-loss bug.
 */
class DuplicatesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(i: Int, hash: String, atMs: Long = 1_000L - i) = Photo(
        path = "/Backup/Phone/p$i.jpg",
        capturedAtMs = atMs,
        isVideo = false,
        contentId = "c$i",
        contentHash = hash,
    )

    @Test
    fun onlyContentHeldTwiceIsOffered() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository(
            listOf(photo(1, "h1"), photo(2, "h1"), photo(3, "h2"))
        )

        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(1, state.groups.size)
        assertEquals("h1", state.groups.single().contentHash)
        assertEquals(2, state.groups.single().photos.size)
        assertEquals(3, state.filesScanned)
        assertFalse(state.isEmpty)
    }

    @Test
    fun keepingOneTrashesTheRestAndNeverTheKeeper() = vmTest(dispatcher) { vms ->
        val keeper = photo(1, "h1")
        val repo = MockPhotosRepository(listOf(keeper, photo(2, "h1"), photo(3, "h1")))

        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()
        vm.keepOnly(vm.state.value.confirmation(vm.state.value.groups.single(), keeper)!!)
        testScheduler.advanceUntilIdle()

        // The keeper is still a live photo; the other two are in the trash, restorable.
        val trashed = (repo.trashed() as app.tenshu.photos.data.TrashResult.Items).items
        assertEquals(setOf("c2", "c3"), trashed.map { it.contentId }.toSet())
        assertTrue(repo.purged.isEmpty(), "the reversible action must not destroy anything")
        // And the group is gone from the screen, because only one copy is left.
        assertTrue(vm.state.value.groups.isEmpty())
    }

    @Test
    fun theCheckboxIsOffByDefaultAndOnlyThenDestroys() = vmTest(dispatcher) { vms ->
        val keeper = photo(1, "h1")
        val repo = MockPhotosRepository(listOf(keeper, photo(2, "h1")))

        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.deletePermanently, "the irreversible path is never the default")

        vm.setDeletePermanently(true)
        vm.keepOnly(vm.state.value.confirmation(vm.state.value.groups.single(), keeper)!!)
        testScheduler.advanceUntilIdle()

        assertEquals(setOf("c2"), repo.purged)
        assertTrue(
            (repo.trashed() as app.tenshu.photos.data.TrashResult.Items).items.isEmpty(),
            "a permanent delete does not also leave a trash row behind",
        )
    }

    @Test
    fun anUnreachableProxyIsAnErrorAndNotAnEmptyScreen() = vmTest(dispatcher) { vms ->
        // ⚠ The distinction this app makes everywhere: "no duplicates" and "could not look" must
        // never render the same, or a broken transport reads as a clean library.
        val repo = MockPhotosRepository(outcome = PhotoPageResult.Unreachable)

        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()

        assertEquals(FeedError.Unreachable, vm.state.value.error)
        assertFalse(vm.state.value.isEmpty)
    }

    @Test
    fun aPhotoWithNoHashIsNeverGroupedWithAnother() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository(listOf(photo(1, ""), photo(2, "")))

        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.groups.isEmpty())
        assertTrue(vm.state.value.isEmpty)
    }
}
