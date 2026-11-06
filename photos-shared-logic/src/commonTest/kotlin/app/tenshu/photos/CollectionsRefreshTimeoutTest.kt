package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.ui.screens.collections.SectionLoad
import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.FaceGroup
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.feed.FeedError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest

/**
 * One read that never answers must not wedge the Collections tab (`tech-debt.md` *"one lost reply
 * wedges the tab"*): the tab would spin for minutes and ignore every refresh until Photos was
 * killed.
 */
class CollectionsRefreshTimeoutTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /**
     * One read hangs while [hang] is set — the shape of a lost IPC reply. Which one is [hangOn]:
     * `"faceGroups"` (People's read) or `"albums"` (the cheap half).
     *
     * ⚠⚠ **Uncancellably**, as the real transports do: Binder's `proxy.call` blocks
     * past cancellation, and the desktop socket against an older Control waits in `NonCancellable`. A fake that honoured
     * cancellation passes against a fix that can never fire on a device.
     */
    private class HangingRepository(
        private val inner: PhotosRepository,
        private val hangOn: String = "faceGroups",
    ) : PhotosRepository by inner {
        var hang = true
        var libraryReads = 0
        override suspend fun allPhotosRead(): List<Photo>? = inner.allPhotosRead().also { libraryReads++ }
        private val lostReply = CompletableDeferred<Unit>()

        /** How many hung reads were CANCELLED — a cooperative read sees it, as a library walk would. */
        var cancelled = 0
        var cooperative = false

        /** The reply finally arrives — so the abandoned read can end and the test can tear down. */
        fun release() {
            lostReply.complete(Unit)
        }

        private suspend fun maybeHang(read: String) {
            if (!hang || read != hangOn) return
            if (cooperative) {
                try {
                    lostReply.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled++
                    throw e
                }
            } else {
                withContext(NonCancellable) { lostReply.await() }
            }
        }

        override suspend fun faceGroups(limit: Int): Pair<List<FaceGroup>, PeopleStatus> {
            maybeHang("faceGroups")
            return inner.faceGroups(limit)
        }

        override suspend fun albums(): AlbumsResult {
            maybeHang("albums")
            return inner.albums()
        }
    }

    private val photo = Photo("/a.jpg", 1_000L, isVideo = false, contentId = "a")

    private fun vm(vms: OwnedViewModels, repository: PhotosRepository) = vms.own(
        CollectionsViewModel(repository, refreshTimeoutMs = 5_000),
    )

    /**
     * §36: a read that never answers fails only its own section — and the sections that do not need
     * it are drawn at once, not after the budget runs out.
     */
    @Test
    fun aHungPeopleReadFailsPeopleAlone_andTheRestIsDrawnAtOnce() = vmTest(dispatcher) { vms ->
        val repository = HangingRepository(MockPhotosRepository(photos = listOf(photo)))
        try {
            val vm = vm(vms, repository)
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            assertEquals(listOf("1970"), vm.state.value.years.map { it.title }, "years drawn while People still waits")
            assertEquals(SectionLoad.LOADING, vm.state.value.peopleLoad)
            assertTrue(vm.state.value.loading, "still inside the budget")

            testScheduler.advanceTimeBy(6_000)
            testScheduler.runCurrent()

            assertFalse(vm.state.value.loading)
            assertEquals(SectionLoad.FAILED, vm.state.value.peopleLoad, "People's read failed")
            assertEquals(SectionLoad.LOADED, vm.state.value.libraryLoad)
            assertNull(vm.state.value.error, "and only People's — the tab is not in error")
        } finally {
            repository.release()
        }
    }

    /** ⚠ A hung albums read must not hold back the library's sections either. */
    @Test
    fun aHungAlbumsReadDoesNotHoldBackYearsOrPeople() = vmTest(dispatcher) { vms ->
        val repository = HangingRepository(MockPhotosRepository(photos = listOf(photo)), hangOn = "albums")
        try {
            val vm = vm(vms, repository)
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            assertEquals(listOf("1970"), vm.state.value.years.map { it.title })
            assertEquals(SectionLoad.LOADED, vm.state.value.peopleLoad)

            testScheduler.advanceTimeBy(6_000)
            testScheduler.runCurrent()
            assertEquals(SectionLoad.FAILED, vm.state.value.albumsLoad, "the Albums row says so")
            assertNull(vm.state.value.error, "and only the Albums row — not the whole tab")
        } finally {
            repository.release()
        }
    }

    /**
     * ⚠⚠ After the hang, `inFlight` must not drop every refresh.
     *
     * ⚠ Models a transport where the abandoned read does not block the retry — hence `release()`
     * before the retry. That holds on Android (a fresh Binder call on its own thread) and on desktop
     * (correlated calls, `desktop-ipc.md` §2.2, pinned by `UnixSocketLostReplyTest`); it does not
     * against an older desktop Control, which serialises calls behind the lost one.
     */
    @Test
    fun tryAgainAfterATimeoutActuallyReads() = vmTest(dispatcher) { vms ->
        val repository = HangingRepository(MockPhotosRepository(photos = listOf(photo)))
        val vm = vm(vms, repository)
        testScheduler.advanceTimeBy(6_000)
        testScheduler.runCurrent()
        assertEquals(SectionLoad.FAILED, vm.state.value.peopleLoad)

        repository.hang = false
        repository.release()
        vm.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(SectionLoad.LOADED, vm.state.value.peopleLoad)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    /** A failed People read after a good load keeps what was drawn and marks the section failed. */
    @Test
    fun aTimeoutAfterAGoodLoadKeepsTheContentAndReportsTheFailure() = vmTest(dispatcher) { vms ->
        val repository = HangingRepository(MockPhotosRepository(photos = listOf(photo)))
        repository.hang = false
        try {
            val vm = vm(vms, repository)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("1970"), vm.state.value.years.map { it.title })

            repository.hang = true
            vm.refresh()
            testScheduler.advanceTimeBy(6_000)
            testScheduler.runCurrent()

            assertFalse(vm.state.value.loading)
            assertEquals(SectionLoad.FAILED, vm.state.value.peopleLoad, "the failure is reported")
            assertEquals(listOf("1970"), vm.state.value.years.map { it.title }, "and the loaded content stays")
        } finally {
            repository.release()
        }
    }

    /** Favourites starts with albums, so a hung albums read does not hold its tile. */
    @Test
    fun favouritesLandsWhileAlbumsHangs() = vmTest(dispatcher) { vms ->
        val inner = MockPhotosRepository(photos = listOf(photo))
        inner.setFavourite(photo.contentId!!, true)
        val repository = HangingRepository(inner, hangOn = "albums")
        try {
            val vm = vm(vms, repository)
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            assertEquals(1, vm.state.value.favouriteCount)
        } finally {
            repository.release()
        }
    }

    /**
     * *Try again* must start a read even while a sibling's read is still inside its
     * budget — `refresh()` alone is dropped while one is in flight.
     */
    @Test
    fun retryStartsANewReadWhileAnotherIsStillInFlight() = vmTest(dispatcher) { vms ->
        val repository = HangingRepository(MockPhotosRepository(photos = listOf(photo)))
        try {
            val vm = vm(vms, repository)
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            assertEquals(SectionLoad.LOADING, vm.state.value.peopleLoad, "People's read is hung")

            repository.hang = false
            vm.retry()
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()

            assertEquals(SectionLoad.LOADED, vm.state.value.peopleLoad, "the retry read at once, not after the budget")
        } finally {
            repository.release()
        }
    }

    /** A retry cancels the reads of the refresh it abandons, rather than leaving them running. */
    @Test
    fun retryCancelsTheAbandonedRefreshsReads() = vmTest(dispatcher) { vms ->
        val repository = HangingRepository(MockPhotosRepository(photos = listOf(photo)))
        repository.cooperative = true
        try {
            val vm = vm(vms, repository)
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()

            vm.retry()
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()

            assertTrue(repository.cancelled >= 1, "the first refresh's hung read was cancelled, not left running")
        } finally {
            repository.release()
        }
    }

    /** A retry supersedes a rerun queued meanwhile — it must not walk the library twice. */
    @Test
    fun aRetryDropsTheRerunItSupersedes() = vmTest(dispatcher) { vms ->
        val repository = HangingRepository(MockPhotosRepository(photos = listOf(photo)))
        try {
            val vm = vm(vms, repository)
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            vm.refresh() // queued behind the hung People read
            assertEquals(1, repository.libraryReads)

            repository.hang = false
            vm.retry()
            testScheduler.advanceUntilIdle()

            assertEquals(2, repository.libraryReads, "the retry walked the library once — no extra rerun after it")
        } finally {
            repository.release()
        }
    }
}
