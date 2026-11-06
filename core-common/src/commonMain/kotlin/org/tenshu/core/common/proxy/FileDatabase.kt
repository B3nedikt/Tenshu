package org.tenshu.core.common.proxy

import org.tenshu.core.TByteArray

interface FileDatabase {

    fun writeRawByteArray(filePath: String, bytes: ByteArray)

    fun writeByteArray(filePath: String, bytes: TByteArray)

    fun readBytes(filePath: String): TByteArray?

    /**
     * Length in bytes of the file at [filePath], or `null` if absent.
     *
     * Default reads the whole file to measure it; the file-content
     * emit path (`docs/adr/0017-file-content-two-plane-channel.md` P1) only calls this once
     * up-front and then streams chunks via [readBytesRange], so an impl
     * backed by a real filesystem SHOULD override this with an
     * O(1) `length()` to keep the emit path strictly `O(CHUNK)`. The
     * in-memory + Android impls do.
     */
    fun fileSize(filePath: String): Long? =
        readBytes(filePath)?.get()?.size?.toLong()

    /**
     * Read at most [length] bytes starting at [offset] from the file at
     * [filePath]. Returns the (possibly shorter, for the final chunk)
     * slice, or `null` if the file is absent. The returned array's size
     * is the number of bytes actually read.
     *
     * This is the **bounded-memory chunk source** for the two-plane
     * file-content emit (`docs/adr/0017-file-content-two-plane-channel.md` / Phase P1):
     * the emitter pulls one `CHUNK`-sized window at a time so peak heap
     * is `O(CHUNK)` regardless of file size. The default delegates to
     * [readBytes] (whole-file) for impls that don't override it — that
     * is correct but NOT bounded; real-filesystem impls MUST override
     * with a true positional read (`RandomAccessFile.seek` /
     * `FileChannel`) to actually realise the memory bound on that
     * platform. `InMemoryFileDatabase` (tests/desktop) and
     * `AndroidFileDatabaseImpl` override it.
     */
    fun readBytesRange(filePath: String, offset: Long, length: Int): ByteArray? {
        val all = readBytes(filePath)?.get() ?: return null
        if (offset >= all.size) return ByteArray(0)
        val end = minOf(all.size.toLong(), offset + length).toInt()
        return all.copyOfRange(offset.toInt(), end)
    }

    /**
     * Append/positional-write [bytes] at [offset] in the file at
     * [filePath], extending it if needed. The **bounded-memory sink**
     * for the two-plane file-content *read* path
     * (`docs/adr/0017-file-content-two-plane-channel.md` / Phase P2) — the manifest
     * applier reassembles a file one decrypted `CHUNK` at a time by
     * calling this once per chunk into a temp path, so read-side peak
     * heap is `O(CHUNK)` (mirror of [readBytesRange] on emit).
     *
     * The default does a whole-file read-modify-write — correct but NOT
     * bounded; real-filesystem impls MUST override with a true
     * positional write (`RandomAccessFile.seek` / `FileChannel`) to
     * actually realise the memory bound on that platform.
     * `InMemoryFileDatabase` (tests/desktop) and `AndroidFileDatabaseImpl`
     * override it.
     */
    fun writeBytesRange(filePath: String, offset: Long, bytes: ByteArray) {
        val existing = readBytes(filePath)?.get() ?: ByteArray(0)
        val end = (offset + bytes.size).toInt()
        val merged = if (end > existing.size) existing.copyOf(end) else existing.copyOf()
        bytes.copyInto(merged, destinationOffset = offset.toInt())
        writeRawByteArray(filePath, merged)
    }

    /**
     * Atomically move/rename [fromPath] onto [toPath] (replacing
     * [toPath] if present). Used by the P2 manifest applier to promote
     * a fully-fetched-and-verified temp reassembly onto the real file
     * path **only after the whole-file integrity gate passes** — so a
     * partial or tampered chunk set never surfaces as a valid file
     * (`docs/adr/0017-file-content-two-plane-channel.md`). O(1) on real filesystems
     * (`File.renameTo`) and on the in-memory store (map key move) —
     * keeps the read path `O(CHUNK)`. The default is a non-atomic
     * copy+delete fallback; impls SHOULD override with a true rename.
     */
    fun moveFile(fromPath: String, toPath: String) {
        val bytes = readBytes(fromPath)?.get() ?: return
        writeRawByteArray(toPath, bytes)
        deleteFile(fromPath)
    }

    /**
     * Delete the file at [filePath] if it exists (no-op otherwise).
     * Used to clean up the P2 temp reassembly when the integrity gate
     * fails (clean no-op + retry-on-next-resolve, no partial-corrupt
     * write left behind). Default is a no-op for impls that don't
     * model deletion; `InMemoryFileDatabase`/`AndroidFileDatabaseImpl`
     * override it.
     */
    fun deleteFile(filePath: String) {
        // no-op default
    }

    /**
     * When the local copy at [filePath] was last written, in epoch millis, or
     * `null` if the file is absent **or this implementation cannot tell**.
     *
     * The recency signal behind the local-cache ceiling's coldest-first eviction
     * (`retention-quota-model.md` Face B). ⚠ It is a *write* time, not an access
     * time: a real filesystem's `atime` is unreliable (`relatime`/`noatime`) and
     * not portable, so this orders by **least-recently-fetched**, which for a
     * write-once content cache is the same thing until something starts touching
     * files on read. Callers must treat `null` as "unknown", never as "epoch 0" —
     * an impl that cannot report it (in-memory, JS) must not thereby have its
     * whole cache classified as maximally cold.
     */
    fun lastModifiedMs(filePath: String): Long? = null

    /**
     * Free space on the volume backing this database, in bytes, or `null` if
     * unknown.
     *
     * The other half of the ceiling (`retention-quota-model.md` D6's "hard
     * free-disk floor"): the cache yields disk when the *device* is low, whatever
     * the user's configured maximum. `null` disables the floor check — an impl
     * that cannot report free space must not be treated as having none.
     */
    fun availableSpaceBytes(): Long? = null
}
