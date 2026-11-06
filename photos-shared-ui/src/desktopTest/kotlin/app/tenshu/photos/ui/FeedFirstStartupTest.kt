package app.tenshu.photos.ui

import androidx.paging.PagingData
import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.di.viewModelsModule
import app.tenshu.photos.model.FaceGroup
import app.tenshu.photos.model.FeedItem
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoLabel
import app.tenshu.photos.model.PhotoPlace
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.feed.PhotosFeedViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.core.paging.TenshuPagingPresenter
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The feed's first page is the only thing a launch asks Control for until it lands
 * (`photos-app.md` §38, fix 2).
 *
 * ⚠⚠ Built from the app's OWN Koin module, not by hand: the defect was wiring — the Collections view
 * model is created at the shell and refreshed in `init` on every launch, on the Photos tab — and a
 * test that constructs the view models itself would pass whatever the module does. On the maintainer's
 * phone those side reads (the scrubber's whole-library capture times, the content index's whole-realm
 * projection, Collections' six reads) were issued within ~0.2 s of launch and shared Control with the
 * first page; holding them back measured −4 s to the first rows (§37.2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedFirstStartupTest {

    // ⚠ Unconfined, as `PhotosFeedViewModelTest` uses: under a StandardTestDispatcher the view
    // model's `Main.immediate` scope never ran the paged stream at all, and the test "passed" its
    // first assertion for the wrong reason.
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Every read a launch can make, in order; the feed's page is held until [firstPage] completes. */
    private class Recording(private val inner: PhotosRepository) : PhotosRepository by inner {
        val reads = mutableListOf<String>()
        val firstPage = CompletableDeferred<Unit>()

        override fun feedPaging(pageSize: Int, readContext: kotlin.coroutines.CoroutineContext): Flow<PagingData<Photo>> = flow {
            firstPage.await()
            emitAll(inner.feedPaging(pageSize))
        }

        override suspend fun feedCaptureTimes(): List<Long>? = inner.feedCaptureTimes().also { reads += "captureTimes" }
        override suspend fun albums(): AlbumsResult = inner.albums().also { reads += "albums" }
        override suspend fun favourites(): PhotoPageResult = inner.favourites().also { reads += "favourites" }
        override suspend fun allPhotosRead(): List<Photo>? = inner.allPhotosRead().also { reads += "allPhotos" }
        override suspend fun placesRead(): List<PhotoPlace>? = inner.placesRead().also { reads += "places" }
        override suspend fun allLabelsRead(): List<PhotoLabel>? = inner.allLabelsRead().also { reads += "labels" }
        override suspend fun faceGroups(limit: Int): Pair<List<FaceGroup>, PeopleStatus> =
            inner.faceGroups(limit).also { reads += "faceGroups" }
    }

    @Test
    fun aLaunchOnThePhotosTab_readsNothingElseUntilTheFeedsFirstRowsLand() = vmTest(dispatcher) { vms ->
        val repository = Recording(
            MockPhotosRepository(photos = listOf(Photo("/a.jpg", 1_000L, isVideo = false, contentId = "a"))),
        )
        val koin = koinApplication {
            modules(module { single<PhotosRepository> { repository } }, viewModelsModule())
        }.koin

        // What `App` does on launch: both view models at the shell, the feed's rows and the
        // scrubber's marks collected at once.
        val feed = vms.own(koin.get<PhotosFeedViewModel>())
        vms.own(koin.get<CollectionsViewModel>())
        backgroundScope.launch { feed.yearMarks.collect {} }
        val rows = TenshuPagingPresenter<FeedItem>(backgroundScope, mainContext = dispatcher)
        rows.collect(feed.items)
        advanceUntilIdle()

        assertEquals(emptyList(), repository.reads, "before the first page lands, the feed is the only reader")

        repository.firstPage.complete(Unit)
        advanceUntilIdle()

        assertTrue(rows.snapshot.value.isNotEmpty(), "the first rows landed")
        assertTrue("captureTimes" in repository.reads, "the scrubber's marks follow the rows: ${repository.reads}")
        assertTrue("albums" in repository.reads, "and so does Collections — its albums feed the add-to-album sheet")
    }
}
