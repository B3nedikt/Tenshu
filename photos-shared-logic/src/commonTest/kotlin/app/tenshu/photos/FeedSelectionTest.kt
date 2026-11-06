package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.feed.PhotosFeedViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest

/**
 * The feed's multi-select (`photos-app.md` S1).
 *
 * ⚠ The selection lives in the view model rather than in the screen so that it survives the
 * recompositions paging causes, and so these can be asserted without a renderer.
 */
class FeedSelectionTest {

    @BeforeTest fun setUpMain() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest fun tearDownMain() = Dispatchers.resetMain()

    private val day = 86_400_000L

    private fun photos(n: Int) = List(n) { i ->
        Photo("/p$i.jpg", (n - i) * day, isVideo = false, contentId = "c$i")
    }

    /**
     * A repository whose writes fail for the paths named in [failing].
     *
     * ⚠ Failure has to be selectable per photograph, because the case worth getting right is the
     * PARTIAL one — all-or-nothing would be indistinguishable from a boolean.
     */
    private class PartialRepository(
        photos: List<Photo>,
        private val failing: Set<String>,
        private val delegate: app.tenshu.photos.data.PhotosRepository =
            MockPhotosRepository(photos = photos),
    ) : app.tenshu.photos.data.PhotosRepository by delegate {
        val trashed = mutableListOf<String>()
        override suspend fun trash(photo: Photo): Boolean {
            trashed += photo.path
            return photo.path !in failing
        }

        override suspend fun addToAlbum(albumId: String, photo: Photo): Boolean =
            photo.path !in failing
    }

    private fun viewModel(vms: OwnedViewModels, repository: app.tenshu.photos.data.PhotosRepository) =
        vms.own(PhotosFeedViewModel(repository = repository, sectionStartOf = { _, ms -> ms / day * day }))

    @Test
    fun aPhotographIsSelectedAndDeselectedByTheSameGesture() = vmTest { vms ->
        val all = photos(3)
        val vm = viewModel(vms, MockPhotosRepository(photos = all))

        vm.toggleSelection(all[0])
        vm.toggleSelection(all[1])
        assertEquals(setOf("/p0.jpg", "/p1.jpg"), vm.selected.value.keys)

        vm.toggleSelection(all[0])
        assertEquals(setOf("/p1.jpg"), vm.selected.value.keys, "the same gesture takes it back out")
    }

    @Test
    fun clearingLeavesNothingSelected() = vmTest { vms ->
        val all = photos(3)
        val vm = viewModel(vms, MockPhotosRepository(photos = all))
        all.forEach(vm::toggleSelection)

        vm.clearSelection()

        assertTrue(vm.selected.value.isEmpty())
    }

    @Test
    fun everySelectedPhotographIsTrashed() = vmTest { vms ->
        val all = photos(4)
        val repository = PartialRepository(all, failing = emptySet())
        val vm = viewModel(vms, repository)
        listOf(all[0], all[2]).forEach(vm::toggleSelection)

        var outcome: app.tenshu.photos.ui.screens.feed.BulkOutcome? = null
        vm.trashSelected { outcome = it }

        assertEquals(listOf("/p0.jpg", "/p2.jpg"), repository.trashed)
        assertEquals(2, outcome?.succeeded)
        assertEquals(0, outcome?.failed)
        assertTrue(vm.selected.value.isEmpty(), "a clean run leaves nothing selected")
    }

    /**
     * ⚠⚠ **The case the whole contract exists for.** `TrashViewModel` records why the single-photo
     * path returns a `Boolean` at all: trashing failed on *every* realm for months because nothing
     * created `/Trash`, and the viewer closed as though the photograph had gone. In bulk the same
     * failure is worse, because "some of them" is a state a single boolean cannot express.
     */
    @Test
    fun aPartialFailureIsReportedWithBothNumbers() = vmTest { vms ->
        val all = photos(5)
        val repository = PartialRepository(all, failing = setOf("/p1.jpg", "/p3.jpg"))
        val vm = viewModel(vms, repository)
        all.forEach(vm::toggleSelection)

        var outcome: app.tenshu.photos.ui.screens.feed.BulkOutcome? = null
        vm.trashSelected { outcome = it }

        assertEquals(3, outcome?.succeeded, "three moved")
        assertEquals(2, outcome?.failed, "and two did not")
        assertTrue(outcome?.partial == true, "which is a partial outcome, not a success")
    }

    /**
     * ⚠⚠ The failures stay selected. Clearing everything after a partial failure throws away the
     * user's record of which photographs still need dealing with — they would have to find the two
     * that did not move, in a grid of hundreds, with nothing marking them.
     */
    @Test
    fun theOnesThatFailedStaySelectedSoTheyCanBeRetried() = vmTest { vms ->
        val all = photos(5)
        val repository = PartialRepository(all, failing = setOf("/p1.jpg", "/p3.jpg"))
        val vm = viewModel(vms, repository)
        all.forEach(vm::toggleSelection)

        vm.trashSelected()

        assertEquals(
            setOf("/p1.jpg", "/p3.jpg"),
            vm.selected.value.keys,
            "exactly the failures remain, ready to try again",
        )
    }

    @Test
    fun addingToAnAlbumCarriesTheSamePartialFailureContract() = vmTest { vms ->
        val all = photos(4)
        val repository = PartialRepository(all, failing = setOf("/p2.jpg"))
        val vm = viewModel(vms, repository)
        all.forEach(vm::toggleSelection)

        var outcome: app.tenshu.photos.ui.screens.feed.BulkOutcome? = null
        vm.addSelectedToAlbum("album-1") { outcome = it }

        assertEquals(3, outcome?.succeeded)
        assertEquals(1, outcome?.failed)
        assertEquals(setOf("/p2.jpg"), vm.selected.value.keys)
    }

    @Test
    fun actingOnAnEmptySelectionDoesNothingRatherThanEverything() = vmTest { vms ->
        // ⚠ The control. A bulk action whose "selected" set is empty must not fall through to the
        // whole library — the failure mode where a stray tap trashes a camera roll.
        val all = photos(4)
        val repository = PartialRepository(all, failing = emptySet())
        val vm = viewModel(vms, repository)

        var outcome: app.tenshu.photos.ui.screens.feed.BulkOutcome? = null
        vm.trashSelected { outcome = it }

        assertEquals(emptyList(), repository.trashed, "nothing was touched")
        assertEquals(0, outcome?.total)
    }

    // ── in flight ────────────────────────────────────────────────────────────────────────────

    /** Writes that wait for [release] — a proxy slow enough for the user to act mid-flight. */
    private class GatedRepository(
        photos: List<Photo>,
        private val failing: Set<String> = emptySet(),
        private val delegate: app.tenshu.photos.data.PhotosRepository = MockPhotosRepository(photos = photos),
    ) : app.tenshu.photos.data.PhotosRepository by delegate {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val trashed = mutableListOf<String>()
        override suspend fun trash(photo: Photo): Boolean {
            release.await()
            trashed += photo.path
            return photo.path !in failing
        }
    }

    @Test
    fun aSecondActionWhileOneRunsIsDropped_notRacedAgainstIt() = vmTest { vms ->
        val all = photos(3)
        val repository = GatedRepository(all)
        val vm = viewModel(vms, repository)
        all.forEach(vm::toggleSelection)

        var reports = 0
        vm.trashSelected { reports++ }
        assertTrue(vm.acting.value, "the first is in flight")
        vm.trashSelected { reports++ }
        repository.release.complete(Unit)

        assertEquals(listOf("/p0.jpg", "/p1.jpg", "/p2.jpg"), repository.trashed, "each photograph moved ONCE")
        assertEquals(1, reports)
        assertTrue(!vm.acting.value)
    }

    @Test
    fun cancellingDuringTheWritesStaysCancelled_evenForTheFailures() = vmTest { vms ->
        val all = photos(3)
        val repository = GatedRepository(all, failing = setOf("/p1.jpg"))
        val vm = viewModel(vms, repository)
        all.forEach(vm::toggleSelection)

        vm.trashSelected()
        vm.clearSelection()
        repository.release.complete(Unit)

        assertEquals(emptySet(), vm.selected.value.keys, "an assignment put the failure back after Cancel")
    }

    @Test
    fun aPhotographSelectedDuringTheWritesStaysSelected() = vmTest { vms ->
        val all = photos(4)
        val repository = GatedRepository(all, failing = setOf("/p1.jpg"))
        val vm = viewModel(vms, repository)
        listOf(all[0], all[1]).forEach(vm::toggleSelection)

        vm.trashSelected()
        vm.toggleSelection(all[3])
        repository.release.complete(Unit)

        assertEquals(setOf("/p1.jpg", "/p3.jpg"), vm.selected.value.keys, "the failure, and the new pick")
    }

    @Test
    fun aSnapshotPhotographPickedAgainDuringTheWritesStaysSelected() = vmTest { vms ->
        // ⚠ Deselected and picked again mid-flight: the user's newer choice, which subtracting the
        // successes must not undo.
        val all = photos(3)
        val repository = GatedRepository(all)
        val vm = viewModel(vms, repository)
        listOf(all[0], all[1]).forEach(vm::toggleSelection)

        vm.trashSelected()
        vm.clearSelection()
        vm.toggleSelection(all[0])
        repository.release.complete(Unit)

        assertEquals(setOf("/p0.jpg"), vm.selected.value.keys, "the latest intent wins")
    }

    @Test
    fun aReportAfterCancelDoesNotPromiseAStillSelectedRetry() = vmTest { vms ->
        // ⚠ Cancel stays enabled while writes run; the failures are then NOT selected, and the report
        // must not say they are.
        val all = photos(3)
        val repository = GatedRepository(all, failing = setOf("/p1.jpg"))
        val vm = viewModel(vms, repository)
        all.forEach(vm::toggleSelection)

        var outcome: app.tenshu.photos.ui.screens.feed.BulkOutcome? = null
        vm.trashSelected { outcome = it }
        vm.clearSelection()
        repository.release.complete(Unit)

        assertEquals(0, outcome?.failedStillSelected)
        assertEquals("2 moved to the trash. 1 could not be.", outcome?.message)
    }

    // ── what the report says (shared by both shells) ─────────────────────────────────────────

    private fun trash(succeeded: Int, failed: Int) =
        app.tenshu.photos.ui.screens.feed.BulkOutcome(app.tenshu.photos.ui.screens.feed.BulkAction.Trash, succeeded, failed)

    private fun album(succeeded: Int, failed: Int) =
        app.tenshu.photos.ui.screens.feed.BulkOutcome(app.tenshu.photos.ui.screens.feed.BulkAction.AddToAlbum, succeeded, failed)

    @Test
    fun anAllSucceededReportSaysWhatHappenedWithTheCount() {
        assertEquals("Done", trash(4, 0).title)
        assertEquals("4 photos moved to the trash.", trash(4, 0).message)
        assertEquals("4 photos added to the album.", album(4, 0).message)
    }

    @Test
    fun oneSucceededReadsOnePhoto() {
        assertEquals("1 photo moved to the trash.", trash(1, 0).message)
        assertEquals("1 photo added to the album.", album(1, 0).message)
    }

    @Test
    fun aPartialReportNamesBothNumbersAndWhatIsStillSelected() {
        assertEquals("Some photos could not be moved to the trash", trash(7, 3).title)
        assertEquals(
            "7 moved to the trash. 3 could not be, and are still selected so you can try again.",
            trash(7, 3).message,
        )
    }

    @Test
    fun anAlbumFailureNeverSaysMoved() {
        // ⚠ Adding to an album moves nothing.
        assertEquals("Some photos could not be added to the album", album(3, 1).title)
        assertTrue("moved" !in album(3, 1).message)
    }

    @Test
    fun anAllFailedReportNeverReadsAsDone() {
        assertEquals("Some photos could not be moved to the trash", trash(0, 5).title)
        assertEquals("None of the 5 photos could be moved to the trash.", trash(0, 5).message)
    }

    @Test
    fun oneOfEachReadsInTheSingular() {
        // ⚠ Found on the iOS pass: "1 could not be, and are still selected".
        assertEquals(
            "3 moved to the trash. 1 could not be, and is still selected so you can try again.",
            trash(3, 1).message,
        )
        assertEquals("1 photo moved to the trash.", trash(1, 0).message)
        assertEquals("The photo could not be moved to the trash.", trash(0, 1).message)
    }
}
