package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.feed.FeedError
import app.tenshu.photos.ui.screens.trash.TrashViewModel
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
 * The trash (`photos-app.md` S8 / PH-5).
 *
 * ⚠⚠ Trashing is **reversible** and deleting for good is not, and the tests are written to keep
 * those two apart: the first pins that a trashed photo can come back to exactly where it was, and
 * the second that only the irreversible call destroys anything.
 */
class TrashViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(i: Int) =
        Photo("/Backup/Phone/p$i.jpg", 1_000L - i, isVideo = false, contentId = "c$i")

    @Test
    fun aTrashedPhotoWaitsInTheTrashWithWhereItCameFrom() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository()
        val vm = vms.own(TrashViewModel(repo))
        testScheduler.advanceUntilIdle()

        vm.trash(photo(1))
        testScheduler.advanceUntilIdle()

        val item = vm.state.value.items.single()
        assertEquals("c1", item.contentId)
        assertEquals("/Backup/Phone/p1.jpg", item.originalPath, "restore needs where it came from")
        assertTrue(item.photo.path.startsWith("/Trash/"), "and it is in the trash now")
    }

    @Test
    fun restoringPutsItBackAndTakesItOutOfTheTrash() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository()
        val vm = vms.own(TrashViewModel(repo))
        testScheduler.advanceUntilIdle()
        vm.trash(photo(1))
        vm.trash(photo(2))
        testScheduler.advanceUntilIdle()

        vm.restore("c1")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("c2"), vm.state.value.items.map { it.contentId })
        // ⚠⚠ And restoring destroyed nothing — the distinction the whole screen is built around.
        assertTrue(repo.purged.isEmpty())
    }

    @Test
    fun onlyDeleteForeverDestroysAPhotograph() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository()
        val vm = vms.own(TrashViewModel(repo))
        testScheduler.advanceUntilIdle()
        vm.trash(photo(1))
        testScheduler.advanceUntilIdle()

        vm.deleteForever("c1")
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.items.isEmpty())
        assertEquals(setOf("c1"), repo.purged)
    }

    @Test
    fun emptyingTheTrashDeletesEverythingInIt() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository()
        val vm = vms.own(TrashViewModel(repo))
        testScheduler.advanceUntilIdle()
        vm.trash(photo(1))
        vm.trash(photo(2))
        vm.trash(photo(3))
        testScheduler.advanceUntilIdle()

        vm.emptyTrash()
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.items.isEmpty())
        assertEquals(setOf("c1", "c2", "c3"), repo.purged)
    }

    /**
     * ⚠⚠ The half of the `/Trash` incident that made it invisible. `repository.trash` has always
     * returned whether it worked, and this view model used to drop it — so when the move failed
     * (which it did on every realm, `/Trash` never having been created) the viewer closed as though
     * the photograph had gone. The callback is what lets the caller keep the screen open instead.
     */
    @Test
    fun aFailedTrashIsReportedToTheCaller_ratherThanLookingLikeItWorked() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository(outcome = PhotoPageResult.Failed("no /Trash"))
        val vm = vms.own(TrashViewModel(repo))

        testScheduler.advanceUntilIdle()

        var reported: Boolean? = null
        vm.trash(photo(1)) { reported = it }
        testScheduler.advanceUntilIdle()

        assertEquals(false, reported, "a trash that did not happen must report false")
    }

    @Test
    fun aTrashThatWorkedReportsTrue() = vmTest(dispatcher) { vms ->
        val vm = vms.own(TrashViewModel(MockPhotosRepository()))

        testScheduler.advanceUntilIdle()

        var reported: Boolean? = null
        vm.trash(photo(1)) { reported = it }
        testScheduler.advanceUntilIdle()

        assertEquals(true, reported)
    }

    @Test
    fun aPhotoWithNoContentIdCannotBeTrashed() = vmTest(dispatcher) { vms ->
        // ⚠ A file predating content addressing: there is no key to record it under, and a trash
        // entry that cannot be restored would be worse than refusing.
        val vm = vms.own(TrashViewModel(MockPhotosRepository()))
        testScheduler.advanceUntilIdle()

        vm.trash(Photo("/old.jpg", 1L, isVideo = false, contentId = ""))
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun anUnreachableProxyIsNotAnEmptyTrash() = vmTest(dispatcher) { vms ->
        val vm = vms.own(TrashViewModel(MockPhotosRepository(outcome = PhotoPageResult.Unreachable)))
        testScheduler.advanceUntilIdle()

        assertEquals(FeedError.Unreachable, vm.state.value.error)
        assertFalse(vm.state.value.isEmpty)
    }

    @Test
    fun twoTrashedPhotosGetTwoDifferentTrashPaths() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The trash destination was `"/Trash/${'$'}{photo.contentId}-${'$'}{photo.name}"` written with
        // the escaped-dollar idiom, so it was a LITERAL: **every photo ever trashed resolved to the
        // same path**. In the real repository that path is a `moveFile` destination, so trashing a
        // second photo moved it on top of the first.
        //
        // ⚠⚠ `aTrashedPhotoWaitsInTheTrashWithWhereItCameFrom` asserts
        // `path.startsWith("/Trash/")` and stayed green throughout — the literal *does* start with
        // `/Trash/`. A prefix assertion cannot see the interpolated remainder, which is the whole of
        // what was broken; and one trashed photo cannot collide with anything. It takes a second row
        // and an assertion past the prefix.
        val repo = MockPhotosRepository()
        val vm = vms.own(TrashViewModel(repo))
        testScheduler.advanceUntilIdle()

        vm.trash(photo(1))
        vm.trash(photo(2))
        testScheduler.advanceUntilIdle()

        val paths = vm.state.value.items.map { it.photo.path }
        assertEquals(2, paths.size, "both must still be in the trash")
        assertEquals(paths.size, paths.toSet().size, "one destination for two photos overwrites: $paths")
        assertTrue(
            paths.any { it.contains("c1") && it.contains("p1.jpg") },
            "the destination must carry its own photo's id and name: $paths",
        )
    }
}
