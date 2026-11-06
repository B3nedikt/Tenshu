package org.tenshu.core.common.proxy

import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.TByteArrayDto

class InMemoryFileDatabase : FileDatabase {
    private val files = mutableMapOf<String, ByteArray>()

    /**
     * Write order, standing in for a modification time. ⚠ Deliberately a counter
     * and not a clock: `commonMain` has no clock here, and the ceiling's eviction
     * only ever *compares* these, so a monotonic stamp orders identically to real
     * millis while keeping tests deterministic. The values are NOT epoch millis
     * and nothing may treat them as such.
     */
    private val writeStamps = mutableMapOf<String, Long>()
    private var nextStamp = 1L

    override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
        files[filePath] = bytes
        writeStamps[filePath] = nextStamp++
    }

    override fun writeByteArray(filePath: String, bytes: TByteArray) {
        writeRawByteArray(filePath, bytes.get())
    }

    override fun readBytes(filePath: String): TByteArray? {
        val bytes = files[filePath] ?: return null
        return TByteArrayDto(bytes)
    }

    override fun fileSize(filePath: String): Long? =
        files[filePath]?.size?.toLong()

    /**
     * Bounded-memory chunk source for the file-content emit path. The
     * only whole-file array resident here is the persisted store entry
     * itself (the in-memory analogue of the on-disk file) — each call
     * allocates just the requested window, so the *emit path* peak is
     * `O(CHUNK)`, which is what the bounded-memory regression test
     * asserts. (A real filesystem impl wouldn't even hold the store
     * copy; that's the Android `RandomAccessFile` override.)
     */
    override fun readBytesRange(filePath: String, offset: Long, length: Int): ByteArray? {
        val bytes = files[filePath] ?: return null
        if (offset >= bytes.size) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offset + length).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }

    /**
     * Bounded-memory reassembly sink for the P2 read path. The only
     * whole-file array resident is the persisted store entry (the
     * in-memory analogue of the on-disk file being assembled); each
     * call copies just the one `CHUNK`-sized window into it, so the
     * *applier* peak stays `O(CHUNK)`, which is what the bounded-memory
     * read-side regression assertion checks. (A real filesystem impl
     * wouldn't even hold the store copy; that's the Android
     * `RandomAccessFile` override.)
     */
    override fun writeBytesRange(filePath: String, offset: Long, bytes: ByteArray) {
        val existing = files[filePath] ?: ByteArray(0)
        val end = (offset + bytes.size).toInt()
        val target = if (end > existing.size) existing.copyOf(end) else existing
        bytes.copyInto(target, destinationOffset = offset.toInt())
        files[filePath] = target
    }

    /** O(1) reference move — keeps the read path `O(CHUNK)`. */
    override fun moveFile(fromPath: String, toPath: String) {
        val bytes = files.remove(fromPath) ?: return
        files[toPath] = bytes
        writeStamps[toPath] = writeStamps.remove(fromPath) ?: nextStamp++
    }

    override fun deleteFile(filePath: String) {
        files.remove(filePath)
        writeStamps.remove(filePath)
    }

    /** ⚠ A write-order counter, not epoch millis — see [writeStamps]. */
    override fun lastModifiedMs(filePath: String): Long? = writeStamps[filePath]
}
