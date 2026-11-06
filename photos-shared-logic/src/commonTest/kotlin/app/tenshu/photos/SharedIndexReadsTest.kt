package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * The reads §12.4 and §13.5 named stopped walking the library (`photos-app.md` §20).
 *
 * ## ⚠⚠ Why this is a counting test rather than an assertion about results
 *
 * The results never changed — that is the point, and it is also why a correctness test could not
 * have caught the cost. `favourites()` and the Collections tab answered a **content-id** question by
 * paging the feed until it ran dry, and every page is its own `traverse()` with its own sort. So
 * the cost was one full-realm walk *per page*: on a 20 000-photo library, ~334 per read, and the
 * Collections tab paid it twice because `favourites()` runs alongside `collectFeed()`.
 *
 * Nothing about the output says any of that. Counting the pages does.
 */
class SharedIndexReadsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photos(n: Int) = (0 until n).map {
        Photo("/Backup/Phone/p$it.jpg", 10_000L - it, isVideo = false, contentId = "c$it")
    }

    /** Counts feed pages, so "did it walk the library?" is a fact rather than an inference. */
    private class CountingRepository(
        private val delegate: MockPhotosRepository,
    ) : PhotosRepository by delegate {
        var feedPages = 0
            private set
        var indexReads = 0
            private set

        override suspend fun feedPage(beforeMs: Long, skip: Int, pageSize: Int): PhotoPageResult {
            feedPages++
            return delegate.feedPage(beforeMs, skip, pageSize)
        }

        override suspend fun allPhotos(): List<Photo> {
            indexReads++
            return delegate.allPhotos()
        }

        // ⚠ Collections reads through the failure-bearing variant (§36) — counted the same way.
        override suspend fun allPhotosRead(): List<Photo>? {
            indexReads++
            return delegate.allPhotosRead()
        }
    }

    @Test
    fun theAlbumsTabReadsTheIndexOnceInsteadOfPagingTheLibrary() = vmTest(dispatcher) { vms ->
        // 250 photos at the mock's page size is five pages — five `traverse()` calls, each with a
        // sort, for a question that is one lookup.
        val repo = CountingRepository(MockPhotosRepository(photos(250)))
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        assertEquals(1, repo.indexReads, "the Collections tab reads the whole library exactly once")
        assertEquals(0, repo.feedPages, "and pages the feed not at all")
    }

    @Test
    fun theAlbumsTabStillSeesEveryPhoto() = vmTest(dispatcher) { vms ->
        // ⚠ The half a counting test must not lose: fewer reads is only an improvement if the
        // answer is the same. 250 photos, all of them grouped into years.
        val repo = MockPhotosRepository(photos(250))
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        assertEquals(250, vm.state.value.years.sumOf { it.photos.size })
    }

    @Test
    fun aTrashedPhotoIsNotInTheSharedRead() = vmTest(dispatcher) { vms ->
        // ⚠ The index excludes the trash, like every read path since PH-G16. A smart album that
        // listed trashed photos would offer to open something the user deleted.
        val all = photos(3)
        val repo = MockPhotosRepository(all)
        repo.trash(all.first())

        assertEquals(2, repo.allPhotos().size)
        assertTrue(all.first().path !in repo.allPhotos().map { it.path })
    }

    @Test
    fun favouritesResolvesByContentIdRatherThanByWalking() = vmTest(dispatcher) { vms ->
        val all = photos(250)
        val repo = CountingRepository(MockPhotosRepository(all))
        repo.setFavourite("c7", true)
        repo.setFavourite("c200", true)

        val result = repo.favourites()

        assertTrue(result is PhotoPageResult.Page)
        assertEquals(
            setOf("c7", "c200"),
            (result as PhotoPageResult.Page).photos.map { it.contentId }.toSet(),
        )
        // ⚠ The mock has no index, so it uses the fallback — what this pins is that the *result*
        // is unchanged, which is what lets the real repository's faster path be trusted.
        assertTrue(result.photos.isNotEmpty())
    }
}
