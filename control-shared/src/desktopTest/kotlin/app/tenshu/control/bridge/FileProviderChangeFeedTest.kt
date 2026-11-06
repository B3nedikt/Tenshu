package app.tenshu.control.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FileProviderChangeFeedTest {

    @Test
    fun anUnchangedRealmKeepsItsAnchor() {
        val feed = FileProviderChangeFeed()
        val a = feed.refresh(mapOf("x" to "1"))
        assertEquals(a, feed.refresh(mapOf("x" to "1")))
        assertEquals(FileProviderChangeFeed.Changes(a, emptySet(), emptySet()), feed.changesSince(a))
    }

    @Test
    fun additionsEditsAndDeletionsAreReportedSinceAnAnchor() {
        val feed = FileProviderChangeFeed()
        val a = feed.refresh(mapOf("kept" to "1", "edited" to "1", "gone" to "1"))
        val b = feed.refresh(mapOf("kept" to "1", "edited" to "2", "new" to "1"))
        assertNotEquals(a, b)

        val changes = assertNotNull(feed.changesSince(a))
        assertEquals(b, changes.anchor)
        assertEquals(setOf("edited", "new"), changes.updated)
        assertEquals(setOf("gone"), changes.deleted)
    }

    @Test
    fun aReaderSeveralRefreshesBehindGetsTheUnion_withLaterDeltasWinning() {
        val feed = FileProviderChangeFeed()
        val a = feed.refresh(mapOf("x" to "1"))
        feed.refresh(mapOf("x" to "1", "y" to "1"))
        feed.refresh(mapOf("x" to "2"))
        val latest = feed.refresh(mapOf("x" to "2", "z" to "1"))

        val changes = assertNotNull(feed.changesSince(a))
        assertEquals(latest, changes.anchor)
        assertEquals(setOf("x", "z"), changes.updated)
        // Created and deleted between the reader's two visits: still a delete, which is harmless
        // for an id the reader never saw and required for one it did.
        assertEquals(setOf("y"), changes.deleted)
    }

    @Test
    fun aSecondReaderIsNotStrandedByTheFirst() {
        val feed = FileProviderChangeFeed()
        val a = feed.refresh(mapOf("x" to "1"))
        feed.refresh(mapOf("x" to "2"))
        // Reader one catches up…
        assertEquals(setOf("x"), feed.changesSince(a)?.updated)
        // …and reader two, still holding the same old anchor, gets the same answer.
        assertEquals(setOf("x"), feed.changesSince(a)?.updated)
    }

    @Test
    fun anchorsOlderThanTheHistoryOrFromAnotherProcessExpire() {
        val feed = FileProviderChangeFeed()
        val a = feed.refresh(mapOf("x" to "0"))
        repeat(FileProviderChangeFeed.HISTORY + 1) { feed.refresh(mapOf("x" to "${it + 1}")) }

        assertNull(feed.changesSince(a), "an anchor the history no longer covers must expire")
        assertNull(feed.changesSince("someoneelse:3"))
        assertNull(feed.changesSince("v1"))
        assertNull(feed.changesSince(a.substringBefore(':') + ":999999"), "an anchor from the future")
    }

    @Test
    fun theOldestAnchorStillCoveredByTheHistoryIsAnswered() {
        val feed = FileProviderChangeFeed()
        val anchors = mutableListOf(feed.refresh(mapOf("x" to "0")))
        repeat(FileProviderChangeFeed.HISTORY) { anchors += feed.refresh(mapOf("x" to "${it + 1}")) }
        assertEquals(setOf("x"), feed.changesSince(anchors.first())?.updated)
    }
}
