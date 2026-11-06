package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.OffCallerPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.data.PhotosRepositoryDecorator
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailViewModel
import app.tenshu.photos.ui.screens.common.boundedCall
import app.tenshu.photos.ui.screens.duplicates.DuplicatesViewModel
import app.tenshu.photos.ui.screens.feed.FeedFirstPage
import app.tenshu.photos.ui.screens.feed.PhotosFeedViewModel
import app.tenshu.photos.ui.screens.search.DescriptionSearch
import app.tenshu.photos.ui.screens.search.PhotoSearchViewModel
import app.tenshu.photos.ui.screens.trash.TrashViewModel
import app.tenshu.photos.ui.screens.viewer.PhotoViewerViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * No repository call a Photos screen makes runs in its caller once the shell installs an
 * [OffCallerPhotosRepository] (`photos-ios.md` §8.22.2). On iOS the caller is the main thread and a
 * call is a store query on the thread that makes it: a walk of the library there is ~2 s of a frozen
 * app, on every screen that makes one.
 *
 * ⚠⚠ The worker here is a QUEUE the test runs by hand, and the main dispatcher is IMMEDIATE — what
 * Kotlin/Native's `viewModelScope` is. So "the trigger returned and nothing was read" is observed, not
 * inferred: nothing on the worker runs until [Fixture.settle] says so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotosCallsOffTheCallerTest {

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Holds what is dispatched to it until [runAll]; marks what it runs. */
    private class Worker : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        var running = false
            private set
        val waiting: Int get() = queue.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue += block
        }

        /** Runs everything queued, and what that queues. `false` when there was nothing. */
        fun runAll(): Boolean {
            if (queue.isEmpty()) return false
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                running = true
                try {
                    next.run()
                } finally {
                    running = false
                }
            }
            return true
        }
    }

    /** Every call that reached the repository, and whether the worker was running it. */
    private class Recording(inner: PhotosRepository, private val onWorker: () -> Boolean) : PhotosRepositoryDecorator(inner) {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val names: List<String> get() = calls.map { it.first }
        val inTheCaller: List<String> get() = calls.filterNot { it.second }.map { it.first }

        override suspend fun <T> call(name: String, block: suspend () -> T): T {
            calls += name to onWorker()
            return block()
        }

        override fun <T> stream(name: String, flow: Flow<T>): Flow<T> = flow.onStart { calls += name to onWorker() }
    }

    private val photos = List(12) {
        Photo("/p$it.jpg", 1_000L + it, isVideo = false, contentId = "c$it", contentHash = if (it < 2) "same" else "")
    }

    private class Fixture(val scope: TestScope, val vms: OwnedViewModels, photos: List<Photo>, offCaller: Boolean) {
        val worker = Worker()
        val mock = MockPhotosRepository(photos = photos)
        val recording = Recording(mock) { worker.running }
        val repository: PhotosRepository = if (offCaller) OffCallerPhotosRepository(recording, worker) else recording

        /** Everything that can run now, runs — the caller's side, then the worker's, until both are quiet. */
        fun settle() {
            do scope.runCurrent() while (worker.runAll())
            scope.runCurrent()
        }
    }

    private fun screens(offCaller: Boolean = true, body: suspend Fixture.() -> Unit) {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        vmTest(main) { vms -> Fixture(this, vms, photos, offCaller).body() }
    }

    // ── the mechanism ───────────────────────────────────────────────────────────────────────────────

    /**
     * ⚠ The list is every suspending member of the interface. One left out of the decorator is a
     * compile error there (no `by` delegation); one left out HERE only goes unchecked, so the count at
     * the end is pinned to make adding a member without adding it here fail.
     */
    @Test
    fun everyCallRunsOnTheWorker_andNoneInTheCaller() = screens {
        val photo = photos[3]
        val every: List<suspend (PhotosRepository) -> Any?> = listOf(
            { it.feedPage(Long.MAX_VALUE, 0, 5) }, { it.feedCaptureTimes() }, { it.preview(photo.path, 64, 64) },
            { it.viewerPreview(photo.path, 256).toList() }, { it.ensureViewerRung(photo.path) }, { it.motionClip(photo.path) },
            { it.albums() }, { it.albumPhotos("a") }, { it.createAlbum("n") }, { it.renameAlbum("a", "b") },
            { it.deleteAlbum("a") }, { it.addToAlbum("a", photo) }, { it.removeFromAlbum("a", photo.contentId) },
            { it.albumsFor(photo.contentId) }, { it.favourites() }, { it.isFavourite(photo.contentId) },
            { it.setFavourite(photo.contentId, true) }, { it.places() }, { it.placesRead() }, { it.trash(photo) },
            { it.restore(photo.contentId) }, { it.deleteForever(photo.contentId) }, { it.trashed() }, { it.duplicates() },
            { it.deletePermanently(photos[4]) }, { it.holdsContent(photo.path, "x") }, { it.details(photo.path) },
            { it.photographicSettings(photo.path) }, { it.searchByName("p") }, { it.searchByLabel("p") },
            { it.searchByNameRead("p") }, { it.searchByLabelRead("p") }, { it.searchSemantic("p", 3) }, { it.allPhotos() },
            { it.allPhotosRead() }, { it.allLabels() }, { it.allLabelsRead() }, { it.faceGroups(5) },
            { it.nameFaceGroup(listOf(photo.path), "Ada") },
        )
        var finished = 0
        every.forEach { one ->
            val job = scope.launch { one(repository); finished++ }
            assertEquals(finished, recording.calls.size, "a call ran before the worker did")
            settle()
            assertFalse(job.isActive)
        }

        assertEquals(every.size, finished)
        assertEquals(emptyList(), recording.inTheCaller, "calls that ran in the caller")
        assertEquals(39, recording.names.toSet().size, "every member reached the repository, under its own name: ${recording.names}")
    }

    /** The contrast everything below is measured against: undecorated, the caller does the reading. */
    @Test
    fun withoutTheDecoratorACallRunsInsideTheCaller() = screens(offCaller = false) {
        vms.own(TrashViewModel(repository))

        assertEquals(listOf("trashed"), recording.names, "the read ran before the constructor returned")
        assertEquals(0, worker.waiting)
    }

    // ── each screen ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun anAlbumOpensWithoutReadingInTheCaller_andARemovalWritesOffIt() = screens {
        val id = mock.createAlbum("Family")!!
        photos.take(3).forEach { mock.addToAlbum(id, it) }

        val vm = vms.own(AlbumDetailViewModel(repository, id, "Family"))
        assertEquals(emptyList(), recording.names, "the constructor read in the caller")
        assertTrue(vm.state.value.loading)
        settle()
        assertEquals(3, vm.state.value.photos.size)

        vm.remove(photos[0])
        assertEquals(listOf("albumPhotos"), recording.names, "remove() wrote before it returned")
        settle()
        assertEquals(2, vm.state.value.photos.size)
        assertTrue("removeFromAlbum" in recording.names)
        assertEquals(emptyList(), recording.inTheCaller)
    }

    @Test
    fun favouritesOpenWithoutReadingInTheCaller() = screens {
        mock.setFavourite(photos[1].contentId, true)

        val vm = vms.own(AlbumDetailViewModel(repository, AlbumDetailViewModel.FAVOURITES, "Favourites"))
        assertEquals(emptyList(), recording.names)
        settle()

        assertEquals(listOf(photos[1].path), vm.state.value.photos.map { it.path })
        assertEquals(emptyList(), recording.inTheCaller)
    }

    @Test
    fun theTrashOpensAndRestoresWithoutTheCaller() = screens {
        mock.trash(photos[0])
        mock.trash(photos[1])

        val vm = vms.own(TrashViewModel(repository))
        assertEquals(emptyList(), recording.names, "the constructor read in the caller")
        settle()
        assertEquals(2, vm.state.value.items.size)

        vm.restore(photos[0].contentId)
        assertEquals(listOf("trashed"), recording.names, "restore() wrote before it returned")
        settle()
        assertEquals(listOf(photos[1].contentId), vm.state.value.items.map { it.contentId })
        assertTrue("restore" in recording.names)
        assertEquals(emptyList(), recording.inTheCaller)
    }

    @Test
    fun duplicatesOpenAndKeepOnlyWithoutTheCaller() = screens {
        val vm = vms.own(DuplicatesViewModel(repository))
        assertEquals(emptyList(), recording.names, "the constructor read in the caller")
        settle()
        val group = vm.state.value.groups.single()

        vm.keepOnly(vm.state.value.confirmation(group, group.photos[0])!!)
        assertEquals(listOf("duplicates"), recording.names, "keepOnly() read or wrote before it returned")
        settle()

        assertTrue(recording.names.containsAll(listOf("holdsContent", "trash")), "${recording.names}")
        assertEquals(emptyList(), vm.state.value.groups)
        assertEquals(emptyList(), recording.inTheCaller)
    }

    @Test
    fun aSearchReadsOnTheWorker_andAKeystrokeNeverWaitsForOne() = screens {
        val vm = vms.own(PhotoSearchViewModel(repository, descriptionSearch = DescriptionSearch.NotBuilt("iPhone")))

        vm.onQueryChanged("p1")
        scope.advanceTimeBy(PhotoSearchViewModel.SEARCH_DEBOUNCE_MS + 1)
        scope.runCurrent()
        assertEquals(emptyList(), recording.names, "the search read in the caller")
        assertEquals(1, worker.waiting, "the walk is waiting for the worker")
        // The next keystroke lands while that walk has not even started.
        vm.onQueryChanged("p11")
        assertEquals("p11", vm.state.value.query)
        settle()
        scope.advanceTimeBy(PhotoSearchViewModel.SEARCH_DEBOUNCE_MS + 1)
        settle()

        assertEquals(listOf("/p11.jpg"), vm.state.value.hits.map { it.photo.path })
        assertEquals(emptyList(), recording.inTheCaller)
    }

    /**
     * ⚠⚠ The order a phone can produce now and could not before: the OLDER query's walk finishes
     * AFTER the newer one's. On the main thread reads ran one at a time, in the order typed.
     */
    @Test
    fun anOlderSearchThatAnswersLastIsNeverPublishedOverANewerOne() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        vmTest(main) { vms ->
            val worker = Worker()
            val slow = CompletableDeferred<Unit>()
            var olderAnswered = false
            val inner = object : PhotosRepositoryDecorator(MockPhotosRepository(photos = photos)) {
                override suspend fun <T> call(name: String, block: suspend () -> T): T = block()
                override fun <T> stream(name: String, flow: Flow<T>): Flow<T> = flow
                override suspend fun searchByNameRead(query: String): List<Photo>? {
                    if (query == "p1") {
                        slow.await()
                        olderAnswered = true
                    }
                    return super.searchByNameRead(query)
                }
            }
            val vm = vms.own(
                PhotoSearchViewModel(OffCallerPhotosRepository(inner, worker), descriptionSearch = DescriptionSearch.NotBuilt("iPhone")),
            )
            fun settle() {
                do runCurrent() while (worker.runAll())
                runCurrent()
            }

            vm.onQueryChanged("p1")
            advanceTimeBy(PhotoSearchViewModel.SEARCH_DEBOUNCE_MS + 1)
            settle() // "p1" is now INSIDE its read, on the worker, and not answering.
            vm.onQueryChanged("p11")
            advanceTimeBy(PhotoSearchViewModel.SEARCH_DEBOUNCE_MS + 1)
            settle()
            assertEquals(listOf("/p11.jpg"), vm.state.value.hits.map { it.photo.path })

            slow.complete(Unit)
            settle()

            assertEquals(listOf("/p11.jpg"), vm.state.value.hits.map { it.photo.path }, "the older answer replaced the newer one")
            assertEquals("p11", vm.state.value.query)
            assertTrue(vm.state.value.settled)
            // ⚠ The older read was let go of when its search was superseded: it never went on to answer.
            assertFalse(olderAnswered, "a superseded search's read ran on to its answer")
        }
    }

    /** A read nobody is waiting for any more is not started (`boundedCall`). */
    @Test
    fun aReadWhoseCallerWasCancelledBeforeItStartedNeverRuns() = screens {
        val job = scope.launch { boundedCall(60_000) { repository.allPhotosRead().orEmpty() } }
        scope.runCurrent()
        assertEquals(1, worker.waiting)

        job.cancelAndJoin()
        settle()

        assertEquals(emptyList(), recording.names, "a walk of the library ran for a caller that had gone")
    }

    @Test
    fun theViewerOpensSwipesAndWritesWithoutTheCaller() = screens {
        val vm = vms.own(PhotoViewerViewModel(repository, photos[0]))
        assertEquals(emptyList(), recording.names, "the constructor read in the caller")
        settle()
        assertEquals(false, vm.state.value.favourite)

        vm.onPhotoShown(photos[1])
        vm.setInfoOpen(true)
        vm.toggleChrome()
        val before = recording.names.size
        settle()
        assertTrue(recording.names.drop(before).containsAll(listOf("isFavourite", "details", "photographicSettings", "albumsFor")))

        vm.toggleFavourite()
        val beforeWrite = recording.names.size
        assertEquals(true, vm.state.value.favourite, "the optimistic star")
        settle()
        assertEquals(listOf("setFavourite"), recording.names.drop(beforeWrite))
        assertEquals(true, vm.state.value.favourite)
        assertFalse(vm.state.value.writing)
        assertEquals(emptyList(), recording.inTheCaller)
    }

    @Test
    fun theFeedsYearIndexIsReadAndBuiltOnTheWorker_andABulkTrashWritesThere() = screens {
        val indexed = mutableListOf<Boolean>()
        val vm = vms.own(
            PhotosFeedViewModel(
                repository,
                sectionStartOf = { _, ms -> ms },
                yearOf = { indexed += worker.running; 2025 },
                firstPage = FeedFirstPage().also { it.markLanded() },
                workDispatcher = worker,
            ),
        )
        val collecting = scope.launch { vm.yearMarks.collect { } }
        scope.runCurrent()
        assertEquals(emptyList(), recording.names, "subscribing to the year index read in the caller")
        settle()

        assertEquals(listOf("feedCaptureTimes"), recording.names)
        assertEquals(photos.size, indexed.size)
        assertTrue(indexed.all { it }, "the year index was built in the caller")
        assertTrue(vm.yearMarks.value.isNotEmpty())

        vm.toggleSelection(photos[2])
        vm.toggleSelection(photos[3])
        vm.trashSelected()
        assertEquals(listOf("feedCaptureTimes"), recording.names, "trashSelected() wrote before it returned")
        assertTrue(vm.acting.value)
        settle()
        assertEquals(listOf("feedCaptureTimes", "trash", "trash"), recording.names)
        assertFalse(vm.acting.value)
        assertEquals(emptyMap(), vm.selected.value)
        assertEquals(emptyList(), recording.inTheCaller)
        collecting.cancelAndJoin()
    }

    /** `null` is every other shell: the index is built where it is collected, as it always was. */
    @Test
    fun withoutAWorkDispatcherTheYearIndexIsBuiltInTheCollector() = screens(offCaller = false) {
        var indexed = 0
        val vm = vms.own(
            PhotosFeedViewModel(
                repository,
                sectionStartOf = { _, ms -> ms },
                yearOf = { indexed++; 2025 },
                firstPage = FeedFirstPage().also { it.markLanded() },
            ),
        )
        val collecting = scope.launch { vm.yearMarks.collect { } }
        scope.runCurrent()

        assertEquals(photos.size, indexed)
        assertEquals(0, worker.waiting)
        collecting.cancelAndJoin()
    }
}
