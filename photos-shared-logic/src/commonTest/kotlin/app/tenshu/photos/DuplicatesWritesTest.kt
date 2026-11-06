package app.tenshu.photos

import androidx.lifecycle.viewModelScope
import app.tenshu.photos.data.DuplicateGroup
import app.tenshu.photos.data.DuplicatesResult
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.data.TrashResult
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.duplicates.DuplicatesCopy
import app.tenshu.photos.ui.screens.duplicates.DuplicatesUiState
import app.tenshu.photos.ui.screens.duplicates.DuplicatesViewModel
import app.tenshu.photos.ui.screens.feed.FeedError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * Duplicates' writes and reads, and what they report (`photos-ios.md` §8.18).
 *
 * ⚠⚠ A dropped write result makes a refused trash or delete look like one that worked, and an
 * unbounded wait leaves the screen locked, or on its spinner, for good after a lost reply. Each
 * test pins one answer reaching the user, or one rule shared with the Trash (§8.17).
 */
class DuplicatesWritesTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(i: Int, hash: String, contentId: String = "c$i") = Photo(
        path = "/Backup/Phone/p$i.jpg",
        capturedAtMs = 1_000L + i,                          // oldest first within a set: p1, p2, …
        isVideo = false,
        contentId = contentId,
        contentHash = hash,
    )

    /** A library whose writes can refuse, hang ([gate]) or half-happen, and whose reads can be held or fail. */
    private class Library(val delegate: MockPhotosRepository) : PhotosRepository by delegate {
        constructor(vararg photos: Photo) : this(MockPhotosRepository(photos.toList()))

        /** Names ("p2") whose write answers NO and does nothing. */
        val refusing = mutableSetOf<String>()

        /** Names whose write HAPPENS and still answers NO — `trash` moves, then fails to record. */
        val halfDone = mutableSetOf<String>()
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        val readGates = mutableMapOf<Int, CompletableDeferred<Unit>>()

        /** The N-th read waits on a gate that IGNORES cancellation — a Binder read. */
        val uninterruptible = mutableMapOf<Int, CompletableDeferred<Unit>>()

        /** The N-th read answers this instead of the library. */
        val readAnswers = mutableMapOf<Int, DuplicatesResult>()
        var reads = 0
            private set

        /** The N-th read THROWS this — a typed error from a dropped connection, not a failed result. */
        val readThrows = mutableMapOf<Int, Throwable>()

        /** Names whose write THROWS instead of answering. */
        val throwing = mutableSetOf<String>()

        /** What the survivor check throws instead of answering. */
        var checkThrows: Throwable? = null

        override suspend fun duplicates(): DuplicatesResult {
            val n = reads++
            readThrows[n]?.let { throw it }
            val snapshot = readAnswers[n] ?: delegate.duplicates()
            readGates[n]?.await()
            uninterruptible[n]?.let { gate -> withContext(NonCancellable) { gate.await() } }
            return snapshot
        }

        private suspend fun write(kind: String, photo: Photo, real: suspend () -> Boolean): Boolean {
            val name = photo.name.substringBefore('.')
            calls += "$kind:$name"
            gate?.await()
            if (name in throwing) throw IllegalStateException("Control went away during $kind:$name")
            if (name in halfDone) return real().let { false }
            return name !in refusing && real()
        }

        override suspend fun trash(photo: Photo) = write("trash", photo) { delegate.trash(photo) }

        override suspend fun deletePermanently(photo: Photo) = write("delete", photo) { delegate.deletePermanently(photo) }

        /** Every survivor check, by name; [checkGate] holds one — ignoring cancellation, as a Binder read does. */
        val checks = mutableListOf<String>()
        var checkGate: CompletableDeferred<Unit>? = null

        override suspend fun holdsContent(path: String, contentHash: String): Boolean {
            checks += path.substringAfterLast('/').substringBefore('.')
            checkGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
            checkThrows?.let { throw it }
            return delegate.holdsContent(path, contentHash)
        }

        suspend fun trashedIds() = (delegate.trashed() as TrashResult.Items).items.map { it.contentId }.toSet()
    }

    private fun DuplicatesUiState.group(hash: String): DuplicateGroup = groups.first { it.contentHash == hash }

    private fun DuplicatesUiState.names(hash: String): List<String> =
        groups.firstOrNull { it.contentHash == hash }?.photos.orEmpty().map { it.name.substringBefore('.') }

    /** The confirmation a tap on [keep] in the set [hash] brings up — what a shell hands back. */
    private fun DuplicatesViewModel.confirm(hash: String, keep: String) =
        state.value.let { s -> s.confirmation(s.group(hash), s.group(hash).photos.first { it.name == "$keep.jpg" }) }

    // ── every result is said ─────────────────────────────────────────────────────────────────

    @Test
    fun aRefusedTrashIsSaid_theCopyStays_andCollectionsIsToldAnyway() = vmTest(dispatcher) { vms ->
        val repo = Library(photo(1, "h1"), photo(2, "h1"))
        repo.refusing += "p2"
        var changed = 0
        val vm = vms.own(DuplicatesViewModel(repo, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()

        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.advanceUntilIdle()

        val report = assertNotNull(vm.state.value.report, "a refusal that is not said looks like a success")
        assertEquals("Couldn't Move Every Copy to the Trash", report.title)
        assertTrue(report.message.startsWith("1 copy of 1 couldn't be moved to the trash."), report.message)
        assertEquals(listOf("p1", "p2"), vm.state.value.names("h1"), "the store still holds both")
        // ⚠ A NO may have half-happened, so the library elsewhere re-reads after one too.
        assertEquals(1, changed)
        assertFalse("try again" in report.message, "the copy is locked here — the advice is to reopen: ${report.message}")

        // ⚠⚠ A NO is not "still there". The refused copy is locked — and with it
        // this set has no copy left that may be chosen, which the row says instead of "tap".
        val state = vm.state.value
        val group = state.group("h1")
        assertTrue(group.photos[1].path in state.uncertain)
        assertTrue(group.photos.none { state.canKeep(group, it) }, "nothing destructive is offered on this set")
        assertFalse("tap" in state.heading(group), state.heading(group))
        // ⚠ And no read unlocks it: a NO from a dropped transport can come before the write commits.
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(group.photos[1].path in vm.state.value.uncertain, "still locked after a re-read that lists it")

        vm.dismissReport()
        assertNull(vm.state.value.report)
    }

    @Test
    fun aRefusedPermanentDeleteSaysDelete_notTrash() = vmTest(dispatcher) { vms ->
        val repo = Library(photo(1, "h1"), photo(2, "h1"), photo(3, "h1"))
        repo.refusing += "p3"
        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()

        vm.setDeletePermanently(true)
        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("delete:p2", "delete:p3"), repo.calls, "a refusal does not stop the loop — only silence does")
        assertEquals("Couldn't Delete Every Copy", vm.state.value.report?.title)
        assertTrue(vm.state.value.report!!.message.startsWith("1 copy of 2 couldn't be deleted."), vm.state.value.report!!.message)
        assertEquals(listOf("p1", "p3"), vm.state.value.names("h1"))
        // ⚠ A refused DELETE is locked too: a delete can be refused having deleted.
        val state = vm.state.value
        assertFalse(state.canKeep(state.group("h1"), state.group("h1").photos.first { it.name == "p3.jpg" }))
        vm.dismissReport()
        vm.confirm("h1", "p1")?.let { vm.keepOnly(it) }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("delete:p2", "delete:p3"), repo.calls, "and never written again")
    }

    // ── a NO is not "still there", and the survivor is checked against the store ───────────────

    @Test
    fun aCopyWhoseTrashSaidNoAfterMovingIt_isNeverOfferedAsTheSurvivor() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The exact sequence: `trash` MOVES p2 and then answers NO (the record failed). The
        // re-read is slow, so p2's tile is still on screen when the lock comes off. The user
        // dismisses the report, turns on "Delete permanently" and picks p2 — already in the trash —
        // as the copy to keep: p1, the last live copy, would be deleted for good.
        val repo = Library(photo(1, "h1"), photo(2, "h1"))
        repo.halfDone += "p2"
        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()

        repo.readGates[1] = CompletableDeferred()           // the re-read after the write: slow
        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.runCurrent()
        assertFalse(vm.state.value.writing, "precondition: the write answered, the lock is off")
        assertEquals(listOf("p1", "p2"), vm.state.value.names("h1"), "precondition: the stale tile is still listed")
        assertEquals(setOf("c2"), repo.trashedIds(), "precondition: p2 really moved")

        vm.dismissReport()
        vm.setDeletePermanently(true)
        val asSurvivor = vm.confirm("h1", "p2")
        assertNull(asSurvivor, "the moved copy must not be offered as the one to keep")
        asSurvivor?.let { vm.keepOnly(it) }
        testScheduler.runCurrent()

        assertEquals(listOf("trash:p2"), repo.calls, "nothing may be deleted")
        assertTrue(repo.delegate.purged.isEmpty(), "the last live copy is still in the library")
    }

    @Test
    fun nothingIsRemovedUnlessTheStoreStillHoldsTheSurvivor() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The last line of defence. The list is what a read saw earlier: here another device took
        // the copy the user is about to KEEP, and no re-read has shown it. Deleting "the others" for
        // good would delete the last copy the library holds.
        val keeper = photo(1, "h1")
        val repo = Library(keeper, photo(2, "h1"))
        var changed = 0
        val vm = vms.own(DuplicatesViewModel(repo, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()
        vm.setDeletePermanently(true)
        val confirmed = vm.confirm("h1", "p1")!!

        repo.delegate.trash(keeper)                         // gone from the library; the screen has not re-read
        vm.keepOnly(confirmed)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("p1"), repo.checks, "the store was asked about the survivor")
        assertTrue(repo.calls.isEmpty(), "nothing written: ${repo.calls}")
        assertTrue(repo.delegate.purged.isEmpty())
        assertEquals("Couldn't Check the Copy to Keep", vm.state.value.report?.title)
        assertTrue("p1.jpg" in vm.state.value.report!!.message && "nothing was removed" in vm.state.value.report!!.message)
        assertFalse(vm.state.value.writing)
        assertEquals(0, changed, "nothing was sent, so nothing changed")
        assertTrue(vm.state.value.groups.isEmpty(), "and the list re-read: one live copy is not a duplicate")
    }

    @Test
    fun aSurvivorCheckThatNeverAnswersRemovesNothing_andReleasesTheLock() = vmTest(dispatcher) { vms ->
        // ⚠ "Could not look" is never "it is there" — and a Binder read that ignores cancellation
        // must not hold the screen past its bound.
        val repo = Library(photo(1, "h1"), photo(2, "h1"))
        val vm = vms.own(DuplicatesViewModel(repo, timeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        val stuck = CompletableDeferred<Unit>().also { repo.checkGate = it }
        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.runCurrent()
        assertTrue(vm.state.value.writing, "held while the store is asked")
        testScheduler.advanceUntilIdle()

        assertTrue(repo.calls.isEmpty(), "nothing written: ${repo.calls}")
        assertFalse(vm.state.value.writing, "released at the bound")
        assertEquals("Couldn't Check the Copy to Keep", vm.state.value.report?.title)
        stuck.complete(Unit)
    }

    // ── a repository call that THROWS ─────────────────────────────────────────────────────────
    //
    // ⚠ Each of these would also fail as an uncaught exception: `vmTest`'s scope reports one thrown
    // out of a `viewModelScope.launch` — on a phone that is the crash.

    @Test
    fun aSurvivorCheckThatThrowsRemovesNothing_saysSo_andReleasesTheLock() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Control gone mid-read arrives as a THROW, not as `false`. Uncaught, it leaves
        // `writing` set for good — Back disabled in both shells, nothing said — or takes the app
        // down.
        val repo = Library(photo(1, "h1"), photo(2, "h1"))
        var changed = 0
        val vm = vms.own(DuplicatesViewModel(repo, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()
        vm.setDeletePermanently(true)

        repo.checkThrows = IllegalStateException("Control went away")
        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("p1"), repo.checks, "precondition: the check ran, and threw")
        assertTrue(repo.calls.isEmpty(), "could not look is never it-is-there: ${repo.calls}")
        assertFalse(vm.state.value.writing, "the lock came off")
        assertTrue(vm.state.value.canLeave)
        assertEquals("Couldn't Check the Copy to Keep", vm.state.value.report?.title)
        assertEquals(0, changed, "nothing was sent")
    }

    @Test
    fun aWriteThatThrowsIsCountedAndLocked_theLoopGoesOn_andTheLockComesOff() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A throw is a NO whose copy may be anywhere: locked like a refusal, said like one.
        val repo = Library(photo(1, "h1"), photo(2, "h1"), photo(3, "h1"))
        repo.throwing += "p2"
        var changed = 0
        val vm = vms.own(DuplicatesViewModel(repo, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()
        vm.setDeletePermanently(true)

        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf("delete:p2", "delete:p3"), repo.calls, "the transport answered — the loop goes on")
        assertFalse(state.writing, "the lock came off")
        assertEquals("Couldn't Delete Every Copy", state.report?.title)
        assertTrue(state.report!!.message.startsWith("1 copy of 2 couldn't be deleted."), state.report!!.message)
        assertEquals(listOf("p1", "p2"), state.names("h1"))
        assertTrue(state.group("h1").photos.first { it.name == "p2.jpg" }.path in state.uncertain, "where it is, is not known")
        assertEquals(1, changed, "it may have happened — Collections re-reads")
    }

    @Test
    fun aReReadThatThrowsIsCouldNotLook_withARetry() = vmTest(dispatcher) { vms ->
        val repo = Library(photo(1, "h1"), photo(2, "h1"), photo(3, "h2"), photo(4, "h2"))
        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()

        repo.readThrows[1] = IllegalStateException("Control went away")
        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.writing)
        assertTrue(state.error is FeedError.Failed, "could not look: ${state.error}")
        assertEquals(true, state.status?.canRetry)
        assertTrue(state.groups.none { group -> group.photos.any { state.canKeep(group, it) } }, "nothing offered over it")

        vm.retry()
        testScheduler.advanceUntilIdle()
        assertNull(vm.state.value.error, "and the retry reads again")
        assertEquals(listOf("h2"), vm.state.value.groups.map { it.contentHash })
    }

    @Test
    fun aClearedScreenStopsTheLoop_cancellationIsNotARefusal() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The guard around a write must not eat the cancellation: read as a refusal, the loop
        // would run on over a screen that is gone — every remaining copy counted refused, and reported.
        val repo = Library(photo(1, "h1"), photo(2, "h1"), photo(3, "h1"))
        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()
        vm.setDeletePermanently(true)

        val held = CompletableDeferred<Unit>().also { repo.gate = it }
        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.runCurrent()
        assertEquals(listOf("delete:p2"), repo.calls, "precondition: the first write is out")

        vm.viewModelScope.cancel()
        testScheduler.advanceUntilIdle()
        held.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("delete:p2"), repo.calls, "no further copy was sent")
        assertNull(vm.state.value.report, "and a cancellation is not reported as a refusal")
    }

    @Test
    fun aScreenClearedDuringTheSurvivorCheckWritesAndReportsNothing() = vmTest(dispatcher) { vms ->
        val repo = Library(photo(1, "h1"), photo(2, "h1"))
        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()

        val held = CompletableDeferred<Unit>().also { repo.checkGate = it }
        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.runCurrent()
        assertEquals(listOf("p1"), repo.checks, "precondition: the check is out")

        vm.viewModelScope.cancel()
        testScheduler.advanceUntilIdle()
        held.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertTrue(repo.calls.isEmpty(), "nothing written: ${repo.calls}")
        assertNull(vm.state.value.report, "a cancellation is not \"couldn't check\"")
    }

    @Test
    fun aNoThatHalfHappenedIsReported_withoutSayingWhereTheCopyIs_andTheListFollowsTheStore() = vmTest(dispatcher) { vms ->
        // ⚠⚠ `trash` moves the file and can then fail to record it: a NO over a copy that is gone.
        val repo = Library(photo(1, "h1"), photo(2, "h1"))
        repo.halfDone += "p2"
        var changed = 0
        val vm = vms.own(DuplicatesViewModel(repo, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()

        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.advanceUntilIdle()

        val message = vm.state.value.report!!.message
        assertFalse("still" in message, "an outcome, never a location: $message")
        assertTrue(vm.state.value.groups.isEmpty(), "the re-read shows what the store holds")
        assertEquals(1, changed, "and Collections re-reads")
    }

    @Test
    fun aCopyWithNoContentIdIsNotSentToTheTrash_andTheReportSaysWhy() = vmTest(dispatcher) { vms ->
        // ⚠ The trash record is keyed on the content id: a copy without one cannot be trashed.
        val repo = Library(photo(1, "h1"), photo(2, "h1", contentId = ""))
        var changed = 0
        val vm = vms.own(DuplicatesViewModel(repo, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()

        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.advanceUntilIdle()

        assertTrue(repo.calls.isEmpty(), "nothing to send: ${repo.calls}")
        assertTrue("can't go to the trash" in vm.state.value.report!!.message, vm.state.value.report!!.message)
        assertFalse("Make sure Tenshu Control is running" in vm.state.value.report!!.message, "Control is not the reason")
        assertEquals(0, changed, "nothing was written, so nothing changed")
    }

    // ── the list follows the writes ──────────────────────────────────────────────────────────

    @Test
    fun aRemovedCopyLeavesTheListAtOnce_andAnOlderReadNeverPutsItBack() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A tile still showing after its write answered could be chosen as the copy to KEEP — a
        // survivor already in the trash. And a read in flight snapshotted the set with it.
        val repo = Library(photo(1, "h1"), photo(2, "h1"), photo(3, "h2"), photo(4, "h2"))
        var changed = 0
        val vm = vms.own(DuplicatesViewModel(repo, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()

        val older = CompletableDeferred<Unit>().also { repo.readGates[1] = it }
        repo.readGates[2] = CompletableDeferred()           // the re-read after the write: held
        vm.refresh()                                        // read #1 snapshots the set WITH p2
        testScheduler.runCurrent()
        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.runCurrent()
        assertEquals(listOf("h2"), vm.state.value.groups.map { it.contentHash }, "off the list before any re-read")
        assertEquals(1, changed)

        older.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf("h2"), vm.state.value.groups.map { it.contentHash }, "the older read did not put it back")
    }

    @Test
    fun aFailedReReadReplacesTheList_andOffersNothingOverIt() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Groups an earlier read saw are not a list to act on once a re-read could not look.
        val repo = Library(photo(1, "h1"), photo(2, "h1"), photo(3, "h2"), photo(4, "h2"))
        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()

        repo.readAnswers[1] = DuplicatesResult.Failed("boom")
        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(DuplicatesCopy.COULD_NOT_LOOK, state.status?.text)
        assertTrue(state.status!!.canRetry)
        assertFalse(state.canKeep(state.group("h2"), state.group("h2").photos.first()), "nothing offered over a stale list")
        assertNull(vm.confirm("h2", "p3"))
    }

    // ── single-flight, and nothing offered meanwhile ─────────────────────────────────────────

    @Test
    fun theWritesAreSingleFlight_andNothingIsOfferedWhileOneRuns() = vmTest(dispatcher) { vms ->
        val repo = Library(photo(1, "h1"), photo(2, "h1"), photo(3, "h2"), photo(4, "h2"))
        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()
        val second = vm.confirm("h2", "p3")!!               // a dialog opened before the first write

        val gate = CompletableDeferred<Unit>().also { repo.gate = it }
        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.runCurrent()
        val writing = vm.state.value
        assertTrue(writing.writing)
        assertFalse(writing.canLeave, "no leaving while a write runs")
        assertFalse(writing.canSetDeletePermanently)
        assertFalse(writing.canKeep(writing.group("h2"), writing.group("h2").photos.first()), "no tile is offered")
        assertNull(vm.confirm("h2", "p3"), "and no confirmation")

        vm.setDeletePermanently(true)
        vm.keepOnly(second)
        testScheduler.runCurrent()
        assertEquals(listOf("trash:p2"), repo.calls, "nothing runs beside it")
        assertFalse(vm.state.value.deletePermanently, "the switch waits too")

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.writing)
        assertTrue(vm.state.value.canLeave)
    }

    // ── bounded, never cancelled, never a refusal ────────────────────────────────────────────

    @Test
    fun anUnansweredWriteSaysStillWaiting_holdsTheLock_stopsTheLoop_andLocksTheCopy() = vmTest(dispatcher) { vms ->
        val repo = Library(photo(1, "h1"), photo(2, "h1"), photo(3, "h1"))
        var changed = 0
        val vm = vms.own(DuplicatesViewModel(repo, timeoutMs = 1_000, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()

        repo.gate = CompletableDeferred()                   // nothing answers
        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.advanceTimeBy(1_500)                  // past the first bound, inside the settle window
        testScheduler.runCurrent()
        assertEquals(DuplicatesCopy.STILL_WAITING, vm.state.value.report, "said at the first bound, inside the loop")
        assertFalse("will update" in vm.state.value.report!!.message, "no promise an abandoned write cannot keep")
        assertTrue(vm.state.value.writing, "nothing runs beside a write that can still land")

        testScheduler.advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.writing, "abandoned after the settle window")
        assertEquals(listOf("trash:p2"), repo.calls, "stopped at the first unanswered write")
        val report = state.report!!
        assertEquals("Still Waiting for Tenshu Control", report.title, "never worded as a refusal")
        assertFalse("couldn't" in report.message, report.message)
        assertTrue("didn't answer for 1 copy" in report.message, report.message)
        assertTrue("1 copy wasn't tried" in report.message, report.message)
        assertEquals(1, changed, "it may have landed — Collections re-reads")

        // ⚠⚠ The copy is locked for the screen's life: never written again, and never the survivor.
        val group = state.group("h1")
        val p2 = group.photos.first { it.name == "p2.jpg" }
        assertTrue(p2.path in state.uncertain)
        assertFalse(state.canKeep(group, p2), "its delete may still land — keeping it could leave no copy")
        val next = vm.confirm("h1", "p3")!!
        assertEquals("1 other copy will move to the trash. You can restore it from there.", next.consequence, "counts what it will do")

        repo.gate = null
        vm.keepOnly(next)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("trash:p2", "trash:p1"), repo.calls, "no second write on the locked copy")

        // ⚠ No read unlocks it — a listing is not proof the write settled (§8.17 note 8).
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(p2.path in vm.state.value.uncertain)
    }

    @Test
    fun aStalledWriteThatAnswersLateTakesDownStillWaiting_andTheLoopGoesOn() = vmTest(dispatcher) { vms ->
        val repo = Library(photo(1, "h1"), photo(2, "h1"), photo(3, "h1"))
        val vm = vms.own(DuplicatesViewModel(repo, timeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        val slow = CompletableDeferred<Unit>().also { repo.gate = it }
        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.advanceTimeBy(1_500)
        testScheduler.runCurrent()
        assertEquals(DuplicatesCopy.STILL_WAITING, vm.state.value.report, "precondition")

        repo.gate = null
        slow.complete(Unit)                                 // the late answer: a yes
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("trash:p2", "trash:p3"), repo.calls, "the loop went on")
        assertNull(vm.state.value.report, "settled — nothing left to say")
        assertTrue(vm.state.value.groups.isEmpty())
        assertTrue(vm.state.value.uncertain.isEmpty())
    }

    // ── what was confirmed is what runs ──────────────────────────────────────────────────────

    @Test
    fun theModeTheUserConfirmedIsTheModeThatRuns() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The dialog said "trash … you can restore": a switch flipped after it must not destroy.
        val repo = Library(photo(1, "h1"), photo(2, "h1"))
        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()

        val confirmed = vm.confirm("h1", "p1")!!
        vm.setDeletePermanently(true)
        vm.keepOnly(confirmed)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("trash:p2"), repo.calls)
        assertTrue(repo.delegate.purged.isEmpty(), "nothing was destroyed")
        assertEquals(setOf("c2"), repo.trashedIds())
    }

    @Test
    fun aSetThatChangedUnderTheConfirmationWritesNothing_andSaysSo() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A re-read can land while the dialog is open. Here the copy the user chose to KEEP went
        // meanwhile (another device) — removing the "others" would leave no copy at all.
        val keeper = photo(1, "h1")
        val repo = Library(keeper, photo(2, "h1"), photo(3, "h1"))
        var changed = 0
        val vm = vms.own(DuplicatesViewModel(repo, onChanged = { changed++ }))
        testScheduler.advanceUntilIdle()

        val confirmed = vm.confirm("h1", "p1")!!
        repo.delegate.trash(keeper)
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("p2", "p3"), vm.state.value.names("h1"), "precondition: the survivor is gone")

        vm.keepOnly(confirmed)
        testScheduler.advanceUntilIdle()
        assertTrue(repo.calls.isEmpty(), "nothing written: ${repo.calls}")
        assertEquals(DuplicatesCopy.CHANGED, vm.state.value.report)
        assertEquals(0, changed)
    }

    // ── reads ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun couldNotLookIsNotNoDuplicates_andARetryLooksAgain() = vmTest(dispatcher) { vms ->
        val repo = Library(photo(1, "h1"), photo(2, "h1"))
        repo.readAnswers[0] = DuplicatesResult.Failed("boom")
        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()

        val failed = vm.state.value
        assertFalse(failed.isEmpty, "a failed read is never an empty library")
        assertEquals(DuplicatesCopy.COULD_NOT_LOOK, failed.status?.text)
        assertTrue(failed.status!!.canRetry)
        assertFalse(failed.status!!.text.startsWith("No duplicates"))

        repo.readGates[1] = CompletableDeferred<Unit>().also { it.complete(Unit) }
        vm.retry()
        assertTrue(vm.state.value.loading, "the retry is visible")
        assertNull(vm.state.value.status)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("p1", "p2"), vm.state.value.names("h1"))
        assertNull(vm.state.value.status)
    }

    @Test
    fun anUnreachableControlSaysSo_withARetry_andAnEmptyLibrarySaysWhatWasCompared() = vmTest(dispatcher) { vms ->
        val repo = Library(photo(1, "h1"), photo(2, "h2"))
        repo.readAnswers[0] = DuplicatesResult.Unreachable
        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()
        assertEquals(FeedError.Unreachable, vm.state.value.error)
        assertEquals(DuplicatesCopy.UNREACHABLE, vm.state.value.status?.text)
        assertTrue(vm.state.value.status!!.canRetry)

        vm.retry()
        testScheduler.advanceUntilIdle()
        val empty = vm.state.value.status!!
        assertTrue(empty.text.startsWith("No duplicates.\nWe compared 2 files."), empty.text)
        assertFalse(empty.canRetry, "there is nothing to retry over a read that answered")
    }

    @Test
    fun aReadThatNeverAnswersTimesOut_saysCouldNotLook_andKeepsNoJobAlive() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Unbounded, it leaves the spinner up for good; a structured child would also keep the
        // view model's job alive past the timeout, once per retry (§8.17 note 12).
        val repo = Library(photo(1, "h1"), photo(2, "h1"))
        val stuck = CompletableDeferred<Unit>().also { repo.uninterruptible[0] = it }
        val vm = vms.own(DuplicatesViewModel(repo, timeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        assertFalse(vm.state.value.loading)
        assertEquals(DuplicatesCopy.COULD_NOT_LOOK, vm.state.value.status?.text)
        assertTrue(
            vm.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive },
            "no job of the view model's still waits on the stuck read",
        )

        vm.retry()                                          // and the latch is free: the next read runs
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("p1", "p2"), vm.state.value.names("h1"))
        stuck.complete(Unit)
    }

    @Test
    fun aRefreshAskedForWhileOneRunsIsQueued_notDropped_andNotRunBesideIt() = vmTest(dispatcher) { vms ->
        val repo = Library(photo(1, "h1"), photo(2, "h1"))
        val first = CompletableDeferred<Unit>().also { repo.readGates[0] = it }
        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.runCurrent()

        vm.refresh()
        vm.refresh()
        testScheduler.runCurrent()
        assertEquals(1, repo.reads, "single-flight")

        first.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(2, repo.reads, "one queued rerun")
    }

    @Test
    fun aReadPublishesOnlyItsOwnFields() = vmTest(dispatcher) { vms ->
        val repo = Library(photo(1, "h1"), photo(2, "h1"))
        repo.refusing += "p2"
        val vm = vms.own(DuplicatesViewModel(repo))
        testScheduler.advanceUntilIdle()
        vm.keepOnly(vm.confirm("h1", "p1")!!)
        testScheduler.advanceUntilIdle()
        assertNotNull(vm.state.value.report, "precondition")

        val gate = CompletableDeferred<Unit>().also { repo.readGates[repo.reads] = it }
        vm.refresh()
        testScheduler.runCurrent()
        vm.setDeletePermanently(true)                       // changed while the read is in flight
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertNotNull(vm.state.value.report, "the re-read left the report alone")
        assertTrue(vm.state.value.deletePermanently, "and the switch")
    }

    @Test
    fun theFirstReadMayRunInsideTheConstructor() {
        // ⚠⚠ The album crash (§8.16): on K/N `viewModelScope` is `Main.immediate`.
        Dispatchers.resetMain()
        val eager = UnconfinedTestDispatcher()
        Dispatchers.setMain(eager)
        vmTest(eager) { vms ->
            val vm = vms.own(DuplicatesViewModel(Library(photo(1, "h1"), photo(2, "h1"))))
            assertEquals(listOf("p1", "p2"), vm.state.value.names("h1"))
        }
    }

    // ── the sentences ────────────────────────────────────────────────────────────────────────

    @Test
    fun theTwoModesNeverReadAlike() {
        val trash = DuplicatesUiState(groups = listOf(DuplicateGroup("h1", listOf(photo(1, "h1"), photo(2, "h1")), 10)), loading = false)
        val keep = trash.groups.single().photos.first()
        val safe = trash.confirmation(trash.groups.single(), keep)!!
        val destroying = trash.copy(deletePermanently = true).confirmation(trash.groups.single(), keep)!!

        assertFalse(safe.permanently)
        assertEquals("Move the other copies to the trash?", safe.title)
        assertEquals("Keeping p1.jpg.", safe.keeping)
        assertEquals("1 other copy will move to the trash. You can restore it from there.", safe.consequence)
        assertEquals("Move to Trash", safe.confirm)

        assertTrue(destroying.permanently)
        assertEquals("Delete the other copies for good?", destroying.title)
        // ⚠⚠ The confirmation names what is LOST, not "are you sure".
        assertEquals("1 other copy will be removed from all your devices. This can't be undone.", destroying.consequence)
        assertEquals("Delete for Good", destroying.confirm)
        assertEquals(listOf(keep.path), listOf(destroying.keepPath))
        assertFalse(keep.path in destroying.otherPaths, "the survivor is never among the copies acted on")
    }

    @Test
    fun theCountsArePluralisedProperly() {
        assertEquals("1 set of identical photos, out of 1 file.", DuplicatesCopy.summary(1, 1))
        assertEquals("3 sets of identical photos, out of 812 files.", DuplicatesCopy.summary(3, 812))
        assertEquals("2 other copies will move to the trash. You can restore them from there.", DuplicatesCopy.consequence(2, false))
        assertTrue(DuplicatesCopy.empty(1).startsWith("No duplicates.\nWe compared 1 file."))
        assertEquals(
            "2 copies of 3 couldn't be deleted. Make sure Tenshu Control is running, then open Duplicates again.",
            DuplicatesCopy.keepReport(total = 3, refused = 2, unconfirmed = 0, notTried = 0, unfileable = 0, permanently = true).message,
        )
    }

    @Test
    fun aSetNoCopyOfWhichCanBeChosenDoesNotSayTap() {
        val group = DuplicateGroup("h1", listOf(photo(1, "h1"), photo(2, "h1")), 10)
        val open = DuplicatesUiState(groups = listOf(group), loading = false)
        assertEquals("2 identical copies — tap the one to keep", open.heading(group))
        // p2's write went unanswered: p2 is locked, and keeping p1 would leave nothing to remove.
        val locked = open.copy(uncertain = setOf(group.photos[1].path))
        assertFalse(locked.canKeep(group, group.photos[0]))
        assertFalse("tap" in locked.heading(group), locked.heading(group))
        // ⚠ While a write merely RUNS the heading stays — the tiles come back when it ends.
        assertEquals("2 identical copies — tap the one to keep", open.copy(writing = true).heading(group))
    }
}
