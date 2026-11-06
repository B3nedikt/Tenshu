package app.tenshu.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The grouping half of [ContentIndex] — the half that decides what the user is shown and what an
 * ingest-time skip drops. The chain read is exercised where the chain is: these are the rules.
 */
class ContentIndexTest {

    private fun entry(
        path: String,
        hash: String,
        createdAtMs: Long = 0L,
        sizeBytes: Long = 1L,
        isImage: Boolean = true,
        isVideo: Boolean = false,
    ) = IndexedContent(
        path = path,
        contentId = "cid-${path.substringAfterLast('/')}",
        contentHash = hash,
        createdAtMs = createdAtMs,
        sizeBytes = sizeBytes,
        isImage = isImage,
        isVideo = isVideo,
    )

    @Test
    fun onlyContentsHeldByMoreThanOneFileAreDuplicates() {
        val index = ContentIndex.of(
            listOf(
                entry("/a.jpg", "h1"),
                entry("/b.jpg", "h1"),
                entry("/c.jpg", "h2"),
            )
        )

        val groups = index.duplicateGroups()

        assertEquals(1, groups.size)
        assertEquals(listOf("/a.jpg", "/b.jpg"), groups.single().map { it.path })
        assertEquals(2, index.contentCount)
        assertEquals(3, index.fileCount)
    }

    @Test
    fun aFileWithNoHashIsUnknownRatherThanAGroupOfItsOwn() {
        // ⚠ The case that matters: pre-hash uploads all carry "", and filing them together would
        // present "everything we could not identify" as one enormous duplicate group.
        val index = ContentIndex.of(
            listOf(
                entry("/old-1.jpg", ""),
                entry("/old-2.jpg", ""),
                entry("/new.jpg", "h1"),
            )
        )

        assertEquals(emptyList(), index.duplicateGroups())
        assertEquals(1, index.contentCount)
        assertFalse(index.holds(""))
        assertEquals(emptyList(), index.copiesOf(""))
    }

    @Test
    fun holdsAnswersTheIngestSkipQuestion() {
        val index = ContentIndex.of(listOf(entry("/a.jpg", "h1")))

        assertTrue(index.holds("h1"))
        assertFalse(index.holds("h2"))
    }

    @Test
    fun theOldestCopyLeadsItsGroupAndTheBiggestGroupLeads() {
        val index = ContentIndex.of(
            listOf(
                entry("/pair-b.jpg", "h1", createdAtMs = 200),
                entry("/pair-a.jpg", "h1", createdAtMs = 100),
                entry("/trio-c.jpg", "h2", createdAtMs = 30),
                entry("/trio-a.jpg", "h2", createdAtMs = 10),
                entry("/trio-b.jpg", "h2", createdAtMs = 20),
            )
        )

        val groups = index.duplicateGroups()

        assertEquals(listOf(3, 2), groups.map { it.size })
        assertEquals(listOf("/trio-a.jpg", "/trio-b.jpg", "/trio-c.jpg"), groups[0].map { it.path })
        assertEquals(listOf("/pair-a.jpg", "/pair-b.jpg"), groups[1].map { it.path })
    }

    @Test
    fun theMediaViewDropsAGroupThatIsNotAllPhotosOrVideos() {
        val index = ContentIndex.of(
            listOf(
                entry("/doc-1.pdf", "h1", isImage = false),
                entry("/doc-2.pdf", "h1", isImage = false),
                entry("/clip-1.mp4", "h2", isImage = false, isVideo = true),
                entry("/clip-2.mp4", "h2", isImage = false, isVideo = true),
            )
        )

        assertEquals(2, index.duplicateGroups().size)
        assertEquals(
            listOf(listOf("/clip-1.mp4", "/clip-2.mp4")),
            index.mediaDuplicateGroups().map { group -> group.map { it.path } },
        )
    }
}
