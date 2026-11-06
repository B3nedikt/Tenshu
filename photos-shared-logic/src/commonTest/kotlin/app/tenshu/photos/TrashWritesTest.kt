package app.tenshu.photos

import androidx.lifecycle.viewModelScope
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.data.TrashResult
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.trash.TrashCopy
import app.tenshu.photos.ui.screens.trash.TrashViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * The trash's writes and what they report (`photos-ios.md` §8.17).
 *
 * ⚠⚠ A dropped result makes a failed restore or delete look like one that worked — the `/Trash`
 * incident's shape (`photos-app.md` §22). Each test here pins one answer reaching the user.
 */
class TrashWritesTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(i: Int) = Photo("/Backup/Phone/p$i.jpg", 1_000L - i, isVideo = false, contentId = "c$i")

    /** A trash whose writes can refuse, hang ([gate]) or have their reads held ([readGates]). */
    private class Trash(val delegate: MockPhotosRepository = MockPhotosRepository()) : PhotosRepository by delegate {
        val refusing = mutableSetOf<String>()

        /** Writes that HAPPEN and still answer `false` — the real ops' half-done shapes. */
        val halfDone = mutableSetOf<String>()
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        val readGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        private var reads = 0

        /** The N-th read waits on a gate that IGNORES cancellation — a Binder read. */
        val uninterruptible = mutableMapOf<Int, CompletableDeferred<Unit>>()

        override suspend fun trashed(): TrashResult {
            val n = reads++
            val snapshot = delegate.trashed()
            readGates[n]?.await()
            uninterruptible[n]?.let { gate ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            }
            return snapshot
        }

        private suspend fun write(name: String, contentId: String, real: suspend () -> Boolean): Boolean {
            calls += "$name:$contentId"
            gate?.await()
            if (contentId in halfDone) return real().let { false }
            return contentId !in refusing && real()
        }

        override suspend fun restore(contentId: String) = write("restore", contentId) { delegate.restore(contentId) }

        override suspend fun deleteForever(contentId: String) =
            write("delete", contentId) { delegate.deleteForever(contentId) }
    }

    private suspend fun Trash.holding(vararg photos: Photo) = apply { photos.forEach { delegate.trash(it) } }

    @Test
    fun aRefusedRestoreIsSaid_andThePhotoStays() = vmTest(dispatcher) { vms ->
        val repo = Trash().holding(photo(1))
        repo.refusing += "c1"
        var changed = 0
        val vm = vms.own(TrashViewModel(repo, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()

        vm.restore("c1")
        testScheduler.advanceUntilIdle()
        assertEquals("Couldn't Restore the Photo", vm.state.value.report?.title)
        assertEquals(listOf("c1"), vm.state.value.items.map { it.contentId }, "still in the trash")
        // ⚠ Told anyway: a NO may have half-happened, and a re-read claims nothing.
        assertEquals(1, changed, "Collections re-reads after a NO too")

        vm.dismissReport()
        assertNull(vm.state.value.report)
    }

    @Test
    fun aRestoredPhotoLeavesTheGridAtOnce_andASecondTapWritesNothing() = vmTest(dispatcher) { vms ->
        // ⚠ As for the album removal: a tile still showing after its write answered could be acted
        // on again, and the second write would report a refusal of one that worked.
        val repo = Trash().holding(photo(1), photo(2))
        var changed = 0
        val vm = vms.own(TrashViewModel(repo, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()

        // #1, the re-read after the restore, is slow (the Trash asks no pre-check — see `write`).
        repo.readGates[1] = CompletableDeferred()
        vm.restore("c1")
        testScheduler.runCurrent()
        assertFalse(vm.state.value.items.any { it.contentId == "c1" }, "off the grid before the re-read lands")
        assertEquals(1, changed)

        vm.restore("c1")
        testScheduler.runCurrent()
        assertEquals(listOf("restore:c1"), repo.calls, "a tap on a photo no longer there writes nothing")
        assertNull(vm.state.value.report)
    }

    @Test
    fun theWritesAreSingleFlight() = vmTest(dispatcher) { vms ->
        val repo = Trash().holding(photo(1), photo(2))
        val vm = vms.own(TrashViewModel(repo))
        testScheduler.advanceUntilIdle()

        val gate = CompletableDeferred<Unit>().also { repo.gate = it }
        vm.restore("c1")
        testScheduler.runCurrent()
        assertTrue(vm.state.value.writing)
        assertFalse(vm.state.value.canEmpty, "Empty waits too")

        vm.deleteForever("c2")
        vm.emptyTrash()
        testScheduler.runCurrent()
        assertEquals(listOf("restore:c1"), repo.calls)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.writing)
    }

    @Test
    fun anEmptyThatLeavesSomeBehindSaysHowMany() = vmTest(dispatcher) { vms ->
        // ⚠⚠ "Emptied" over a trash still holding photographs is the §22 shape again.
        val repo = Trash().holding(photo(1), photo(2), photo(3))
        repo.refusing += "c2"
        var changed = 0
        val vm = vms.own(TrashViewModel(repo, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()

        vm.emptyTrash()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("c2"), vm.state.value.items.map { it.contentId }, "the refused one stays")
        assertEquals("The Trash Wasn't Emptied", vm.state.value.report?.title)
        // ⚠ An outcome, never a location: a refused delete may have half-happened.
        assertTrue(vm.state.value.report!!.message.startsWith("1 photo of 3 couldn't be deleted."), vm.state.value.report!!.message)
        assertFalse("still in the trash" in vm.state.value.report!!.message)
        assertEquals(1, changed, "two went, so the library elsewhere re-reads")
    }

    @Test
    fun anUnansweredDeleteIsUnconfirmed_holdsTheLock_andIsLetGoOfInTheEnd() = vmTest(dispatcher) { vms ->
        val repo = Trash().holding(photo(1), photo(2))
        val vm = vms.own(TrashViewModel(repo, timeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        repo.gate = CompletableDeferred()                   // never answers
        vm.deleteForever("c1")
        testScheduler.advanceTimeBy(1_500)
        testScheduler.runCurrent()
        assertEquals("Still Waiting for Tenshu Control", vm.state.value.report?.title, "never worded as a refusal")
        // ⚠ And no promise the screen cannot keep: an abandoned write is watched by nothing (§8.16 note 16).
        assertFalse("will update" in vm.state.value.report!!.message)
        assertTrue(vm.state.value.writing, "nothing runs beside a write that can still land")

        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.writing, "abandoned after the settle window")
        repo.gate = null
        vm.restore("c2")
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.items.any { it.contentId == "c2" }, "and the next write runs")
    }

    @Test
    fun aNoIsReported_withoutClaimingWhereThePhotoIs_andTheGridFollowsTheStore() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Both ways round: `restore` can move the file and still answer false — so "it's still
        // in the trash" can be wrong — but the listing cannot prove it happened either (it drops
        // rows whose read failed). So the NO is reported without a location, and the grid and
        // Collections re-read the store, which shows where the photo actually is.
        val repo = Trash().holding(photo(1))
        repo.halfDone += "c1"
        var changed = 0
        val vm = vms.own(TrashViewModel(repo, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()

        vm.restore("c1")
        testScheduler.advanceUntilIdle()
        assertEquals("Couldn't Restore the Photo", vm.state.value.report?.title, "the write's own answer stands")
        assertFalse("still in the trash." in vm.state.value.report!!.message.substringBefore("If"), "no location claimed")
        assertTrue(vm.state.value.items.isEmpty(), "the grid follows the store")
        assertEquals(1, changed, "and Collections re-reads")
    }

    @Test
    fun aListingThatOmitsAnItemNeverSkipsItsWrite() = vmTest(dispatcher) { vms ->
        // ⚠⚠ "Not listed" — possibly a transient read failure — is no pre-check: it would skip the
        // write while the tile went away.
        val repo = Trash().holding(photo(1))
        val vm = vms.own(TrashViewModel(repo))
        testScheduler.advanceUntilIdle()
        repo.delegate.restore("c1")                         // the next listing will not show it
        vm.deleteForever("c1")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("delete:c1"), repo.calls, "the write was sent, not inferred")
    }

    @Test
    fun aStuckRefreshReadKeepsNoJobOfTheViewModelAlive() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Were the refresh's read a structured child, a Binder call stuck in it would keep the
        // view model's job alive past the timeout, once per retry.
        val repo = Trash().holding(photo(1))
        val stuck = CompletableDeferred<Unit>().also { repo.uninterruptible[0] = it }
        val vm = vms.own(TrashViewModel(repo, timeoutMs = 1_000))
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.error != null, "the read timed out")
        assertTrue(
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.children.none { it.isActive },
            "no job of the view model's still waits on the stuck read",
        )
        stuck.complete(Unit)
    }

    @Test
    fun anEmptyStopsAtTheFirstUnansweredDelete() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Against a hung transport, each further item would hold the user — Back disabled — for
        // two more windows (ten photos, ~40 minutes) and leave one more write that may still
        // commit.
        val repo = Trash().holding(photo(1), photo(2), photo(3))
        var changed = 0
        val vm = vms.own(TrashViewModel(repo, timeoutMs = 1_000, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()

        repo.gate = CompletableDeferred()                   // nothing answers
        vm.emptyTrash()
        testScheduler.advanceUntilIdle()
        assertEquals(1, repo.calls.size, "stopped after the first unanswered delete: ${repo.calls}")
        assertFalse(vm.state.value.writing)
        val message = vm.state.value.report!!.message
        assertTrue("2 photos weren't tried" in message, message)
        assertEquals(1, changed, "the one that may have landed — Collections re-reads")
    }

    @Test
    fun anEmptyThatStallsSaysItIsStillWaiting_andGoesOnWhenTheAnswerComes() = vmTest(dispatcher) { vms ->
        // ⚠ Empty must keep the first bound's signal, or a stalled one holds the screen — Back
        // disabled — through the whole settle window with nothing said.
        val repo = Trash().holding(photo(1), photo(2))
        val vm = vms.own(TrashViewModel(repo, timeoutMs = 1_000, onChanged = {}))
        testScheduler.advanceUntilIdle()

        val slow = CompletableDeferred<Unit>().also { repo.gate = it }
        vm.emptyTrash()
        testScheduler.advanceTimeBy(1_500)                  // past the first bound, inside the settle window
        testScheduler.runCurrent()
        assertTrue(vm.state.value.writing, "the lock is still held")
        assertEquals("Still Waiting for Tenshu Control", vm.state.value.report?.title)

        repo.gate = null
        slow.complete(Unit)                                 // the late answer: a yes
        testScheduler.advanceUntilIdle()
        assertEquals(2, repo.calls.size, "Empty went on to the next photograph: ${repo.calls}")
        assertEquals(emptyList(), vm.state.value.items.map { it.contentId })
        assertEquals(null, vm.state.value.report, "nothing left to say — both went")
    }

    @Test
    fun anUnconfirmedRestoreTellsCollectionsToo() = vmTest(dispatcher) { vms ->
        // ⚠ An unanswered restore may have committed, so Collections re-reads too, not only the
        // Trash.
        val repo = Trash().holding(photo(1))
        var changed = 0
        val vm = vms.own(TrashViewModel(repo, timeoutMs = 1_000, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()

        repo.gate = CompletableDeferred()
        vm.restore("c1")
        testScheduler.advanceUntilIdle()
        assertTrue("c1" in vm.state.value.uncertain, "precondition: abandoned")
        assertEquals(1, changed)
    }

    @Test
    fun aLateSuccessTakesDownTheStillWaitingReport() = vmTest(dispatcher) { vms ->
        // ⚠ No uncertainty alert left up over a write known to have happened.
        val repo = Trash().holding(photo(1))
        val vm = vms.own(TrashViewModel(repo, timeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        val slow = CompletableDeferred<Unit>().also { repo.gate = it }
        vm.restore("c1")
        testScheduler.advanceTimeBy(1_500)
        testScheduler.runCurrent()
        assertEquals("Still Waiting for Tenshu Control", vm.state.value.report?.title, "precondition")

        slow.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertNull(vm.state.value.report, "settled — nothing left to wait for")
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun anItemWhoseWriteWasAbandonedIsLocked_untilATrashReadShowsItSettled() = vmTest(dispatcher) { vms ->
        // ⚠⚠ After both windows the item stays locked, or a restore could race the unanswered
        // delete still able to land.
        val repo = Trash().holding(photo(1), photo(2))
        val vm = vms.own(TrashViewModel(repo, timeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        repo.gate = CompletableDeferred()                   // the delete never answers
        vm.deleteForever("c1")
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.writing, "abandoned")
        assertTrue("c1" in vm.state.value.uncertain)
        assertFalse(vm.state.value.canAct("c1"))
        // ⚠ Empty skips c1, so its confirmation must count one photograph, not two.
        assertEquals(1, vm.state.value.emptyCount, "Empty's count is what it will delete, not every tile")

        repo.gate = null
        vm.restore("c1")
        vm.emptyTrash()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("delete:c1", "delete:c2"), repo.calls, "no write on c1 again — Empty skipped it too")

        // ⚠⚠ A listing that omits the item is not proof — the real `trashed()` drops a
        // row whose own read failed — so the lock outlives any read, and the tile comes back locked.
        repo.delegate.deleteForever("c1")
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue("c1" in vm.state.value.uncertain, "still locked though this read omitted it")
    }

    @Test
    fun aReadNeverClearsAReportOrAWriteInFlight() = vmTest(dispatcher) { vms ->
        // ⚠ A read publishes only its own fields (as on the album screen).
        val repo = Trash().holding(photo(1))
        repo.refusing += "c1"
        val vm = vms.own(TrashViewModel(repo))
        testScheduler.advanceUntilIdle()
        vm.restore("c1")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.report != null, "precondition")

        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.report != null, "the re-read left the report alone")
    }

    @Test
    fun theFirstReadMayRunInsideTheConstructor() {
        // ⚠⚠ The album crash (§8.16): on K/N `viewModelScope` is `Main.immediate`.
        Dispatchers.resetMain()
        val eager = UnconfinedTestDispatcher()
        Dispatchers.setMain(eager)
        vmTest(eager) { vms ->
            val repo = Trash().holding(photo(1))
            val vm = vms.own(TrashViewModel(repo))
            assertEquals(listOf("c1"), vm.state.value.items.map { it.contentId })
        }
    }

    @Test
    fun theEmptyConfirmationCountsProperly() {
        assertEquals(
            "1 photo will be deleted for good. This can't be undone — it's removed from all your devices.",
            TrashCopy.emptyMessage(1),
        )
        assertTrue(TrashCopy.emptyMessage(3).startsWith("3 photos will be deleted for good."))
    }
}
