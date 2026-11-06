package org.tenshu.core.common.proxy

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.TByteArrayDto

/**
 * The **durable, disk-backed** JVM [FileDatabase]: file content bytes live as plain
 * files under a caller-chosen [root], so cached plaintext survives a process
 * restart instead of dying with it.
 *
 * **Two consumers, one implementation**: the hub
 * (`hub-hardware-and-management-service.md` §12 — a keep-everything replica whose
 * content must survive restarts *and* relay loss) and desktop Control, which would
 * otherwise re-fetch every decrypted byte from the relay after each launch (iOS's
 * counterpart is `IosFileDatabase`).
 *
 * ⚠ Android keeps [AndroidFileDatabaseImpl]: it resolves paths against
 * `context.filesDir` and exposes `getFile` for `FileByteArrayDto`'s lazy reads, so
 * it is a genuinely different class rather than this one with a different root.
 *
 * Semantics match [AndroidFileDatabaseImpl] (the other real-filesystem impl), with
 * two contract points that are load-bearing for the offline keeper:
 *
 *  - **A 0-byte file is present, not absent.** [fileSize] returns `0L` (non-null)
 *    for an existing empty file and [readBytes] returns an empty (non-null)
 *    payload, so the keeper's `fileSize(path) != null` "has local bytes" probe
 *    never re-selects an already-fetched empty file (an empty write is not an
 *    absence). [writeBytesRange] therefore *creates* the
 *    file even for a zero-length write — the manifest applier's 0-chunk-payload
 *    reassembly must materialise an empty temp file for [moveFile] to promote.
 *  - **Bounded-memory range IO.** [readBytesRange]/[writeBytesRange] are true
 *    positional `RandomAccessFile` ops so the content emit/reassembly paths stay
 *    `O(CHUNK)`; nothing is held resident (no in-RAM content cache — the disk is
 *    the store).
 *
 * All paths are confined to [root]: logical `FileDatabase` paths are realm file
 * paths (`/Photos/img.jpg`) plus internal temp/namespace siblings; anything that
 * escapes the content root after normalisation is rejected. The content at rest
 * is **plaintext** (retention-model tier 3); the at-rest answer differs by
 * deployment — full-disk encryption on the data dir's disk for the hub (LUKS,
 * `docs/how-to/hub-pi-deploy.md`, threat-model §8.15 / T-C-17), and the desktop
 * OS's own protection (FileVault et al.) for a user's machine, where A21 already
 * records the same posture for the `objects.db` replica sitting beside it.
 *
 * ⚠ Nothing evicts. Growth is bounded only by free disk on every platform — the
 * "max local data" ceiling + LRU is unbuilt everywhere (`retention-quota-model.md`
 * tier 3).
 */
class JvmFileDatabase(private val root: File) : FileDatabase {

    init {
        root.mkdirs()
    }

    /**
     * Maps a logical FileDatabase path onto a real file under [root], rejecting
     * anything that would escape it (`..` traversal). `File(root, "/x")` already
     * resolves the leading slash relative to [root]; the normalized-prefix check
     * is the backstop.
     */
    private fun resolve(filePath: String): File {
        require(filePath.isNotBlank()) { "File path cannot be blank." }
        val resolved = File(root, filePath).normalize()
        require(
            resolved.path == root.path || resolved.path.startsWith(root.path + File.separator)
        ) { "File path escapes the content root: $filePath" }
        return resolved
    }

    override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
        val file = resolve(filePath)
        if (file.isDirectory) {
            throw IOException("Cannot write file. Path '$filePath' is a directory.")
        }
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                throw IOException("Failed to create parent directory: ${parent.absolutePath}")
            }
            if (parent.isFile) {
                throw IOException("Parent path is a file, not a directory: ${parent.absolutePath}")
            }
        }
        file.outputStream().use { it.write(bytes) }
    }

    override fun writeByteArray(filePath: String, bytes: TByteArray) {
        writeRawByteArray(filePath, bytes.get())
    }

    override fun readBytes(filePath: String): TByteArray? {
        val file = resolve(filePath)
        if (!file.exists() || !file.isFile || !file.canRead()) return null
        return TByteArrayDto(file.readBytes())
    }

    /** O(1) length; `0L` (non-null) for an existing empty file, null only when absent. */
    override fun fileSize(filePath: String): Long? {
        val file = resolve(filePath)
        return if (file.exists() && file.isFile) file.length() else null
    }

    override fun readBytesRange(filePath: String, offset: Long, length: Int): ByteArray? {
        val file = resolve(filePath)
        if (!file.exists() || !file.isFile) return null
        RandomAccessFile(file, "r").use { raf ->
            if (offset >= raf.length()) return ByteArray(0)
            raf.seek(offset)
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = raf.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            return if (read == length) buf else buf.copyOf(read)
        }
    }

    override fun writeBytesRange(filePath: String, offset: Long, bytes: ByteArray) {
        val file = resolve(filePath)
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        // "rw" creates the file on open — a zero-length write at offset 0 still
        // materialises an (empty) file, which the 0-byte round trip depends on.
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            raf.write(bytes)
        }
    }

    /** Atomic O(1) rename (streamed-copy fallback), replacing the target if present. */
    override fun moveFile(fromPath: String, toPath: String) {
        val from = resolve(fromPath)
        if (!from.exists() || !from.isFile) return
        val to = resolve(toPath)
        to.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (to.exists()) to.delete()
        if (!from.renameTo(to)) {
            from.inputStream().use { input ->
                to.outputStream().use { output -> input.copyTo(output) }
            }
            from.delete()
        }
    }

    override fun deleteFile(filePath: String) {
        val file = resolve(filePath)
        if (file.exists() && file.isFile) file.delete()
    }

    /** ⚠ `File.lastModified()` returns `0L` for an absent file — mapped to null. */
    override fun lastModifiedMs(filePath: String): Long? {
        val file = resolve(filePath)
        if (!file.exists() || !file.isFile) return null
        return file.lastModified().takeIf { it > 0L }
    }

    override fun availableSpaceBytes(): Long? = root.usableSpace.takeIf { it > 0L }
}
