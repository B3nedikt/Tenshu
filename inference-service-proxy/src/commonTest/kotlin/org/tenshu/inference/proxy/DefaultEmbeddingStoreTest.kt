package org.tenshu.inference.proxy

import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.inference.ModelProvenance
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The durable vector sink (IN-4b) — the half that matters, because an in-memory store that forgets
 * on restart is discovered as *"indexing runs from scratch every launch"*, hours of battery at a
 * time. `DefaultModelStoreTest` makes the same argument about models.
 */
class DefaultEmbeddingStoreTest {

    private val files = MapFileDatabase()
    private val path = "/inference/embeddings.log"
    private val model = ModelProvenance("siglip", 1)
    private val otherModel = ModelProvenance("siglip", 2)

    private fun store() = DefaultEmbeddingStore(files, path)

    @Test
    fun vectorsSurviveAReopen() = runTest {
        store().apply {
            put("/a.jpg", floatArrayOf(1f, 0f), model)
            put("/b.jpg", floatArrayOf(0f, 1f), model)
        }

        val reopened = store()

        assertEquals(2, reopened.size())
        assertEquals(model, reopened.provenance())
        assertEquals(listOf(1f, 0f), reopened.entries().first { it.first == "/a.jpg" }.second.toList())
    }

    @Test
    fun theLastWriteWinsAcrossAReopen() = runTest {
        store().apply {
            put("/a.jpg", floatArrayOf(1f, 0f), model)
            put("/a.jpg", floatArrayOf(0f, 1f), model)
        }

        // ⚠ A log replays in order, so a re-index that overwrites has to land as a later record —
        // and the reader must apply them in that order rather than keeping the first.
        val reopened = store()

        assertEquals(1, reopened.size())
        assertEquals(listOf(0f, 1f), reopened.entries().first().second.toList())
    }

    @Test
    fun aRemovalSurvivesAReopen() = runTest {
        store().apply {
            put("/a.jpg", floatArrayOf(1f, 0f), model)
            put("/b.jpg", floatArrayOf(0f, 1f), model)
            remove("/a.jpg")
        }

        // ⚠⚠ The append-only trap: without a tombstone record the removed vector would come back
        // on the next launch, and a deleted photo would keep turning up in searches forever.
        assertEquals(listOf("/b.jpg"), store().entries().map { it.first }.toList())
    }

    /**
     * ⚠⚠ A photo this device could not decode is remembered across a restart. Forgetting it would
     * re-read every such file on every idle-and-charging pass — which on Android, where the proxy
     * dies routinely, is every pass.
     */
    @Test
    fun aVisitWithoutAVectorSurvivesAReopenAndIsNotAnEntry() = runTest {
        store().apply {
            put("/a.jpg", floatArrayOf(1f, 0f), model)
            assertTrue(markVisited("/b.raw"))
        }

        val reopened = store()

        assertTrue(reopened.visited("/b.raw"))
        assertEquals(1, reopened.size(), "a visit is not a vector")
        assertEquals(listOf("/a.jpg"), reopened.entries().map { it.first }.toList())
    }

    /** ⚠ With no model stamped there is nobody the visit belongs to, so nothing is recorded. */
    @Test
    fun aVisitOnAnEmptyStoreIsRefused() = runTest {
        val empty = store()

        assertFalse(empty.markVisited("/b.raw"))
        assertFalse(store().visited("/b.raw"))
        assertNull(store().provenance())
    }

    @Test
    fun aRemovalClearsAVisitAcrossAReopen() = runTest {
        store().apply {
            put("/a.jpg", floatArrayOf(1f, 0f), model)
            markVisited("/b.raw")
            remove("/b.raw")
        }

        assertFalse(store().visited("/b.raw"))
    }

    @Test
    fun aTornTailIsDiscardedRatherThanRefusingToOpen() = runTest {
        store().apply {
            put("/a.jpg", floatArrayOf(1f, 0f), model)
            put("/b.jpg", floatArrayOf(0f, 1f), model)
        }
        // A pass killed mid-append: the process died between extending the file and finishing the
        // record. ⚠⚠ Routine, not exotic — an index pass runs for hours and can be killed at any
        // moment, so this is the *normal* shape of an interrupted store.
        files.truncateBy(3)

        val reopened = store()

        assertEquals(1, reopened.size(), "the intact prefix survives")
        assertEquals("/a.jpg", reopened.entries().first().first)
        // And it must still be writable — a store that opened read-only after one interruption
        // would never index again.
        assertTrue(reopened.put("/c.jpg", floatArrayOf(0f, 1f), model))
        assertEquals(2, store().size())
    }

    @Test
    fun rebaseTruncatesTheLogRatherThanAppendingToIt() = runTest {
        store().apply {
            put("/a.jpg", floatArrayOf(1f, 0f), model)
            rebase(otherModel)
        }

        val reopened = store()

        assertEquals(0, reopened.size())
        assertEquals(otherModel, reopened.provenance())
        // ⚠ The old records are *gone*, not shadowed: a log that kept them would be replayed on the
        // next open and resurrect another model's vectors under the new stamp.
        assertTrue((files.sizeOf(path) ?: 0) < 64, "only the header remains")
    }

    @Test
    fun aForeignStampIsRefusedOnDiskToo() = runTest {
        store().put("/a.jpg", floatArrayOf(1f, 0f), model)

        assertFalse(store().put("/b.jpg", floatArrayOf(0f, 1f), otherModel))
        assertEquals(1, store().size())
    }

    @Test
    fun anAbsentFileIsAnEmptyStoreNotACrash() = runTest {
        val fresh = DefaultEmbeddingStore(MapFileDatabase(), "/nothing/here.log")

        assertEquals(0, fresh.size())
        assertNull(fresh.provenance())
    }

    /**
     * ⚠⚠ **A clear that only empties the maps is no clear at all.** The log on disk is the store —
     * the next process start replays it — so a realm reset would hand the fresh realm every vector
     * of the old one back at the next launch, with nothing anywhere reporting a fault. The
     * in-memory assertion alone cannot see this: it passes on a store whose file is still there.
     */
    @Test
    fun aClearedStoreStaysClearedAcrossAReopen() = runTest {
        store().apply {
            put("/a.jpg", floatArrayOf(1f, 0f), model)
            put("/b.jpg", floatArrayOf(0f, 1f), model)
            markVisited("/c.jpg")
            clear()
        }

        val reopened = store()

        assertEquals(0, reopened.size())
        assertNull(reopened.provenance(), "the model stamp goes with the vectors it judged")
        assertFalse(reopened.visited("/c.jpg"), "a visit marker is a claim about vectors that are gone")
        assertNull(files.sizeOf(path), "the file itself must go — a header alone still owns the store")
    }

    /** ⚠ And the cleared store is writable again, rather than appending after a phantom offset. */
    @Test
    fun aClearedStoreCanBeWrittenAndReadBack() = runTest {
        val cleared = store().apply {
            put("/a.jpg", floatArrayOf(1f, 0f), model)
            clear()
            put("/new.jpg", floatArrayOf(0f, 1f), otherModel)
        }

        assertEquals(listOf("/new.jpg"), cleared.entries().map { it.first })
        assertEquals(listOf("/new.jpg"), store().entries().map { it.first })
        assertEquals(otherModel, store().provenance())
    }

    private class MapFileDatabase : FileDatabase {
        private val files = mutableMapOf<String, ByteArray>()

        fun sizeOf(filePath: String): Long? = files[filePath]?.size?.toLong()

        fun truncateBy(bytes: Int) {
            files.keys.forEach { key ->
                val current = files.getValue(key)
                files[key] = current.copyOf(maxOf(0, current.size - bytes))
            }
        }

        override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
            files[filePath] = bytes
        }

        override fun writeByteArray(filePath: String, bytes: TByteArray) {
            files[filePath] = bytes.get()
        }

        override fun readBytes(filePath: String): TByteArray? = files[filePath]?.let { TByteArrayDto(it) }

        override fun deleteFile(filePath: String) {
            files.remove(filePath)
        }
    }
}
