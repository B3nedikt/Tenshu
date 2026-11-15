package app.tenshu.photos

import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.MutableLibraryWatch
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A return to the Collections tab walks the library only when something the tab draws may have
 * changed (`photos-ios.md` §8.22.3). A walk is ~2 s of store reads on a phone, too much to make on
 * every return.
 *
 * ⚠⚠ Every assertion is a COUNT of walks — `allPhotosRead` calls on the repository — never a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsWalksOnlyWhenChangedTest {

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val photos = List(6) { Photo("/p$it.jpg", 1_000L + it, isVideo = false, contentId = "c$it") }

    /** Counts walks; can hold one open, and can fail the albums read. */
    private class Library(private val inner: MockPhotosRepository) : PhotosRepository by inner {
        var walks = 0
        var hold: CompletableDeferred<Unit>? = null
        var failAlbums = false

        override suspend fun allPhotosRead(): List<Photo>? {
            walks++
            hold?.await()
            return inner.allPhotosRead()
        }

        override suspend fun albums(): AlbumsResult = if (failAlbums) AlbumsResult.Failed("boom") else inner.albums()
    }

    private class Fixture(
        val scope: TestScope,
        val watch: MutableLibraryWatch?,
        val library: Library,
        val vm: CollectionsViewModel,
        /** What the iOS shell feeds from the index runner: a pass that changed the face templates. */
        val indexed: MutableSharedFlow<Unit>,
    ) {
        fun settle() = scope.advanceUntilIdle()

        /** An index pass ended having written face templates — and, ⚠ like the Core ML backend, no label. */
        fun passEnded() {
            check(indexed.tryEmit(Unit))
        }
    }

    /**
     * ⚠ [followsIndex] `false` is the Android/desktop construction — no `indexChanges` at all — kept
     * covered on its own, since every other case here passes one.
     */
    private fun tab(
        watch: MutableLibraryWatch? = MutableLibraryWatch(watching = true),
        followsIndex: Boolean = true,
        body: suspend Fixture.() -> Unit,
    ) {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        vmTest(main) { vms ->
            val library = Library(MockPhotosRepository(photos = photos).also { it.watch = watch })
            val indexed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val fixture = Fixture(this, watch, library, vms.own(CollectionsViewModel(library, indexChanges = indexed.takeIf { followsIndex })), indexed)
            fixture.settle()
            assertEquals(1, library.walks, "the walk the view model starts with")
            fixture.body()
        }
    }

    // ── nothing changed ────────────────────────────────────────────────────────────────────────────

    @Test
    fun returnsToTheTabWithNothingChangedWalkNothing() = tab {
        repeat(5) {
            vm.setShown("tab", true)
            vm.refreshIfChanged()
            settle()
            vm.setShown("tab", false)
        }
        assertEquals(1, library.walks)
    }

    // ── something changed ──────────────────────────────────────────────────────────────────────────

    /**
     * A grid pushed over the tab that calls only [CollectionsViewModel.setShown] when it comes back —
     * the smart album's, under a viewer that trashed a photo from it. ⚠⚠ Nothing else walks for it:
     * the viewer's `changed()` landed with nothing shown, and the write's own signal may have, too.
     */
    @Test
    fun aSurfaceThatComesBackAfterAChangeIsWalkedForOnce_withoutBeingAsked() = tab {
        vm.setShown("smart", true)
        vm.setShown("smart", false) // the viewer is pushed over it
        vm.changed() // … and trashes a photo
        watch!!.changed() // the trash's own signal, before the grid is back
        settle()
        assertEquals(1, library.walks, "nobody is looking: nothing is walked")

        vm.setShown("smart", true) // the viewer is dismissed
        settle()
        assertEquals(2, library.walks, "the grid that came back is walked for, once")

        vm.setShown("smart", false)
        vm.setShown("smart", true)
        settle()
        assertEquals(2, library.walks, "and not again with nothing changed")
    }

    @Test
    fun aChangeWhileTheTabIsHiddenIsOneWalkOnTheReturn_andNoneBeforeIt() = tab {
        repeat(7) { watch!!.changed() }
        settle()
        assertEquals(1, library.walks, "nobody is looking: nothing is walked")

        vm.setShown("tab", true)
        vm.refreshIfChanged()
        settle()
        assertEquals(2, library.walks)

        vm.refreshIfChanged()
        settle()
        assertEquals(2, library.walks, "and clean again after it")
    }

    @Test
    fun aBurstOfChangesWhileTheTabIsShownIsOneWalk() = tab {
        vm.setShown("tab", true)
        // Twelve arrivals 80 ms apart — a sync, as the tab sees one.
        repeat(12) {
            watch!!.changed()
            scope.advanceTimeBy(80)
        }
        assertEquals(1, library.walks, "not while they are still arriving")
        settle()

        assertEquals(2, library.walks)
    }

    /** ⚠ A debounce with no ceiling shows nothing until the sync ENDS — which can be an hour. */
    @Test
    fun aChangeThatNeverStopsIsStillWalkedFor() = tab {
        vm.setShown("tab", true)
        repeat(40) {
            watch!!.changed()
            scope.advanceTimeBy(300)
        }
        scope.runCurrent()

        assertEquals(true, library.walks >= 3, "twelve seconds of changes, ${library.walks - 1} walks")
        assertEquals(true, library.walks <= 9, "and never one per change: ${library.walks - 1} walks")
    }

    @Test
    fun changesDuringAWalkAreExactlyOneMoreWalk() = tab {
        vm.setShown("tab", true)
        val gate = CompletableDeferred<Unit>()
        library.hold = gate
        watch!!.changed()
        // ⚠ Bounded advances while the walk is held: `advanceUntilIdle` would run virtual time past
        // the read's own 20 s bound and end the walk for it.
        scope.advanceTimeBy(2_000)
        assertEquals(2, library.walks, "the walk for the first change, held open")

        repeat(5) { watch.changed() } // overtaken while it reads
        scope.advanceTimeBy(5_000)
        assertEquals(2, library.walks, "never a second walk beside the first")
        library.hold = null
        gate.complete(Unit)
        settle()

        assertEquals(3, library.walks, "one rerun for everything that changed meanwhile")
        vm.refreshIfChanged()
        settle()
        assertEquals(3, library.walks)
    }

    /** The same overtaking with nobody looking: the rerun waits for the return. */
    @Test
    fun aChangeDuringAWalkWithTheTabHiddenWaitsForTheReturn() = tab {
        val gate = CompletableDeferred<Unit>()
        library.hold = gate
        vm.changed() // an edit made on a pushed screen …
        vm.setShown("tab", true)
        vm.refreshIfChanged() // … walked for on the way back
        scope.runCurrent()
        assertEquals(2, library.walks)
        vm.setShown("tab", false)
        watch!!.changed() // … and overtaken, after the user has left again
        library.hold = null
        gate.complete(Unit)
        settle()
        assertEquals(2, library.walks, "nobody is looking")

        vm.setShown("tab", true)
        vm.refreshIfChanged()
        settle()
        assertEquals(3, library.walks, "the overtaken walk was not called clean")
    }

    // ── what the shell tells it ────────────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ An edit is told twice: by the shell ([CollectionsViewModel.changed]) and, a moment later, by
     * the store's own signal. Walked for at once, the first walk is overtaken by the second telling —
     * two walks for one rename.
     */
    @Test
    fun anEditToldByTheShellAndThenByTheStoreIsOneWalk() = tab {
        vm.setShown("tab", true)
        vm.changed()
        scope.advanceTimeBy(40)
        watch!!.changed() // the write's own signal, arriving late
        settle()

        assertEquals(2, library.walks)
    }

    @Test
    fun anEditMadeOnAPushedScreenIsOneWalkOnTheReturn() = tab {
        vm.changed()
        watch!!.changed()
        settle()
        assertEquals(1, library.walks, "the tab is covered")

        vm.setShown("tab", true)
        vm.refreshIfChanged()
        settle()
        assertEquals(2, library.walks)
    }

    /**
     * ⚠⚠ An edit is only NOTED because the watch is expected to deliver its walk. A watch that
     * stops inside the settle wait must not drop it: the edit is still walked for.
     */
    @Test
    fun anEditIsWalkedFor_evenIfTheWatchStopsBeforeItsWalk() = tab {
        vm.setShown("tab", true)
        vm.changed()
        scope.advanceTimeBy(100) // inside the settle wait
        watch!!.setWatching(false)
        settle()
        assertEquals(2, library.walks, "the tab on screen walks for its own edit")
    }

    /** The same, for a grid that only says it is shown again — the smart album's, under the viewer. */
    @Test
    fun anEditOnAPushedScreenIsWalkedForOnTheWayBack_evenIfTheWatchStopped() = tab {
        vm.setShown("smart", true)
        vm.setShown("smart", false) // the viewer is pushed over it
        vm.changed() // … and trashes a photo
        watch!!.setWatching(false)
        settle()
        assertEquals(1, library.walks, "nobody is looking: nothing is walked")

        vm.setShown("smart", true)
        settle()
        assertEquals(2, library.walks, "the grid that came back is walked for")
    }

    // ── this device's index (`photos-ios.md` §8.22.3, `ios-face-grouping.md` §17.6) ────────────────

    /**
     * ⚠⚠ The device-pass defect: a pass writes face templates and no `MediaLabel`, so the watch's
     * revision does not move — and the return said `walk=skipped (clean)` over the pre-pass People.
     */
    @Test
    fun aPassThatWroteFacesButNoLabelIsWalkedForOnTheReturn() = tab {
        passEnded()
        settle()
        assertEquals(1, library.walks, "nobody is looking: nothing is walked")

        vm.setShown("tab", true)
        vm.refreshIfChanged()
        settle()
        assertEquals(2, library.walks, "the return walks: People is re-read")

        vm.setShown("tab", false)
        vm.setShown("tab", true)
        vm.refreshIfChanged()
        settle()
        assertEquals(2, library.walks, "and clean again after it")
    }

    /** The tab on screen when the pass ends redraws without being left and re-entered. */
    @Test
    fun aPassThatEndsWhileTheTabIsShownIsWalkedFor() = tab {
        vm.setShown("tab", true)
        passEnded()
        settle()
        assertEquals(2, library.walks)
    }

    /** A pass ending mid-walk is one more walk after it, not a walk beside it. */
    @Test
    fun aPassThatEndsDuringAWalkIsOneMoreWalk() = tab {
        vm.setShown("tab", true)
        val gate = CompletableDeferred<Unit>()
        library.hold = gate
        vm.refresh()
        scope.runCurrent()
        assertEquals(2, library.walks)
        passEnded()
        scope.advanceTimeBy(5_000)
        assertEquals(2, library.walks, "never a second walk beside the first")
        library.hold = null
        gate.complete(Unit)
        settle()
        assertEquals(3, library.walks)
    }

    /** ⚠ Unlike `changed()`: with no watch a pass is still not a walk of a library nobody is looking at. */
    @Test
    fun withoutAWatchAPassWithTheTabHiddenWaitsForTheReturn() = tab(watch = null) {
        passEnded()
        settle()
        assertEquals(1, library.walks)
        vm.setShown("tab", true)
        passEnded()
        settle()
        assertEquals(2, library.walks, "shown: walked for, by the change loop a watchless tab still runs")
    }

    // ── when nobody can vouch ──────────────────────────────────────────────────────────────────────

    /** ⚠⚠ The safe direction: no watch is "may have changed", every time — today's behaviour. */
    @Test
    fun withoutAWatchEveryReturnWalks_andAnEditWalksAtOnce() = tab(watch = null) {
        repeat(3) {
            vm.refreshIfChanged()
            settle()
        }
        assertEquals(4, library.walks)

        vm.changed()
        settle()
        assertEquals(5, library.walks)
    }

    /** The Android/desktop construction: no watch, no `indexChanges` — every return walks, an edit at once. */
    @Test
    fun withoutAWatchOrIndexChangesEveryReturnWalks_andAnEditWalksAtOnce() = tab(watch = null, followsIndex = false) {
        repeat(3) {
            vm.setShown("tab", true)
            vm.refreshIfChanged()
            settle()
        }
        assertEquals(4, library.walks)
        vm.changed()
        settle()
        assertEquals(5, library.walks)
    }

    /** ⚠ With a watch and no `indexChanges`, the skip is today's: nothing changed, nothing walked. */
    @Test
    fun withAWatchAndNoIndexChangesACleanReturnStillSkips() = tab(followsIndex = false) {
        vm.setShown("tab", true)
        vm.refreshIfChanged()
        settle()
        assertEquals(1, library.walks)
    }

    @Test
    fun aWatchThatIsNotWatchingIsNoWatch() = tab {
        watch!!.setWatching(false)
        repeat(3) {
            vm.refreshIfChanged()
            settle()
        }
        assertEquals(4, library.walks)
    }

    /** Watching again is not "nothing changed meanwhile": the first return after it walks, then it is clean. */
    @Test
    fun aWatchThatWasInterruptedIsNotClean() = tab {
        watch!!.setWatching(false)
        watch.setWatching(true)

        vm.refreshIfChanged()
        settle()
        assertEquals(2, library.walks)
        vm.refreshIfChanged()
        settle()
        assertEquals(2, library.walks)
    }

    /** A walk that began before the watch was watching cannot be vouched for. */
    @Test
    fun aWalkThatBeganBeforeTheWatchWasLiveIsNotClean() = tab(watch = MutableLibraryWatch(watching = false)) {
        watch!!.setWatching(true)

        vm.refreshIfChanged()
        settle()
        assertEquals(2, library.walks)
        vm.refreshIfChanged()
        settle()
        assertEquals(2, library.walks)
    }

    /** ⚠ A walk that did not read every section leaves the tab unvouched-for: the next return retries. */
    @Test
    fun aWalkWithAFailedSectionIsNotClean() = tab {
        library.failAlbums = true
        watch!!.changed()
        vm.setShown("tab", true)
        vm.refreshIfChanged()
        settle()
        assertEquals(2, library.walks)
        library.failAlbums = false

        vm.refreshIfChanged()
        settle()
        assertEquals(3, library.walks, "the failed read was retried by the return")
        vm.refreshIfChanged()
        settle()
        assertEquals(3, library.walks)
    }

    /** `refresh()` is still unconditional — Try Again, a realm change, the Compose shells' nav item. */
    @Test
    fun anExplicitRefreshAlwaysWalks() = tab {
        vm.refresh()
        settle()
        assertEquals(2, library.walks)
    }
}
