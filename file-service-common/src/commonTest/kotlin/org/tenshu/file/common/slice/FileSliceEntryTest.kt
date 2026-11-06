package org.tenshu.file.common.slice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSliceEntryTest {

    private fun entry(
        entityId: String = "/photos/a.jpg",
        name: String = "a.jpg",
        isFolder: Boolean = false,
        size: Long = 321_488,
        contentId: String = "content-1",
        contentHash: String = "abc123",
        createdAtMillis: Long = 1_700_000_000_000,
        lastModifiedAtMillis: Long = 1_700_000_500_000,
    ) = FileSliceEntry(
        entityId, name, isFolder, size, contentId, contentHash, createdAtMillis, lastModifiedAtMillis,
    )

    @Test
    fun roundTripsEveryField() {
        val entries = listOf(
            entry(),
            entry(entityId = "/photos", name = "photos", isFolder = true, size = 0, contentId = "", contentHash = ""),
        )

        val decoded = FileSliceEntry.decode(FileSliceEntry.encode(entries))

        assertEquals(2, decoded?.size)
        val file = decoded!![0]
        assertEquals("/photos/a.jpg", file.entityId)
        assertEquals("a.jpg", file.name)
        assertEquals(false, file.isFolder)
        assertEquals(321_488, file.size)
        assertEquals("content-1", file.contentId)
        assertEquals("abc123", file.contentHash)
        assertEquals(1_700_000_000_000, file.createdAtMillis)
        assertEquals(1_700_000_500_000, file.lastModifiedAtMillis)

        val folder = decoded[1]
        assertEquals(true, folder.isFolder)
        assertEquals("", folder.contentId, "a folder carries no content id")
    }

    @Test
    fun roundTripsAnEmptySlice() {
        // An empty folder is not a missing folder — it must encode and decode as zero entries,
        // so a reader can render "this folder is empty" rather than fall back to a relation read.
        val decoded = FileSliceEntry.decode(FileSliceEntry.encode(emptyList()))
        assertEquals(0, decoded?.size)
    }

    @Test
    fun roundTripsNonAsciiNames() {
        // Names are user data: emoji, CJK and combining marks must survive the length-prefixed
        // UTF-8 encoding, where a naive char-count would corrupt them.
        val name = "Ünïcödé 日本語 🎉 é.txt"
        val decoded = FileSliceEntry.decode(FileSliceEntry.encode(listOf(entry(name = name))))
        assertEquals(name, decoded?.single()?.name)
    }

    @Test
    fun decode_returnsNullOnTruncation() {
        // ⚠ Null, never a throw — same contract as the envelope. An unreadable slice makes a
        // device fall back to a relation read; it must not break it.
        val encoded = FileSliceEntry.encode(listOf(entry(), entry(entityId = "/b", name = "b")))
        for (length in 0 until encoded.size) {
            assertNull(
                FileSliceEntry.decode(encoded.copyOfRange(0, length)),
                "truncating to $length bytes must decode to null, not throw",
            )
        }
    }

    @Test
    fun decode_returnsNullForEntriesFromTheFuture() {
        // ⚠⚠ The reason entries carry their OWN version, independent of the envelope's: Drive
        // can evolve its projection without touching the platform artifact, and an older reader
        // declines rather than mis-parsing.
        val encoded = FileSliceEntry.encode(listOf(entry())).copyOf()
        encoded[0] = (FileSliceEntry.VERSION + 1).toByte()

        assertNull(FileSliceEntry.decode(encoded))
    }

    @Test
    fun anEntryStaysAroundTheSizeTheDesignAssumed() {
        // §5.7.2 sizes the design on ~120 B/entry — that is what makes a ~20-entry root slice
        // "~2.4 KB, one request", and hence the whole "usable in seconds" claim. If an entry
        // grows materially, that arithmetic (and the case for C8 over C7 alone) needs redoing.
        val encoded = FileSliceEntry.encode(listOf(entry()))
        assertTrue(
            encoded.size < 160,
            "a typical entry encoded to ${encoded.size} B; §5.7.2's ~120 B assumption is at risk",
        )
    }
}
