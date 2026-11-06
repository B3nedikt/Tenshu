package app.tenshu.drive.ui.common.files

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [shouldFollowHeadInsert] — the rule that keeps a reader parked at the top of a sorted listing
 * looking at the head when rows are inserted above them (`drive-sorted-folder-paging.md` §10.6).
 *
 * A keyed `LazyColumn` anchors on the row it is showing, so a prepend leaves the new row *above*
 * the viewport. Under "Date modified ↓" that is every created folder and every backed-up photo.
 * The counter-risk is scrolling a reader who is deep in the list back to 0, so each case below
 * pins one half of the boundary.
 */
class PagedFilesListHeadInsertTest {

    @Test
    fun followsAPrependWhenTheReaderWasParkedAtTheTop() {
        // Was [b, c]; `a` arrives at the head, so the old head `b` is now at index 1 and the
        // viewport is anchored exactly on it — the shape a reader at the very top produces.
        assertTrue(
            shouldFollowHeadInsert(
                previousFirstPath = "/b",
                loadedPaths = listOf("/a", "/b", "/c"),
                firstVisibleIndex = 1,
                firstVisibleOffset = 0,
            )
        )
    }

    @Test
    fun followsABurstThatPrependsSeveralRows() {
        // A backup writes several rows between two reloads; the anchor shifts by all of them.
        assertTrue(
            shouldFollowHeadInsert(
                previousFirstPath = "/d",
                loadedPaths = listOf("/a", "/b", "/c", "/d"),
                firstVisibleIndex = 3,
                firstVisibleOffset = 0,
            )
        )
    }

    @Test
    fun leavesAScrolledReaderAlone() {
        // Same prepend, but the reader is further down: the viewport is not anchored on the old
        // head, so following would throw away their place.
        assertFalse(
            shouldFollowHeadInsert(
                previousFirstPath = "/b",
                loadedPaths = listOf("/a", "/b", "/c"),
                firstVisibleIndex = 2,
                firstVisibleOffset = 0,
            )
        )
    }

    @Test
    fun leavesAReaderMidRowAlone() {
        // Anchored on the old head but scrolled part-way through it — not "parked at the top".
        assertFalse(
            shouldFollowHeadInsert(
                previousFirstPath = "/b",
                loadedPaths = listOf("/a", "/b", "/c"),
                firstVisibleIndex = 1,
                firstVisibleOffset = 40,
            )
        )
    }

    @Test
    fun ignoresAFreshGenerationThatReplacedEverything() {
        // A refresh (sort change, navigation) leaves the old head absent. Scrolling here would
        // fight scroll restoration rather than follow an insert.
        assertFalse(
            shouldFollowHeadInsert(
                previousFirstPath = "/gone",
                loadedPaths = listOf("/a", "/b"),
                firstVisibleIndex = 0,
                firstVisibleOffset = 0,
            )
        )
    }

    @Test
    fun ignoresAnUnchangedHeadAndAnEmptyOrFirstListing() {
        assertFalse(
            shouldFollowHeadInsert("/a", listOf("/a", "/b"), firstVisibleIndex = 0, firstVisibleOffset = 0),
        )
        assertFalse(
            shouldFollowHeadInsert(null, listOf("/a"), firstVisibleIndex = 0, firstVisibleOffset = 0),
        )
        assertFalse(
            shouldFollowHeadInsert("/a", emptyList(), firstVisibleIndex = 0, firstVisibleOffset = 0),
        )
    }

    @Test
    fun ignoresAnAppendOnlyChange() {
        // The natural-order shape: rows arrive at the TAIL, so the head is unchanged and nothing
        // moves under the reader.
        assertFalse(
            shouldFollowHeadInsert("/a", listOf("/a", "/b", "/c"), firstVisibleIndex = 0, firstVisibleOffset = 0),
        )
    }
}
