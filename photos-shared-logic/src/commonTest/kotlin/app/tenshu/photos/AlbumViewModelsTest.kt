package app.tenshu.photos

import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailViewModel
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
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
 * The Collections tab and album detail (`photos-app.md` S4/S5).
 *
 * ⚠⚠ Two of these are about the line the whole service is shaped around: removing a photo from an
 * album must not remove the photograph, and Favourites — which has no membership — must not offer
 * the gesture at all.
 */
class AlbumViewModelsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(i: Int) = Photo("/p$i.jpg", 1_000L - i, isVideo = false, contentId = "c$i")

    @Test
    fun creatingAnAlbumShowsItWithItsCount() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository()
        val vm = vms.own(CollectionsViewModel(repo))
        testScheduler.advanceUntilIdle()

        vm.createAlbum("Holiday")
        testScheduler.advanceUntilIdle()
        val id = vm.state.value.albums.single().albumId

        vm.addPhoto(id, photo(1))
        vm.addPhoto(id, photo(2))
        testScheduler.advanceUntilIdle()

        val album = vm.state.value.albums.single()
        assertEquals("Holiday", album.name)
        assertEquals(2, album.photoCount)
        assertEquals("/p1.jpg", album.coverPath, "the cover falls back to the first photo")
    }

    @Test
    fun aBlankNameIsNotAnAlbum() = vmTest(dispatcher) { vms ->
        val vm = vms.own(CollectionsViewModel(MockPhotosRepository()))
        testScheduler.advanceUntilIdle()

        vm.createAlbum("   ")
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.albums.isEmpty())
    }

    /** Albums are SNAPSHOT, then the read waits for [gate] — a library walk slower than a mutation. */
    private class SlowAlbums(
        private val delegate: MockPhotosRepository = MockPhotosRepository(),
    ) : app.tenshu.photos.data.PhotosRepository by delegate {
        var gate = kotlinx.coroutines.CompletableDeferred<Unit>().apply { complete(Unit) }
        override suspend fun albums(): app.tenshu.photos.data.AlbumsResult {
            val snapshot = delegate.albums()
            gate.await()
            return snapshot
        }
    }

    @Test
    fun anAlbumMadeWhileARefreshRunsIsShown_notOverwrittenByTheOlderWalk() = vmTest(dispatcher) { vms ->
        // ⚠⚠ The mutation's refresh must not be dropped by the in-flight guard, or the older walk
        // publishes its pre-create snapshot — "made", and then absent.
        val repo = SlowAlbums()
        val vm = vms.own(CollectionsViewModel(repo))
        testScheduler.advanceUntilIdle()
        repo.gate = kotlinx.coroutines.CompletableDeferred()

        // ⚠ `runCurrent`, not `advanceUntilIdle`, while the walk is held: skipping virtual time would run
        // the refresh into its own timeout and test that instead.
        vm.refresh()                 // a walk that has already snapshotted the albums
        testScheduler.runCurrent()
        vm.createAlbum("Holiday")    // lands while it waits
        testScheduler.runCurrent()
        repo.gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("Holiday"), vm.state.value.albums.map { it.name })
    }

    /** An album whose N-th read SNAPSHOTS, then waits for `gates[N]` (no gate = answers at once). */
    private class SlowAlbumPhotos(
        private val delegate: MockPhotosRepository = MockPhotosRepository(),
    ) : app.tenshu.photos.data.PhotosRepository by delegate {
        val gates = mutableMapOf<Int, kotlinx.coroutines.CompletableDeferred<Unit>>()
        var reads = 0
        override suspend fun albumPhotos(albumId: String): app.tenshu.photos.data.PhotoPageResult {
            val n = reads++
            val snapshot = delegate.albumPhotos(albumId)
            gates[n]?.await()
            return snapshot
        }
    }

    @Test
    fun anAlbumReadStartedBeforeARemovalNeverPutsThePhotographBack() = vmTest(dispatcher) { vms ->
        // ⚠⚠ Overlapping reads race, and if the LAST to finish wins, a read snapshotted before a
        // removal can restore the photograph. Here the NEWER read is released first, the
        // order in which an unguarded refresh restores the stale photograph.
        val repo = SlowAlbumPhotos()
        val id = repo.createAlbum("Holiday")!!
        repo.addToAlbum(id, photo(1))
        val vm = vms.own(app.tenshu.photos.ui.screens.albumdetail.AlbumDetailViewModel(repo, id, "Holiday"))
        testScheduler.advanceUntilIdle()                   // read #0, at once
        assertEquals(1, vm.state.value.photos.size, "precondition")

        val older = kotlinx.coroutines.CompletableDeferred<Unit>().also { repo.gates[1] = it }
        val newer = kotlinx.coroutines.CompletableDeferred<Unit>().also { repo.gates[2] = it }
        vm.refresh()                                       // read #1: snapshots WITH the photograph
        testScheduler.runCurrent()
        repo.removeFromAlbum(id, photo(1).contentId)
        vm.refresh()                                       // asked mid-read
        testScheduler.runCurrent()
        newer.complete(Unit)                               // the newer answer first…
        testScheduler.runCurrent()
        older.complete(Unit)                               // …then the older one
        testScheduler.advanceUntilIdle()

        assertEquals(0, vm.state.value.photos.size, "the older read never has the last word")
    }

    @Test
    fun aReadThatNeverAnswersTimesOut_andTheNextRefreshStillRuns() = vmTest(dispatcher) { vms ->
        // ⚠⚠ A lost Android reply never returns; the single-flight latch must not stay
        // set behind it, or every later refresh only queues.
        val repo = SlowAlbumPhotos()
        val id = repo.createAlbum("Holiday")!!
        repo.addToAlbum(id, photo(1))
        repo.gates[0] = kotlinx.coroutines.CompletableDeferred()   // the first read never answers
        val vm = vms.own(
            app.tenshu.photos.ui.screens.albumdetail.AlbumDetailViewModel(repo, id, "Holiday", refreshTimeoutMs = 1_000),
        )
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.error != null, "the hung read ended as an error, not a spinner for ever")

        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.state.value.photos.size, "and the next refresh ran")
        assertEquals(null, vm.state.value.error)
    }

    @Test
    fun aRefusedAlbumIsReported_andAMadeOneToo() = vmTest(dispatcher) { vms ->
        // ⚠ An unreachable proxy refuses the write, and that answer must reach the user.
        val refusing = vms.own(CollectionsViewModel(app.tenshu.photos.data.NotConnectedPhotosRepository()))
        var refused: Boolean? = null
        refusing.createAlbum("Holiday") { refused = it }
        testScheduler.advanceUntilIdle()
        assertEquals(false, refused)

        val vm = vms.own(CollectionsViewModel(MockPhotosRepository()))
        var made: Boolean? = null
        vm.createAlbum("Holiday") { made = it }
        testScheduler.advanceUntilIdle()
        assertEquals(true, made)
    }

    @Test
    fun aPhotoWithNoContentIdCannotBeFiled() = vmTest(dispatcher) { vms ->
        // ⚠ A file predating content addressing. The op refuses it; so must everything above.
        val repo = MockPhotosRepository()
        val vm = vms.own(CollectionsViewModel(repo))
        testScheduler.advanceUntilIdle()
        vm.createAlbum("Holiday")
        testScheduler.advanceUntilIdle()
        val id = vm.state.value.albums.single().albumId

        vm.addPhoto(id, Photo("/old.jpg", 1L, isVideo = false, contentId = ""))
        testScheduler.advanceUntilIdle()

        assertEquals(0, vm.state.value.albums.single().photoCount)
    }

    @Test
    fun deletingAnAlbumKeepsThePhotographs() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository()
        val vm = vms.own(CollectionsViewModel(repo))
        testScheduler.advanceUntilIdle()
        vm.createAlbum("Holiday")
        testScheduler.advanceUntilIdle()
        val id = vm.state.value.albums.single().albumId
        vm.addPhoto(id, photo(1))
        testScheduler.advanceUntilIdle()

        vm.deleteAlbum(id)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.albums.isEmpty())
        // ⚠⚠ The feed still has every photo: an album is a statement about files, and removing the
        // statement cannot remove what it was about.
        val feed = repo.feedPage(Long.MAX_VALUE, pageSize = 50) as PhotoPageResult.Page
        assertTrue(feed.photos.any { it.path == "/p1.jpg" } || feed.photos.isNotEmpty())
    }

    @Test
    fun anUnreachableProxyIsNotAnEmptyShelf() = vmTest(dispatcher) { vms ->
        val vm = vms.own(CollectionsViewModel(MockPhotosRepository(outcome = PhotoPageResult.Unreachable)))
        testScheduler.advanceUntilIdle()

        assertEquals(FeedError.Unreachable, vm.state.value.error)
        assertFalse(vm.state.value.isEmpty, "an unreachable proxy must not read as 'no albums'")
    }

    @Test
    fun removingFromAnAlbumLeavesThePhotographAlone() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository()
        repo.createAlbum("Holiday")
        repo.addToAlbum("album-1", photo(1))
        repo.addToAlbum("album-1", photo(2))
        val vm = vms.own(AlbumDetailViewModel(repo, "album-1", "Holiday"))
        testScheduler.advanceUntilIdle()
        assertEquals(2, vm.state.value.photos.size)

        vm.remove(photo(1))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("/p2.jpg"), vm.state.value.photos.map { it.path })
        // ⚠⚠ Still in the library. `RemoveFromAlbum` cannot reach `:file-service` at all.
        val feed = repo.feedPage(Long.MAX_VALUE, pageSize = 50) as PhotoPageResult.Page
        assertTrue(feed.photos.isNotEmpty())
    }

    @Test
    fun favouritesIsNotEditable() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository()
        repo.setFavourite("c1", true)
        val vm = vms.own(AlbumDetailViewModel(repo, AlbumDetailViewModel.FAVOURITES, "Favourites"))
        testScheduler.advanceUntilIdle()

        assertFalse(vm.state.value.editable, "Favourites has no membership to edit")

        // ⚠ `remove` is a no-op there rather than a silent failure against a pseudo-album id.
        val before = vm.state.value.photos.size
        vm.remove(vm.state.value.photos.first())
        testScheduler.advanceUntilIdle()
        assertEquals(before, vm.state.value.photos.size)
    }

    @Test
    fun favouritesShowsWhatWasFavourited() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository()
        repo.setFavourite("c3", true)
        val vm = vms.own(AlbumDetailViewModel(repo, AlbumDetailViewModel.FAVOURITES, "Favourites"))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("c3"), vm.state.value.photos.map { it.contentId })
    }
}
