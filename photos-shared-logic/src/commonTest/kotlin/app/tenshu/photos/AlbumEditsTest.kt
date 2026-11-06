package app.tenshu.photos

import androidx.lifecycle.viewModelScope
import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailViewModel
import app.tenshu.photos.ui.screens.common.WriteOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * An album's edits — rename, delete, remove a photograph (`photos-ios.md` §8.16).
 *
 * ⚠⚠ Each edit reports whether it HAPPENED, because the shells act on the answer: the album screen
 * closes only on a delete that happened, and a refusal is said rather than swallowed.
 */
class AlbumEditsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(i: Int) = Photo("/p$i.jpg", 1_000L - i, isVideo = false, contentId = "c$i")

    /** Counts each edit, answers [answer], and holds every edit on [gate] when one is set. */
    private class Edits(
        val delegate: MockPhotosRepository = MockPhotosRepository(),
        var answer: Boolean? = null,
    ) : PhotosRepository by delegate {
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null

        /** The edit COMMITS, then its reply is lost — the case a timeout cannot tell from a refusal. */
        var replyLost = false

        /** The N-th album read waits for `readGates[N]` once it has snapshotted. */
        val readGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        private var reads = 0

        override suspend fun albumPhotos(albumId: String): app.tenshu.photos.data.PhotoPageResult {
            val n = reads++
            val snapshot = delegate.albumPhotos(albumId)
            readGates[n]?.await()
            return if (snapshot is app.tenshu.photos.data.PhotoPageResult.Page) {
                snapshot.copy(photos = snapshot.photos.filterNot { it.contentId in unresolved })
            } else {
                snapshot
            }
        }

        /** Photographs the resolved page leaves out though their ENTRY exists — an unresolvable file. */
        val unresolved = mutableSetOf<String>()

        /** Edits whose wait was abandoned (cancelled) by the view model. */
        var abandoned = 0

        /**
         * Holds every album-list read on a gate that IGNORES cancellation — a Binder read, which cannot
         * be interrupted. `null` = answers at once.
         */
        var uninterruptibleAlbums: CompletableDeferred<Unit>? = null

        override suspend fun albums(): AlbumsResult {
            uninterruptibleAlbums?.let { gate ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            }
            return delegate.albums()
        }

        /** Holds every write on a gate that IGNORES cancellation — a Binder call. */
        var uninterruptibleWrite: CompletableDeferred<Unit>? = null

        private suspend fun edit(name: String, real: suspend () -> Boolean): Boolean {
            calls += name
            uninterruptibleWrite?.let { gate ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            }
            try {
                if (replyLost) {
                    real()
                    CompletableDeferred<Unit>().await()
                }
                gate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                abandoned++
                throw e
            }
            return answer ?: real()
        }

        override suspend fun renameAlbum(albumId: String, name: String) =
            edit("rename:$name") { delegate.renameAlbum(albumId, name) }

        override suspend fun deleteAlbum(albumId: String) = edit("delete") { delegate.deleteAlbum(albumId) }

        override suspend fun removeFromAlbum(albumId: String, contentId: String) =
            edit("remove:$contentId") { delegate.removeFromAlbum(albumId, contentId) }
    }

    private suspend fun Edits.album(vararg photos: Photo): String {
        val id = delegate.createAlbum("Holiday")!!
        photos.forEach { delegate.addToAlbum(id, it) }
        return id
    }

    @Test
    fun aRenameChangesTheTitle_onlyOnceItHappened() = vmTest(dispatcher) { vms ->
        val repo = Edits()
        val vm = vms.own(AlbumDetailViewModel(repo, repo.album(), "Holiday"))
        testScheduler.advanceUntilIdle()

        repo.answer = false
        var refused: WriteOutcome? = null
        vm.rename("Summer") { refused = it }
        testScheduler.advanceUntilIdle()
        assertEquals(WriteOutcome.Refused, refused)
        assertEquals("Holiday", vm.state.value.title, "a refused rename leaves the name the album still has")

        repo.answer = null
        var renamed: WriteOutcome? = null
        vm.rename("  Summer  ") { renamed = it }
        testScheduler.advanceUntilIdle()
        assertEquals(WriteOutcome.Done, renamed)
        assertEquals("Summer", vm.state.value.title, "trimmed")
        assertEquals(listOf("Summer"), (repo.delegate.albums() as AlbumsResult.Albums).albums.map { it.name })
    }

    @Test
    fun aBlankNameIsNotARename() = vmTest(dispatcher) { vms ->
        val repo = Edits()
        val vm = vms.own(AlbumDetailViewModel(repo, repo.album(), "Holiday"))
        testScheduler.advanceUntilIdle()

        var answer: WriteOutcome? = null
        vm.rename("   ") { answer = it }
        testScheduler.advanceUntilIdle()
        assertEquals(WriteOutcome.Refused, answer, "reported as not having happened")
        assertTrue(repo.calls.isEmpty(), "and never written")
        assertEquals("Holiday", vm.state.value.title)
    }

    @Test
    fun aDeleteReportsWhetherItHappened_andKeepsThePhotographs() = vmTest(dispatcher) { vms ->
        val repo = Edits()
        val id = repo.album(photo(1))
        val vm = vms.own(AlbumDetailViewModel(repo, id, "Holiday"))
        testScheduler.advanceUntilIdle()

        repo.answer = false
        var refused: WriteOutcome? = null
        vm.delete { refused = it }
        testScheduler.advanceUntilIdle()
        assertEquals(WriteOutcome.Refused, refused, "a refusal keeps the screen open")

        repo.answer = null
        var deleted: WriteOutcome? = null
        vm.delete { deleted = it }
        testScheduler.advanceUntilIdle()
        assertEquals(WriteOutcome.Done, deleted)
        assertTrue((repo.delegate.albums() as AlbumsResult.Albums).albums.isEmpty())
    }

    @Test
    fun aRemovalReportsWhetherItHappened_andTheGridFollows() = vmTest(dispatcher) { vms ->
        val repo = Edits()
        val vm = vms.own(AlbumDetailViewModel(repo, repo.album(photo(1), photo(2)), "Holiday"))
        testScheduler.advanceUntilIdle()
        assertEquals(2, vm.state.value.photos.size, "precondition")

        repo.answer = false
        var refused: WriteOutcome? = null
        vm.remove(photo(1)) { refused = it }
        testScheduler.advanceUntilIdle()
        assertEquals(WriteOutcome.Refused, refused)
        assertEquals(2, vm.state.value.photos.size)

        repo.answer = null
        var removed: WriteOutcome? = null
        vm.remove(photo(1)) { removed = it }
        testScheduler.advanceUntilIdle()
        assertEquals(WriteOutcome.Done, removed)
        assertEquals(listOf("/p2.jpg"), vm.state.value.photos.map { it.path }, "the grid re-read")
    }

    @Test
    fun aRefusedRemovalReReadsTheAlbum() = vmTest(dispatcher) { vms ->
        // ⚠ Android's long-press removal passes no `onDone`; a refusal must still be said — a
        // message, and a read to say whether Control was unreachable and what the album holds.
        val repo = Edits()
        val id = repo.album(photo(1), photo(2))
        val vm = vms.own(AlbumDetailViewModel(repo, id, "Holiday"))
        testScheduler.advanceUntilIdle()

        repo.delegate.removeFromAlbum(id, "c2")             // the store moved on; the screen has not read it
        repo.answer = false
        vm.remove(photo(1))                                 // as Android calls it: no callback
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("/p1.jpg"), vm.state.value.photos.map { it.path }, "the refusal re-read the album")
    }

    @Test
    fun aRemovedPhotographLeavesTheGridAtOnce_andAnOlderReadNeverBringsItBack() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The edit unlocks when the write answers, so a tile still showing until the slow
        // re-read lands could be removed twice — the second a false refusal.
        val repo = GatedReads()
        val id = repo.album(photo(1), photo(2))
        val vm = vms.own(AlbumDetailViewModel(repo, id, "Holiday"))
        testScheduler.advanceUntilIdle()                    // read #0
        assertEquals(2, vm.state.value.photos.size, "precondition")

        val older = CompletableDeferred<Unit>().also { repo.gates[1] = it }
        // #2 is the re-read after the removal (a removal asks no pre-check — see `remove`).
        val after = CompletableDeferred<Unit>().also { repo.gates[2] = it }
        vm.refresh()                                        // read #1 snapshots WITH /p1
        testScheduler.runCurrent()

        var removed: WriteOutcome? = null
        vm.remove(photo(1)) { removed = it }
        testScheduler.runCurrent()
        assertEquals(WriteOutcome.Done, removed)
        assertFalse(vm.state.value.writing, "the edit is answered")
        assertEquals(listOf("/p2.jpg"), vm.state.value.photos.map { it.path }, "and the tile is already gone")

        older.complete(Unit)                                // the snapshot from before the removal lands
        testScheduler.runCurrent()
        assertEquals(listOf("/p2.jpg"), vm.state.value.photos.map { it.path }, "an older read never brings it back")

        after.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("/p2.jpg"), vm.state.value.photos.map { it.path })
    }

    /** An album whose N-th read SNAPSHOTS, then waits for `gates[N]` (no gate = answers at once). */
    private class GatedReads(val delegate: MockPhotosRepository = MockPhotosRepository()) : PhotosRepository by delegate {
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        private var reads = 0
        override suspend fun albumPhotos(albumId: String): app.tenshu.photos.data.PhotoPageResult {
            val n = reads++
            val snapshot = delegate.albumPhotos(albumId)
            gates[n]?.await()
            return snapshot
        }

        suspend fun album(vararg photos: Photo): String {
            val id = delegate.createAlbum("Holiday")!!
            photos.forEach { delegate.addToAlbum(id, it) }
            return id
        }
    }

    @Test
    fun theFirstReadMayRunInsideTheConstructor() {
        // ⚠⚠ The iOS crash (§8.16): on K/N `viewModelScope` is `Main.immediate`, so `init`'s read runs
        // during construction, before any field declared below `init` exists. `StandardTestDispatcher`
        // defers it and would hide the crash; an eager Main is what the phone runs.
        Dispatchers.resetMain()
        val eager = kotlinx.coroutines.test.UnconfinedTestDispatcher()
        Dispatchers.setMain(eager)
        vmTest(eager) { vms ->
            val repo = GatedReads()
            val id = repo.album(photo(1))
            val vm = vms.own(AlbumDetailViewModel(repo, id, "Holiday"))
            assertEquals(listOf("/p1.jpg"), vm.state.value.photos.map { it.path }, "read during construction")
        }
    }

    @Test
    fun editsAreSingleFlight() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A rename and a delete in flight together end in whichever answer lands last.
        val repo = Edits()
        val vm = vms.own(AlbumDetailViewModel(repo, repo.album(photo(1)), "Holiday"))
        testScheduler.advanceUntilIdle()

        val gate = CompletableDeferred<Unit>().also { repo.gate = it }
        var renamed: WriteOutcome? = null
        vm.rename("Summer") { renamed = it }
        testScheduler.runCurrent()
        assertTrue(vm.state.value.writing, "the shells disable the actions meanwhile")

        var deleted: WriteOutcome? = null
        vm.delete { deleted = it }
        vm.remove(photo(1)) {}
        testScheduler.runCurrent()
        assertEquals(listOf("rename:Summer"), repo.calls, "nothing else was written while the rename ran")
        assertNull(deleted, "and an ignored request reports nothing")

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(WriteOutcome.Done, renamed)
        assertFalse(vm.state.value.writing)
    }

    @Test
    fun anEditThatNeverAnswersIsAbandoned_andTheNextOneRuns() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A lost Android reply never returns. The lock must release in the end, and the hung
        // call must be let go of — a watcher per retry would keep the view model alive for good.
        val repo = Edits()
        val vm = vms.own(AlbumDetailViewModel(repo, repo.album(photo(1), photo(2)), "Holiday", editTimeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        repo.gate = CompletableDeferred()                   // never answers
        var first: WriteOutcome? = null
        vm.remove(photo(1)) { first = it }
        testScheduler.advanceUntilIdle()
        assertEquals(WriteOutcome.Unconfirmed, first, "unconfirmed — never a refusal: it may yet land")
        assertFalse(vm.state.value.writing, "the lock released once the settle window ended")
        assertEquals(1, repo.abandoned, "and the hung call was let go of")

        repo.gate = null
        var second: WriteOutcome? = null
        vm.remove(photo(2)) { second = it }
        testScheduler.advanceUntilIdle()
        assertEquals(WriteOutcome.Done, second, "the next edit runs")
    }

    @Test
    fun noEditRunsBesideOneStillSettling() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Released at the first timeout, the lock would let a delete run beside a rename that
        // could still commit — and a late rename (an upsert) re-creates the album.
        val repo = Edits()
        val vm = vms.own(AlbumDetailViewModel(repo, repo.album(photo(1)), "Holiday", editTimeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        val slow = CompletableDeferred<Unit>().also { repo.gate = it }
        var renamed: WriteOutcome? = null
        vm.rename("Summer") { renamed = it }
        testScheduler.advanceTimeBy(1_500)                  // past the first bound, inside the settle window
        testScheduler.runCurrent()
        assertEquals(WriteOutcome.Unconfirmed, renamed, "the user is told it is still waiting")
        assertTrue(vm.state.value.writing, "but the lock is still held")

        var deleted: WriteOutcome? = null
        vm.delete { deleted = it }
        testScheduler.runCurrent()
        assertEquals(listOf("rename:Summer"), repo.calls, "no delete beside the unsettled rename")
        assertEquals(null, deleted)

        slow.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.writing)
    }

    @Test
    fun anEditWhoseReplyIsLostShowsWhatTheStoreHolds_withoutClaimingIt() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A timed-out edit may have committed — a "refused" would keep a stale title and a
        // deleted album on screen — but no VALUE can prove it was this write. So it stays
        // Unconfirmed, and the screen shows the store's truth once the write is let go of.
        val repo = Edits()
        val id = repo.album(photo(1), photo(2))
        val vm = vms.own(AlbumDetailViewModel(repo, id, "Holiday", editTimeoutMs = 1_000))
        testScheduler.advanceUntilIdle()
        repo.replyLost = true

        var renamed: WriteOutcome? = null
        vm.rename("Summer") { renamed = it }
        testScheduler.advanceUntilIdle()
        assertEquals(WriteOutcome.Unconfirmed, renamed, "never confirmed by a value")
        assertEquals("Summer", vm.state.value.title, "but the screen shows the name the store holds")

        vm.remove(photo(1)) {}
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("/p2.jpg"), vm.state.value.photos.map { it.path }, "and the photographs it holds")

        vm.delete {}
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.deleted, "and, the album gone, the screen may close")
    }

    @Test
    fun aPeersIdenticalRenameNeverConfirmsOurs() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Another device writing the SAME name while ours hangs makes the value check true — a
        // lock released on it would let a delete be undone by our late upsert.
        val repo = Edits()
        val id = repo.album(photo(1))
        val vm = vms.own(AlbumDetailViewModel(repo, id, "Holiday", editTimeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        val ours = CompletableDeferred<Unit>().also { repo.gate = it }
        vm.rename("Summer") {}
        testScheduler.runCurrent()
        repo.delegate.renameAlbum(id, "Summer")             // a peer, meanwhile
        testScheduler.advanceTimeBy(1_500)
        testScheduler.runCurrent()
        assertTrue(vm.state.value.writing, "the lock is held: the peer's value is not our answer")
        vm.delete {}
        testScheduler.runCurrent()
        assertEquals(listOf("rename:Summer"), repo.calls, "no delete beside our unsettled rename")
        ours.complete(Unit)
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun aSlowEditThatLandsAfterTheTimeoutStillUpdatesTheScreen() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A Binder call cannot be cancelled, so a slow write commits after the timeout's one
        // check — and the screen, told "refused", must still learn it happened.
        val repo = Edits()
        val id = repo.album(photo(1))
        var landed = 0
        val vm = vms.own(AlbumDetailViewModel(repo, id, "Holiday", editTimeoutMs = 1_000, onEditLanded = { landed++ }))
        testScheduler.advanceUntilIdle()

        val slow = CompletableDeferred<Unit>().also { repo.gate = it }
        var renamed: WriteOutcome? = null
        vm.rename("Summer") { renamed = it }
        testScheduler.advanceTimeBy(1_500)                  // inside the settle window
        testScheduler.runCurrent()
        assertEquals(WriteOutcome.Unconfirmed, renamed, "not in the store yet")
        assertEquals("Holiday", vm.state.value.title)
        assertEquals(0, landed, "nothing landed yet")

        slow.complete(Unit)                                 // the write commits, late
        testScheduler.advanceUntilIdle()
        assertEquals("Summer", vm.state.value.title, "the screen follows the store")
        assertEquals(1, landed, "and the parent is told to refresh")
        assertFalse(vm.state.value.writing)

        val slowDelete = CompletableDeferred<Unit>().also { repo.gate = it }
        vm.delete {}
        testScheduler.advanceTimeBy(1_500)
        testScheduler.runCurrent()
        assertFalse(vm.state.value.deleted)
        slowDelete.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.deleted, "a late delete still closes the screen")
    }

    @Test
    fun stillWaitingPromisesNoUpdateItCannotDeliver() {
        // ⚠ Once a write is abandoned nothing watches it, so "this screen will update" is a promise
        // the screen cannot keep.
        val message = app.tenshu.photos.ui.screens.albumdetail.AlbumEdit.Rename.report(WriteOutcome.Unconfirmed)!!.message
        assertFalse("will update" in message, message)
        assertTrue("later" in message, "it tells the user to look again: $message")
    }

    @Test
    fun aRenameToTheNameItHasWritesNothing_soNothingCanLandLate() = vmTest(dispatcher) { vms ->
        // ⚠⚠ "The store has this name" is true BEFORE the write, so a timed-out rename to the
        // current name must not count as landed at once — the lock would release while its upsert
        // could still commit, and re-create the album a following delete removed.
        val repo = Edits()
        val vm = vms.own(AlbumDetailViewModel(repo, repo.album(photo(1)), "Holiday", editTimeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        repo.gate = CompletableDeferred()                   // a write would hang
        var outcome: WriteOutcome? = null
        vm.rename("Holiday") { outcome = it }
        testScheduler.advanceUntilIdle()
        assertEquals(WriteOutcome.Done, outcome, "nothing to do")
        assertTrue(repo.calls.isEmpty(), "and nothing written that could land later")
        assertFalse(vm.state.value.writing)
    }

    @Test
    fun aReconciliationReadThatNeverReturnsCannotHoldTheLock() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A Binder read cannot be interrupted, so a check read timed directly would wait for
        // ever on its cancellation, and the lock with it.
        val repo = Edits()
        val vm = vms.own(AlbumDetailViewModel(repo, repo.album(photo(1)), "Holiday", editTimeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        val stuck = CompletableDeferred<Unit>().also { repo.uninterruptibleAlbums = it }
        var renamed: WriteOutcome? = null
        vm.rename("Summer") { renamed = it }                // its pre-check reads the album list
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("rename:Summer"), repo.calls, "the check was abandoned, and the write ran")
        // ⚠ The check spent the edit's whole first window, so the user was told "still waiting" when it
        // ended; the write's YES then lands like any late one (aLateYesIsAppliedEvenWhenTheStoreCannotBeRead).
        assertEquals(WriteOutcome.Unconfirmed, renamed)
        assertEquals("Summer", vm.state.value.title, "the answer was applied")
        assertFalse(vm.state.value.writing, "and the lock released")
        stuck.complete(Unit)
    }

    @Test
    fun aCheckThatNeverAnswersCountsAgainstTheEditsBound() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The pre-check gets no window of its OWN before the write's two: with both replies lost
        // — and no Back while an edit runs — that would hold the user for three windows, saying
        // nothing until the second had passed. The bound is two, counted from the start.
        val repo = Edits()
        val vm = vms.own(AlbumDetailViewModel(repo, repo.album(photo(1)), "Holiday", editTimeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        val stuck = CompletableDeferred<Unit>().also { repo.uninterruptibleAlbums = it }
        repo.gate = CompletableDeferred()                   // the write never answers either
        var renamed: WriteOutcome? = null
        vm.rename("Summer") { renamed = it }
        testScheduler.advanceTimeBy(1_100)
        testScheduler.runCurrent()
        assertEquals(WriteOutcome.Unconfirmed, renamed, "\"still waiting\" after ONE window, not two")
        assertEquals(listOf("rename:Summer"), repo.calls, "the write was still sent")
        assertTrue(vm.state.value.writing)

        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertFalse(vm.state.value.writing, "released after two windows — a third was the check's own")
        stuck.complete(Unit)
    }

    @Test
    fun aLateReconciliationReadThatNeverAnswersCannotHoldTheLock() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The late `reconcile` read is bounded too — a lost reply must not keep `writing`.
        val repo = Edits()
        val vm = vms.own(AlbumDetailViewModel(repo, repo.album(photo(1)), "Holiday", editTimeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        val slow = CompletableDeferred<Unit>().also { repo.gate = it }
        vm.rename("Summer") {}
        testScheduler.runCurrent()                          // the pre-check answered; the write hangs
        val stuck = CompletableDeferred<Unit>().also { repo.uninterruptibleAlbums = it }
        testScheduler.advanceTimeBy(1_500)
        slow.complete(Unit)                                 // answers late → reconcile reads the albums
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.writing, "the reconciliation read was let go of, and the lock with it")
        stuck.complete(Unit)
    }

    @Test
    fun aLateYesIsAppliedEvenWhenTheStoreCannotBeRead() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A late definitive success is applied, not handled like no answer at all: a
        // reconciliation read alone, unreadable, would leave a renamed album under its old title.
        val repo = Edits()
        val vm = vms.own(AlbumDetailViewModel(repo, repo.album(photo(1)), "Holiday", editTimeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        val slow = CompletableDeferred<Unit>().also { repo.gate = it }
        vm.rename("Summer") {}
        testScheduler.runCurrent()                          // pre-check answered; the write hangs
        val unreadable = CompletableDeferred<Unit>().also { repo.uninterruptibleAlbums = it }
        testScheduler.advanceTimeBy(1_500)
        slow.complete(Unit)                                 // a late YES
        testScheduler.advanceUntilIdle()
        assertEquals("Summer", vm.state.value.title, "the answer is proof, read or no read")
        unreadable.complete(Unit)
    }

    @Test
    fun anAbandonedWriteDoesNotKeepTheEditAlive() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A Binder write ignores cancellation, and as a structured child it would keep the
        // edit's job — the view model, repository and closures — alive for good, once per retry.
        val repo = Edits()
        val vm = vms.own(AlbumDetailViewModel(repo, repo.album(photo(1)), "Holiday", editTimeoutMs = 1_000))
        testScheduler.advanceUntilIdle()

        val stuck = CompletableDeferred<Unit>().also { repo.uninterruptibleWrite = it }
        vm.rename("Summer") {}
        testScheduler.advanceUntilIdle()                    // both windows pass; the write is abandoned
        assertFalse(vm.state.value.writing)
        val scope = vm.viewModelScope
        assertTrue(
            scope.coroutineContext[kotlinx.coroutines.Job]!!.children.none { it.isActive },
            "no job of the view model's still waits on the abandoned call",
        )
        stuck.complete(Unit)
    }

    @Test
    fun aRemovalIsSentEvenWhenThePageNoLongerShowsThePhoto() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The page drops an entry whose file is unresolvable, so a pre-check of "not on the
        // page" would skip a removal whose ENTRY still exists — and the photograph would come back
        // in the album once its file resolved again.
        val repo = Edits()
        val id = repo.album(photo(1))
        val vm = vms.own(AlbumDetailViewModel(repo, id, "Holiday"))
        testScheduler.advanceUntilIdle()
        repo.unresolved += "c1"                             // the file goes unresolvable; its entry stays

        vm.remove(photo(1)) {}
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("remove:c1"), repo.calls, "the removal was sent, not inferred")
        repo.unresolved.clear()
        assertTrue(
            (repo.delegate.albumPhotos(id) as app.tenshu.photos.data.PhotoPageResult.Page).photos.isEmpty(),
            "and the entry is really gone — it does not come back when the file resolves",
        )
    }

    @Test
    fun aReadNeverPutsBackATitleARenameSet() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A read publishes only its own fields: a copy of the WHOLE state, answered before a
        // rename and published after it, would put the old title back.
        val repo = Edits()
        val vm = vms.own(AlbumDetailViewModel(repo, repo.album(photo(1)), "Holiday"))
        testScheduler.advanceUntilIdle()                    // read #0

        val read = CompletableDeferred<Unit>().also { repo.readGates[1] = it }
        vm.refresh()
        testScheduler.runCurrent()
        val write = CompletableDeferred<Unit>().also { repo.gate = it }
        vm.rename("Summer") {}
        testScheduler.runCurrent()

        read.complete(Unit)                                 // the read is answered first…
        write.complete(Unit)                                // …the rename just after
        testScheduler.advanceUntilIdle()
        assertEquals("Summer", vm.state.value.title, "the read never had a say in the title")
        assertFalse(vm.state.value.writing)
    }

    @Test
    fun favouritesHasNothingToEdit() = vmTest(dispatcher) { vms ->
        // ⚠ Favourites has no membership and no name of its own: every edit is absent, not refused.
        val repo = Edits()
        val vm = vms.own(AlbumDetailViewModel(repo, AlbumDetailViewModel.FAVOURITES, "Favourites"))
        testScheduler.advanceUntilIdle()

        var answered = false
        vm.rename("Mine") { answered = true }
        vm.delete { answered = true }
        vm.remove(photo(1)) { answered = true }
        testScheduler.advanceUntilIdle()
        assertTrue(repo.calls.isEmpty())
        assertFalse(answered)
    }
}
