package app.tenshu.photos

import androidx.paging.LoadState
import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.DuplicatesResult
import app.tenshu.photos.data.ExifReadResult
import app.tenshu.photos.data.FeedReadException
import app.tenshu.photos.data.NotConnectedPhotosRepository
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.TrashResult
import app.tenshu.photos.model.NamingOutcome
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.SemanticSearchStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.tenshu.core.paging.TenshuPagingPresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shipping default of an unprovisioned shell (PIO-0). ⚠⚠ The property under test is
 * *unreachable, never empty* — `photos-app.md` §10.3: "no photos yet" for a library the app simply
 * cannot see tells the user their photographs are gone.
 */
class NotConnectedPhotosRepositoryTest {

    private val repository = NotConnectedPhotosRepository()
    private val photo = Photo(path = "/Photos/a.jpg", capturedAtMs = 1L, isVideo = false, contentId = "a")

    @Test
    fun it_says_it_cannot_reach_control() = runTest {
        assertEquals(false, repository.controlReachable().first())
        // Unknown, not NotInstalled: absence must be known, never inferred (UnreachableReason).
        assertNull(repository.controlUnreachableReason().first())
    }

    @Test
    fun the_feed_fails_as_unreachable_rather_than_presenting_an_empty_library() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        try {
            val presenter = TenshuPagingPresenter<Photo>(scope, dispatcher)
            presenter.collect(repository.feedPaging())
            advanceUntilIdle()
            val refresh = presenter.loadState.value?.refresh
            assertIs<LoadState.Error>(refresh, "an empty success here is the §10.3 lie")
            val cause = assertIs<FeedReadException>(refresh.error)
            assertTrue(cause.unreachable, "the screen must say 'can't reach', not 'failed'")
        } finally {
            scope.cancel()
        }
        assertNull(repository.feedCaptureTimes(), "no scrubber over a library that was never read")
    }

    @Test
    fun every_typed_read_answers_unreachable() = runTest {
        assertEquals(PhotoPageResult.Unreachable, repository.feedPage(0L, 0, 60))
        assertEquals(PhotoPageResult.Unreachable, repository.albumPhotos("x"))
        assertEquals(PhotoPageResult.Unreachable, repository.favourites())
        assertEquals(AlbumsResult.Unreachable, repository.albums())
        assertEquals(TrashResult.Unreachable, repository.trashed())
        assertEquals(DuplicatesResult.Unreachable, repository.duplicates())
        assertEquals(ExifReadResult.Unreachable, repository.photographicSettings("/Photos/a.jpg"))
        assertEquals(SemanticSearchStatus.Failed, repository.searchSemantic("dog", 10).second)
        // ⚠ Nothing was searched — `null`, never an empty list the Search screen would call "no photos match".
        assertNull(repository.searchByNameRead("dog"))
        assertNull(repository.searchByLabelRead("dog"))
        assertEquals(PeopleStatus.Failed, repository.faceGroups(10).second)
    }

    @Test
    fun every_write_is_refused() = runTest {
        assertNull(repository.createAlbum("Holiday"))
        assertFalse(repository.renameAlbum("x", "y"))
        assertFalse(repository.deleteAlbum("x"))
        assertFalse(repository.addToAlbum("x", photo))
        assertFalse(repository.removeFromAlbum("x", "a"))
        assertFalse(repository.setFavourite("a", true))
        assertFalse(repository.trash(photo))
        assertFalse(repository.restore("a"))
        assertFalse(repository.deleteForever("a"))
        assertFalse(repository.deletePermanently(photo))
        assertEquals(NamingOutcome.Failed, repository.nameFaceGroup(listOf("/Photos/a.jpg"), "Ada"))
    }
}
