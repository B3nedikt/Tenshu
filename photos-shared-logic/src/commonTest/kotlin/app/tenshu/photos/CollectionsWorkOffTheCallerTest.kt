package app.tenshu.photos

import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.FaceGroup
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoLabel
import app.tenshu.photos.model.PhotoPlace
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.collections.ReadTimings
import app.tenshu.photos.ui.screens.collections.SectionLoad
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.viewmodel.test.vmTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A refresh's reads and its regrouping run on the dispatcher the shell names, never in the caller
 * (`photos-ios.md` §8.22). On iOS the caller is the main thread and a read is a store query on the
 * thread that calls it: all six, and the grouping of the whole library, held it for 1.65 s on every
 * return to the Collections tab.
 */
class CollectionsWorkOffTheCallerTest {

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Marks what runs on it — the stand-in for "not the main thread". */
    private class Worker(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        var running = false
        override fun dispatch(context: CoroutineContext, block: Runnable) =
            inner.dispatch(context, Runnable {
                running = true
                try { block.run() } finally { running = false }
            })
    }

    /** Every read, and where it ran. */
    private class Recording(private val inner: PhotosRepository, private val onWorker: () -> Boolean) : PhotosRepository by inner {
        val reads = mutableListOf<Pair<String, Boolean>>()
        private fun note(name: String) { reads += name to onWorker() }
        override suspend fun albums(): AlbumsResult { note("albums"); return inner.albums() }
        override suspend fun favourites(): PhotoPageResult { note("favourites"); return inner.favourites() }
        override suspend fun allPhotosRead(): List<Photo>? { note("allPhotos"); return inner.allPhotosRead() }
        override suspend fun placesRead(): List<PhotoPlace>? { note("places"); return inner.placesRead() }
        override suspend fun allLabelsRead(): List<PhotoLabel>? { note("allLabels"); return inner.allLabelsRead() }
        override suspend fun faceGroups(limit: Int): Pair<List<FaceGroup>, PeopleStatus> { note("faceGroups"); return inner.faceGroups(limit) }
    }

    private val photos = List(40) { Photo("/p$it.jpg", 1_000L + it, isVideo = false, contentId = "c$it") }

    private val allSix = setOf("albums", "favourites", "allPhotos", "places", "allLabels", "faceGroups")

    /**
     * ⚠⚠ The dispatchers are two QUEUES on one scheduler, and the main one is not unconfined: an
     * unconfined main resumes inside the worker's own block, where work the view model wrongly did
     * after an `await` would be marked as the worker's and pass.
     */
    @Test
    fun everyReadAndTheGroupingRunOnTheWorker_andTheStateStillArrives() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        vmTest(main) { vms ->
            val worker = Worker(StandardTestDispatcher(testScheduler))
            val repository = Recording(MockPhotosRepository(photos = photos)) { worker.running }
            val grouped = mutableListOf<Boolean>()
            val vm = vms.own(
                CollectionsViewModel(
                    repository,
                    yearOf = { grouped += worker.running; 2025 },
                    workDispatcher = worker,
                ),
            )
            testScheduler.advanceUntilIdle()

            assertEquals(allSix, repository.reads.map { it.first }.toSet(), "all six reads ran")
            assertEquals(emptyList(), repository.reads.filterNot { it.second }.map { it.first }, "reads that ran in the caller")
            assertEquals(photos.size, grouped.size, "the library was grouped by year")
            assertTrue(grouped.all { it }, "the grouping ran in the caller")
            assertEquals(SectionLoad.LOADED, vm.state.value.libraryLoad)
            assertEquals(listOf("2025"), vm.state.value.years.map { it.title })
            assertEquals(photos.size, vm.state.value.years.single().photos.size)
        }
    }

    /**
     * What a return to the tab is: `refresh()` called on the main thread. ⚠ An IMMEDIATE main, as
     * Kotlin/Native's `viewModelScope` is — the dispatcher under which the reads ran inside the call.
     */
    @Test
    fun aRefreshReturnsBeforeAnyReadHasRun() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        vmTest(main) { vms ->
            val worker = Worker(StandardTestDispatcher(testScheduler))
            val repository = Recording(MockPhotosRepository(photos = photos)) { worker.running }
            val vm = vms.own(CollectionsViewModel(repository, workDispatcher = worker))
            assertEquals(emptyList(), repository.reads, "the constructor's refresh read nothing in the caller")
            testScheduler.advanceUntilIdle()
            repository.reads.clear()

            vm.refresh()

            assertEquals(emptyList(), repository.reads, "refresh() read nothing before it returned")
            testScheduler.advanceUntilIdle()
            assertEquals(allSix, repository.reads.map { it.first }.toSet())
        }
    }

    /** The contrast the test above is measured against: without a worker the caller does the reading. */
    @Test
    fun withoutAWorkerTheReadsRunInsideTheCall() {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        vmTest(main) { vms ->
            val repository = Recording(MockPhotosRepository(photos = photos)) { false }
            vms.own(CollectionsViewModel(repository))

            assertEquals(allSix, repository.reads.map { it.first }.toSet(), "every read ran before the constructor returned")
        }
    }

    // ── the timing line ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aReadAnsweredWhenItFinishedIsOneFigure() {
        val timings = ReadTimings(readsOffCaller = true)
        timings.record("allPhotos", ranMs = 1_203, answeredMs = 1_210, failed = false, timedOut = false)
        timings.record("albums", ranMs = 41, answeredMs = 44, failed = false, timedOut = false)

        assertEquals("reads=background allPhotos=1203ms albums=41ms", timings.report())
    }

    /** The phone's line, 2026-10-10: six reads "took" 1 632 ms each. They did not; the thread was held. */
    @Test
    fun aReadWhoseAnswerWaitedSaysSo() {
        val timings = ReadTimings(readsOffCaller = false)
        timings.record("allPhotos", ranMs = 1_200, answeredMs = 1_632, failed = false, timedOut = false)
        timings.record("albums", ranMs = 40, answeredMs = 1_632, failed = false, timedOut = false)
        timings.record("places", ranMs = 30, answeredMs = 1_632, failed = true, timedOut = false)
        timings.record("faceGroups", ranMs = 20_000, answeredMs = 20_000, failed = true, timedOut = true)

        assertEquals(
            "reads=caller allPhotos=1200ms(answered after 1632ms) albums=40ms(answered after 1632ms) " +
                "places=30ms(FAILED)(answered after 1632ms) faceGroups=20000ms(TIMED OUT)",
            timings.report(),
        )
    }
}
