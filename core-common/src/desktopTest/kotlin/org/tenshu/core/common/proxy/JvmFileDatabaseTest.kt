package org.tenshu.core.common.proxy

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the durable, disk-backed [JvmFileDatabase] — the shared JVM
 * implementation behind both the hub's keep-everything replica (H3) and desktop
 * Control's plaintext content cache. The two contract points the offline keeper
 * leans on are pinned explicitly:
 *
 *  - a **0-byte file is present, not absent** (`fileSize` returns `0L`, never null;
 *    `readBytes` returns an empty non-null payload) — the keeper's
 *    `fileSize(path) != null` probe must not re-select an already-fetched empty
 *    file into an endless fetch loop;
 *  - durability: a **fresh instance over the same directory** reads back what an
 *    earlier instance wrote (the restart shape), compared by content bytes — never
 *    by `TByteArray` object equality (reference-based).
 */
class JvmFileDatabaseTest {

    private val root = createTempDirectory("jvm-file-db-test").toFile()

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun db() = JvmFileDatabase(root)

    @Test
    fun writeReadRoundTrip_andDurabilityAcrossInstances() {
        val payload = ByteArray(70_000) { (it % 251).toByte() }
        db().writeRawByteArray("/Photos/img.bin", payload)

        // A FRESH instance over the same directory — the hub-restart shape.
        val reopened = db()
        assertEquals(70_000L, reopened.fileSize("/Photos/img.bin"))
        assertContentEquals(payload, assertNotNull(reopened.readBytes("/Photos/img.bin")).get())
    }

    @Test
    fun zeroByteFile_isPresentAndEmpty_neverAbsent() {
        db().writeRawByteArray("/empty.txt", ByteArray(0))

        val reopened = db()
        // fileSize is the keeper's "has local bytes" probe: 0L (non-null), so the
        // keeper never re-selects the file for another fetch.
        assertEquals(0L, reopened.fileSize("/empty.txt"))
        val bytes = assertNotNull(reopened.readBytes("/empty.txt"), "present-and-empty, not absent")
        assertEquals(0, bytes.get().size)
    }

    @Test
    fun zeroLengthRangeWrite_stillCreatesTheFile() {
        // The manifest applier reassembles a 0-byte file as a single empty chunk:
        // one writeBytesRange(temp, 0, EMPTY) then moveFile(temp, path). The empty
        // range write must materialise the file or the promote silently no-ops.
        val database = db()
        database.writeBytesRange("/partial.tmp", 0, ByteArray(0))
        assertEquals(0L, database.fileSize("/partial.tmp"))

        database.moveFile("/partial.tmp", "/final.txt")
        assertEquals(0L, database.fileSize("/final.txt"))
        assertNull(database.fileSize("/partial.tmp"))
    }

    @Test
    fun rangeReadAndWrite_areBoundedAndPositional() {
        val database = db()
        database.writeBytesRange("/chunked.bin", 0, byteArrayOf(1, 2, 3, 4))
        database.writeBytesRange("/chunked.bin", 4, byteArrayOf(5, 6, 7, 8))

        assertContentEquals(byteArrayOf(3, 4, 5), database.readBytesRange("/chunked.bin", 2, 3))
        // Final short chunk: only the actually-read bytes come back.
        assertContentEquals(byteArrayOf(7, 8), database.readBytesRange("/chunked.bin", 6, 100))
        // Past EOF: empty, not null (the file exists).
        assertContentEquals(ByteArray(0), database.readBytesRange("/chunked.bin", 99, 4))
        // Absent file: null.
        assertNull(database.readBytesRange("/missing.bin", 0, 4))
    }

    @Test
    fun moveFile_replacesTargetAtomically_andMissingSourceIsANoOp() {
        val database = db()
        database.writeRawByteArray("/a.bin", byteArrayOf(1))
        database.writeRawByteArray("/b.bin", byteArrayOf(2))

        database.moveFile("/a.bin", "/b.bin")
        assertContentEquals(byteArrayOf(1), assertNotNull(database.readBytes("/b.bin")).get())
        assertNull(database.fileSize("/a.bin"))

        // Missing source: no-op, target untouched.
        database.moveFile("/ghost.bin", "/b.bin")
        assertContentEquals(byteArrayOf(1), assertNotNull(database.readBytes("/b.bin")).get())
    }

    @Test
    fun deleteFile_removes_andIsANoOpWhenAbsent() {
        val database = db()
        database.writeRawByteArray("/gone.bin", byteArrayOf(9))
        database.deleteFile("/gone.bin")
        assertNull(database.fileSize("/gone.bin"))
        database.deleteFile("/gone.bin") // no-op
    }

    @Test
    fun contentLivesUnderTheContentRoot_onRealDisk() {
        db().writeRawByteArray("/Photos/2026/img.bin", byteArrayOf(1, 2))
        val onDisk = File(root, "Photos/2026/img.bin")
        assertTrue(onDisk.isFile, "bytes must land as a real file under the content root")
        assertEquals(2L, onDisk.length())
    }

    @Test
    fun pathTraversal_isRejected() {
        assertFailsWith<IllegalArgumentException> {
            db().writeRawByteArray("/../escape.bin", byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> { db().readBytes("/a/../../escape.bin") }
        assertFailsWith<IllegalArgumentException> { db().writeRawByteArray("  ", byteArrayOf(1)) }
    }
}
