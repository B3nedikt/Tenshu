package org.tenshu.core.common.proxy

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The durable iOS content cache (`ios-fit-timeline-maps-parity.md` P4).
 *
 * ⚠ Every case here is one an `InMemoryFileDatabase` would also pass **except**
 * [bytesSurviveANewInstanceOverTheSameDirectory] — which is the entire reason
 * this class exists, and the one that was silently false on iOS before it.
 */
@OptIn(ExperimentalForeignApi::class)
class IosFileDatabaseTest {

    private val root: String =
        "${NSTemporaryDirectory()}ios-filedb-test-${Random.nextLong()}"

    private fun db() = IosFileDatabase(root = root)

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    @Test
    fun bytesSurviveANewInstanceOverTheSameDirectory() {
        db().writeRawByteArray("/photos/a.jpg", byteArrayOf(1, 2, 3, 4))

        // A second instance over the same root stands in for the next process —
        // which is exactly what the in-memory implementation could never do, and
        // why keep-offline, the plaintext cache and downloaded regions all forgot
        // their work on every relaunch.
        val reopened = db().readBytes("/photos/a.jpg")

        assertContentEquals(byteArrayOf(1, 2, 3, 4), reopened?.get())
    }

    @Test
    fun nestedPathsAreCreatedAndALeadingSlashIsNotAFilesystemRoot() {
        // ⚠ Callers key into a virtual tree ("/.health/streams/…"), not the host
        // filesystem; a leading slash must not escape the replica directory.
        db().writeRawByteArray("/.health/streams/42", byteArrayOf(9))

        assertContentEquals(byteArrayOf(9), db().readBytes("/.health/streams/42")?.get())
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath("$root/.health/streams/42"),
            "the file must land under the replica root, not at the device root",
        )
    }

    @Test
    fun anAbsentFileReadsAsNullRatherThanEmpty() {
        assertNull(db().readBytes("/missing"))
        assertNull(db().fileSize("/missing"))
        assertNull(db().readBytesRange("/missing", 0, 16))
    }

    @Test
    fun fileSizeReadsTheDirectoryEntryNotTheFile() {
        db().writeRawByteArray("/big", ByteArray(4096) { it.toByte() })

        assertEquals(4096L, db().fileSize("/big"))
    }

    @Test
    fun rangedReadReturnsAWindowAnEmptyArrayPastEofAndAShortFinalChunk() {
        val content = ByteArray(100) { it.toByte() }
        val database = db()
        database.writeRawByteArray("/ranged", content)

        assertContentEquals(
            content.copyOfRange(10, 26),
            database.readBytesRange("/ranged", offset = 10, length = 16),
        )
        // ⚠ Past EOF is an EMPTY array, not null — null means "no such file", and
        // the chunk loop distinguishes the two.
        assertContentEquals(ByteArray(0), database.readBytesRange("/ranged", offset = 100, length = 16))
        // The final chunk is short rather than zero-padded.
        assertContentEquals(
            content.copyOfRange(90, 100),
            database.readBytesRange("/ranged", offset = 90, length = 16),
        )
    }

    @Test
    fun rangedWriteCreatesExtendsAndOverwritesInPlace() {
        val database = db()

        // Absent file: an updating handle cannot open one, so it must be created.
        database.writeBytesRange("/reassembled", offset = 0, bytes = byteArrayOf(1, 2, 3, 4))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), database.readBytes("/reassembled")?.get())

        // Extend past the current end — the chunk-by-chunk reassembly shape.
        database.writeBytesRange("/reassembled", offset = 4, bytes = byteArrayOf(5, 6))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), database.readBytes("/reassembled")?.get())

        // Overwrite in the middle without truncating the tail.
        database.writeBytesRange("/reassembled", offset = 1, bytes = byteArrayOf(9))
        assertContentEquals(byteArrayOf(1, 9, 3, 4, 5, 6), database.readBytes("/reassembled")?.get())
    }

    @Test
    fun moveReplacesAnExistingDestination() {
        val database = db()
        database.writeRawByteArray("/tmp/part", byteArrayOf(7, 7))
        database.writeRawByteArray("/final", byteArrayOf(0))

        database.moveFile("/tmp/part", "/final")

        // ⚠ NSFileManager.moveItemAtPath FAILS on an existing destination rather
        // than replacing it, unlike File.renameTo — so the promote step would
        // silently keep the stale file without the explicit remove.
        assertContentEquals(byteArrayOf(7, 7), database.readBytes("/final")?.get())
        assertNull(database.readBytes("/tmp/part"), "the source must be gone after a move")
    }

    @Test
    fun aMoveThatCannotLandFailsLoudlyAndKeepsTheSourceBytes() {
        // ⚠⚠ `moveFile` is how the P2 manifest applier promotes a temp reassembly onto the real
        // path **after** the whole-file integrity gate passes. A silent failure there means the
        // caller believes a verified file landed while the old contents are still in place —
        // and on a move it means the row names the new path while the bytes sit at the old one,
        // which is a device that answers "not me" to every content repair (§6d D3).
        val fileDatabase = db()
        fileDatabase.writeRawByteArray("/source.bin", byteArrayOf(1, 2, 3, 4))
        // A *file* where the destination needs a directory: the parent cannot be created, so the
        // move cannot land. Nothing exotic — a file and a folder of the same name collide here.
        fileDatabase.writeRawByteArray("/occupied", byteArrayOf(9))

        assertFailsWith<IllegalStateException> {
            fileDatabase.moveFile("/source.bin", "/occupied/target.bin")
        }

        assertContentEquals(
            byteArrayOf(1, 2, 3, 4),
            fileDatabase.readBytes("/source.bin")?.get(),
            "a failed move must not consume the source — it is the only copy left",
        )
        assertNull(fileDatabase.readBytes("/occupied/target.bin"), "and nothing landed")
    }

    @Test
    fun deleteIsANoOpOnAnAbsentFile() {
        val database = db()
        database.writeRawByteArray("/doomed", byteArrayOf(1))

        database.deleteFile("/doomed")
        database.deleteFile("/doomed")

        assertNull(database.readBytes("/doomed"))
    }

    // ---- a failed write must SAY so ------------------------------------------------------------

    /**
     * ⚠⚠⚠ **A write that cannot happen must raise, not return quietly.**
     *
     * Both iOS writers used to report success when they had written nothing — the ranged write
     * returned on an unavailable handle, and the raw write discarded the `Boolean` `writeToFile`
     * hands back. Every caller holding durable state decides what it may now believe from whether
     * the write returned, so a silent refusal let an append-log store advance its offset, mutate its
     * in-memory map, or mark a wipe done over bytes that were never persisted — losing an accepted
     * write or resurrecting a removed row at the next open.
     *
     * The unwritable path here is a file used as a directory: `ensureParentDirectory` cannot create
     * a directory where a file already is, so neither writer can reach its target. That is the same
     * shape as a read-only volume or a protected path, and it is reachable from a test.
     */
    @Test
    fun aRawWriteThatCannotReachItsPathRaises() {
        val db = db()
        db.writeRawByteArray("/occupied", byteArrayOf(9))

        // "/occupied" is a FILE, so "/occupied/child.bin" has nowhere to live.
        assertFailsWith<IllegalStateException> {
            db.writeRawByteArray("/occupied/child.bin", byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun aRangedWriteThatCannotReachItsPathRaises() {
        val db = db()
        db.writeRawByteArray("/occupied", byteArrayOf(9))

        assertFailsWith<IllegalStateException> {
            db.writeBytesRange("/occupied/child.bin", offset = 0, bytes = byteArrayOf(1, 2, 3))
        }
    }

    /**
     * ⚠ The converse, so the two above cannot pass by raising on everything: an ordinary write to a
     * reachable path still succeeds, and still lands atomically.
     */
    @Test
    fun anOrdinaryWriteStillSucceeds() {
        val db = db()

        db.writeRawByteArray("/fine/a.bin", byteArrayOf(1, 2, 3))
        db.writeBytesRange("/fine/b.bin", offset = 0, bytes = byteArrayOf(4, 5))

        assertContentEquals(byteArrayOf(1, 2, 3), db.readBytes("/fine/a.bin")?.get())
        assertContentEquals(byteArrayOf(4, 5), db.readBytes("/fine/b.bin")?.get())
    }
}
