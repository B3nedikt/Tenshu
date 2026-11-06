package app.tenshu.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure halves of the rules `PhotosLibrary.kt` copies from `TenshuPhotosRepository` (its header,
 * rules 2, 3, 5, 6). ⚠ Each assertion is a value native also produces — except rule 3's cursor,
 * which diverges on purpose (the header says why); when one goes red, the question is which side
 * moved, not how to make this file agree.
 */
class PhotosLibraryRulesTest {

    @Test
    fun theHeadCursorIsNullOrEmpty_andAnEncodedCursorRoundTrips() {
        assertEquals(FeedCursor.HEAD, FeedCursor.decode(null))
        assertEquals(FeedCursor.HEAD, FeedCursor.decode(""))
        val c = FeedCursor(1_700_000_000_123L, "/DCIM/2024.01/IMG_0001.v2.jpg")
        assertEquals(c, FeedCursor.decode(c.encode()), "a path with dots survives")
        val top = FeedCursor(1_700_000_000_123L, null)
        assertEquals(top, FeedCursor.decode(top.encode()))
    }

    @Test
    fun aMalformedCursorDecodesToNull_soTheCallerCanFailLoudly() {
        // `v1.<ms>.<skip>` was run A's counting cursor; it is refused, never read as a path.
        for (bad in listOf("1700.0", "v2.", "v2.abc./a", "v2.1", "v2.1.relative", "v1.1.0", "v3.1./a")) {
            assertNull(FeedCursor.decode(bad), "'$bad' must not decode")
        }
    }

    @Test
    fun theHeadSentinelIsNotWidened_butEveryRealCursorIsInclusive() {
        // `+ 1` on Long.MAX_VALUE overflows to MIN_VALUE and would empty the first page.
        assertEquals(Long.MAX_VALUE, exclusiveEndFor(Long.MAX_VALUE))
        assertEquals(1001L, exclusiveEndFor(1000L))
    }

    @Test
    fun aShortPageIsTheLast() {
        assertNull(nextFeedCursor(listOf(FeedKey(5L, "/a"), FeedKey(4L, "/b")), pageSize = 3))
        assertNull(nextFeedCursor(emptyList(), pageSize = 3))
    }

    @Test
    fun theNextCursorNamesTheLastRow_andCountsNothing() {
        val page = listOf(FeedKey(9L, "/z"), FeedKey(7L, "/a"), FeedKey(7L, "/b"))
        assertEquals(FeedCursor(7L, "/b"), nextFeedCursor(page, pageSize = 3))
    }

    @Test
    fun theOrderIsTotal_captureTimeDescThenPathAsc() {
        val shuffled = listOf(FeedKey(7L, "/b"), FeedKey(9L, "/z"), FeedKey(7L, "/a"), FeedKey(8L, "/m"))
        assertEquals(
            listOf(FeedKey(9L, "/z"), FeedKey(8L, "/m"), FeedKey(7L, "/a"), FeedKey(7L, "/b")),
            shuffled.sortedWith(FEED_ORDER),
        )
    }

    @Test
    fun afterTheCursorIsStrictlyLaterInTheOrder() {
        val cursor = FeedCursor(7L, "/b")
        assertTrue(FeedKey(6L, "/a").afterCursor(cursor), "older")
        assertTrue(FeedKey(7L, "/c").afterCursor(cursor), "tied, later by path")
        assertFalse(FeedKey(7L, "/b").afterCursor(cursor), "the served row itself")
        assertFalse(FeedKey(7L, "/a").afterCursor(cursor), "tied, earlier by path: the head's")
        assertFalse(FeedKey(8L, "/z").afterCursor(cursor), "newer")
        assertTrue(FeedKey(7L, "/a").afterCursor(FeedCursor(7L, null)), "a window top keeps its millisecond")
    }

    @Test
    fun twoAlbumsMadeInOneMillisecondGetDifferentIds() {
        // The shipped defect `photos-app.md` §24.2 records: every album had one id.
        assertNotEquals(mintAlbumId(1000L, "Holiday"), mintAlbumId(1000L, "Family"))
        assertEquals("album-1000-${"Holiday".hashCode().toUInt().toString(16)}", mintAlbumId(1000L, "Holiday"))
    }

    @Test
    fun aTrashPathIsKeyedOnTheContentId_soTwoSameNamedPhotosCannotCollide() {
        assertEquals("/Trash/c1-IMG_0001.jpg", trashPathFor("c1", "IMG_0001.jpg"))
        assertNotEquals(trashPathFor("c1", "IMG_0001.jpg"), trashPathFor("c2", "IMG_0001.jpg"))
    }
}
