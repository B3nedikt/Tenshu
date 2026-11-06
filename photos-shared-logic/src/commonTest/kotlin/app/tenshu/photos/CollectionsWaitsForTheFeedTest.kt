package app.tenshu.photos

import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.feed.FeedFirstPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Collections' first refresh, when the shell asks it to wait for the feed (`photos-app.md` §38,
 * fix 2). The wiring that asks is pinned in `:photos-shared-ui`'s `FeedFirstStartupTest`; this is
 * the view model's half — that waiting never costs the tab a load, and never costs a second walk.
 *
 * ⚠ Unconfined on purpose: Kotlin/Native's `viewModelScope` is `Main.immediate`, so a gate that has
 * already been marked runs its refresh inside the constructor — the order these must survive.
 */
class CollectionsWaitsForTheFeedTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Counting(private val inner: PhotosRepository) : PhotosRepository by inner {
        var albumReads = 0
        override suspend fun albums(): AlbumsResult = inner.albums().also { albumReads++ }
    }

    private fun repository() =
        Counting(MockPhotosRepository(photos = listOf(Photo("/a.jpg", 1_000L, isVideo = false, contentId = "a"))))

    @Test
    fun theFirstRefreshWaitsForTheFeedsFirstRows_thenRuns() = vmTest(dispatcher) { vms ->
        val repository = repository()
        val firstPage = FeedFirstPage()
        vms.own(CollectionsViewModel(repository, startAfter = firstPage))
        testScheduler.advanceUntilIdle()
        assertEquals(0, repository.albumReads, "nothing is read while the feed's first page is loading")

        firstPage.markLanded()
        testScheduler.advanceUntilIdle()
        assertEquals(1, repository.albumReads, "and the refresh runs the moment it lands")
    }

    /** ⚠⚠ The tab opened first: its own refresh is the first, and the deferred one never repeats it. */
    @Test
    fun aTabOpenedBeforeTheFeedLands_loadsAtOnce_andIsNotWalkedTwice() = vmTest(dispatcher) { vms ->
        val repository = repository()
        val firstPage = FeedFirstPage()
        val vm = vms.own(CollectionsViewModel(repository, startAfter = firstPage))

        vm.refresh() // `selectTop(Destination.Collections)` in `App`
        testScheduler.advanceUntilIdle()
        assertEquals(1, repository.albumReads, "opening the tab loads it, whatever the feed is doing")

        firstPage.markLanded()
        testScheduler.advanceUntilIdle()
        assertEquals(1, repository.albumReads, "the deferred first refresh is not a second walk")
    }

    /** A feed that has already landed (a shell recreated after its view models) does not hold it back. */
    @Test
    fun aFeedThatHasAlreadyLanded_holdsNothingBack() = vmTest(dispatcher) { vms ->
        val repository = repository()
        val firstPage = FeedFirstPage().apply { markLanded() }
        vms.own(CollectionsViewModel(repository, startAfter = firstPage))
        testScheduler.advanceUntilIdle()
        assertEquals(1, repository.albumReads)
    }
}
