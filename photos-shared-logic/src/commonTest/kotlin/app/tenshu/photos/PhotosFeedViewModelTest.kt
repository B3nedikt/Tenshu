package app.tenshu.photos

import androidx.paging.testing.asSnapshot
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.model.FeedItem
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.feed.PhotosFeedViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest

/**
 * What is left of this class to test, and what is not.
 *
 * ⚠⚠ **This file used to have thirteen tests and now has four, and that is a move rather than a
 * loss.** `PhotosFeedViewModel` was a hand-rolled keyset pager (`core-keyset-paging.md` K-4), so
 * most of its tests were about paging rather than about Photos:
 *
 * | Was tested here | Lives now in |
 * |---|---|
 * | first page, cursor advance, short page ends the feed | `KeysetPagingSourceTest`, verified by mutation |
 * | photographs sharing a capture time; a page wholly inside one millisecond | ditto — the tie tests |
 * | a fling cannot issue two pages at once | Paging's own load state; there is no in-flight guard to test |
 * | unreachable / failed / empty are three different screens | `PhotosFeedScreenTest`, which pins the pixels rather than a flag |
 *
 * ⚠⚠ **One thing genuinely lost a test: the resume refresh.** `refreshNewest` had three, and its
 * replacement is the chain's invalidation — which this module cannot exercise, because
 * [MockPhotosRepository] serves a static list and the real signal needs a store. It is verified on
 * a device instead (take a photograph, return to the app), and that is written down rather than
 * implied.
 *
 * What remains is the one paging-shaped decision that IS Photos': where the day headers go.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotosFeedViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val day = 86_400_000L

    private fun dayStart(ms: Long) = ms / day * day

    private fun photo(id: String, at: Long) =
        Photo(path = "/Photos/$id.jpg", capturedAtMs = at, isVideo = false, contentId = id)

    private fun feed(vms: OwnedViewModels, photos: List<Photo>) =
        // ⚠ Groups by day whatever the density: these assert the separator RULE, and a
        // fixture that also varied the grouping would be testing two things at once.
        vms.own(
            PhotosFeedViewModel(
                MockPhotosRepository(photos = photos),
                sectionStartOf = { _, ms -> dayStart(ms) },
            ),
        )

    @Test
    fun everyDayGetsExactlyOneHeader_beforeItsFirstPhotograph() = vmTest { vms ->
        val items = feed(vms, 
            listOf(
                photo("a", 3 * day + 5_000),
                photo("b", 3 * day + 1_000),
                photo("c", 1 * day + 9_000),
            )
        ).items.asSnapshot()

        assertEquals(
            listOf("day-3", "a", "b", "day-1", "c"),
            items.map { it.label() },
        )
    }

    /**
     * ⚠ The property the old whole-list grouping pass existed to guarantee, kept: `insertSeparators`
     * compares the last row of one page with the first of the next, so the comparison spans the
     * seam rather than restarting at it.
     *
     * ⚠⚠ The fixture puts a day boundary **inside** a page and another **on** one, because a rule
     * that only ever sees whole days per page cannot fail the way this one could.
     */
    @Test
    fun aDayStraddlingAPageBoundaryStillGetsOneHeader() = vmTest { vms ->
        // 40 photographs, one per hour, so a day spans 24 of them and pages are 20.
        val photos = List(40) { i -> photo("p$i", 10 * day - i * 3_600_000L) }

        val labels = feed(vms, photos).items.asSnapshot().map { it.label() }
        val headers = labels.filter { it.startsWith("day-") }

        assertEquals(headers, headers.distinct(), "a day must not be announced twice")
        assertEquals(
            photos.map { dayStart(it.capturedAtMs) }.distinct().map { "day-" + it / day },
            headers,
            "and every day present must be announced once, in order",
        )
    }

    @Test
    fun aHeaderNeverTrailsTheLastPhotograph() = vmTest { vms ->
        val labels = feed(vms, listOf(photo("only", 5 * day))).items.asSnapshot().map { it.label() }

        assertEquals(listOf("day-5", "only"), labels)
        assertTrue(!labels.last().startsWith("day-"), "a trailing header would label nothing")
    }

    @Test
    fun anEmptyLibraryProducesNoHeaders() = vmTest { vms ->
        assertEquals(emptyList(), feed(vms, emptyList()).items.asSnapshot())
    }

    private fun FeedItem.label(): String = when (this) {
        is FeedItem.DayHeader -> "day-" + dayStartMs / 86_400_000L
        is FeedItem.Tile -> photo.contentId
    }
}
