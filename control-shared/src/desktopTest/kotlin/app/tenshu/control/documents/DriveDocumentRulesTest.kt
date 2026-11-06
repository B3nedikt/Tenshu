package app.tenshu.control.documents

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The id, range and thumbnail rules behind the Android documents provider — no proxy needed. */
class DriveDocumentRulesTest {

    @Test
    fun anIdIsARealmPath_andAnythingElseIsRefused() {
        assertEquals("/", DriveDocumentIds.pathOf("/"))
        assertEquals("/Photos/cat.jpg", DriveDocumentIds.pathOf("/Photos/cat.jpg"))
        // The id arrives from another process, so a traversal or a malformed path never reaches a read.
        for (bad in listOf("", "Photos", "/Photos/", "//x", "/a/../b", "/./a", "/..")) {
            val e = assertFailsWith<DriveDocumentsException>(bad) { DriveDocumentIds.pathOf(bad) }
            assertEquals(DriveDocumentsException.Kind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun aStreamedReadNeverAsksPastTheEnd() = runBlocking {
        val asked = mutableListOf<Pair<Long, Int>>()
        val content = StreamedContent(
            size = 10,
            readRange = { offset, length -> asked += offset to length; ByteArray(length) },
            closeStream = {},
        )
        assertEquals(4, content.read(6, 128)?.size)
        assertContentEquals(ByteArray(0), content.read(10, 4))
        assertEquals(listOf(6L to 4), asked, "the clamped range, and nothing for a read past the end")
    }

    @Test
    fun aFailedStreamedReadIsNull_soTheReaderGetsAnIoError() = runBlocking {
        val content = StreamedContent(size = 10, readRange = { _, _ -> null }, closeStream = {})
        assertNull(content.read(0, 4))
    }

    @Test
    fun localContentReadsByRange() = runBlocking {
        val content = LocalContent(byteArrayOf(0, 1, 2, 3, 4))
        assertContentEquals(byteArrayOf(1, 2), content.read(1, 2))
        assertContentEquals(byteArrayOf(3, 4), content.read(3, 99))
        assertContentEquals(ByteArray(0), content.read(5, 1))
    }

    @Test
    fun theThumbnailIsTheSmallestStoredClassThatCovers_elseWhicheverExists() {
        val list = byteArrayOf(1)
        val large = byteArrayOf(2)
        assertEquals(list, bestStoredThumbnail(100, list, large))
        assertEquals(large, bestStoredThumbnail(101, list, large))
        assertEquals(list, bestStoredThumbnail(512, list, null))
        assertEquals(large, bestStoredThumbnail(64, null, large))
        assertNull(bestStoredThumbnail(64, null, null))
    }

    @Test
    fun stillsAreMintedForImagesVideosAndPdfsOnly() {
        assertTrue(carriesStills("cat.JPG"))
        assertTrue(carriesStills("clip.mp4"))
        assertTrue(carriesStills("paper.pdf"))
        assertFalse(carriesStills("archive.zip"))
        assertFalse(carriesStills("notes.txt"))
    }
}
