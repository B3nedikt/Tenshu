package org.tenshu.inference.proxy.catalog

import org.tenshu.core.common.proxy.FileDatabase

/** An archive [TarReader] will not unpack. The message names the entry and the reason. */
class TarFormatException(message: String) : Exception(message)

/** One regular file [TarReader.unpack] wrote, by its path inside the archive. */
data class TarEntry(val path: String, val size: Long)

/**
 * Unpacks an **uncompressed ustar** archive from one [FileDatabase] path into a directory of the
 * same database (`ios-on-device-inference.md` §3.4).
 *
 * A Core ML `.mlpackage` is a directory and the model pipeline moves files, so each tower travels
 * as one tar. The archive's digest is checked against the signed catalog before this reader sees
 * it; what follows is defence in depth, and it refuses rather than repairs.
 *
 * ⚠⚠ **Bounded memory.** A tower's `weight.bin` is hundreds of megabytes. Both directions go
 * through [FileDatabase.readBytesRange] / [FileDatabase.writeBytesRange] in [CHUNK]-sized windows;
 * nothing here holds an entry, let alone the archive.
 *
 * ⚠ **Only what `scripts/inference/export-coreml-towers.py` writes is accepted**: regular files
 * and directories, with names that fit a ustar header. A pax or GNU extension header, a link, a
 * device node, a base-256 size and a bad header checksum are all refused by name.
 *
 * ⚠ A directory entry is checked and then skipped: [FileDatabase] has no directories, and a file
 * write creates its parents. An archive's **empty** directory is therefore not recreated.
 */
object TarReader {

    /** A tar is a sequence of blocks this long; a header is one block. */
    const val BLOCK: Int = 512

    /** The copy window. 1 MiB keeps a 530 MB tower at ~500 positional writes. */
    const val CHUNK: Int = 1 shl 20

    /**
     * @param destination the directory, inside [files], that entry paths are resolved under.
     * @return the regular files written, in archive order.
     * @throws TarFormatException for a hostile, unsupported or truncated archive. ⚠ Entries before
     *   the offending one have already been written: the caller owns [destination] and deletes it.
     */
    fun unpack(files: FileDatabase, archivePath: String, destination: String): List<TarEntry> {
        val archiveSize: Long = files.fileSize(archivePath)
            ?: throw TarFormatException("no archive at $archivePath")
        val root: String = destination.trimEnd('/')
        val written = ArrayList<TarEntry>()
        var offset = 0L

        while (true) {
            val header: ByteArray = readExactly(files, archivePath, offset, BLOCK, archiveSize, "a header")
            offset += BLOCK
            // ⚠ The end of an archive is a zero block. Running out of bytes without one is the
            // truncation case, and `readExactly` has already refused it.
            if (header.all { it == 0.toByte() }) return written

            verifyChecksum(header, offset - BLOCK)
            val path: String = safePath(entryName(header))
            val size: Long = entrySize(header, path)

            when (val type: Int = header[TYPE_OFFSET].toInt()) {
                TYPE_FILE, TYPE_FILE_LEGACY -> {
                    copyEntry(files, archivePath, offset, size, archiveSize, "$root/$path")
                    written += TarEntry(path, size)
                }
                TYPE_DIRECTORY -> if (size != 0L) {
                    throw TarFormatException("directory entry '$path' declares $size bytes")
                }
                else -> throw TarFormatException(
                    "entry '$path' has type '${type.toChar()}': only regular files and directories are unpacked",
                )
            }
            // An entry's bytes are padded to a whole block.
            offset += (size + BLOCK - 1) / BLOCK * BLOCK
        }
    }

    private fun copyEntry(
        files: FileDatabase,
        archivePath: String,
        from: Long,
        size: Long,
        archiveSize: Long,
        target: String,
    ) {
        // ⚠ A positional write over a longer file left by an earlier, interrupted unpack would keep
        // that file's tail.
        files.deleteFile(target)
        if (size == 0L) {
            files.writeRawByteArray(target, ByteArray(0))
            return
        }
        var copied = 0L
        while (copied < size) {
            val want: Int = minOf(CHUNK.toLong(), size - copied).toInt()
            val bytes: ByteArray = readExactly(files, archivePath, from + copied, want, archiveSize, "'$target'")
            files.writeBytesRange(target, copied, bytes)
            copied += want
        }
    }

    private fun readExactly(
        files: FileDatabase,
        archivePath: String,
        offset: Long,
        length: Int,
        archiveSize: Long,
        what: String,
    ): ByteArray {
        if (offset + length > archiveSize) {
            throw TarFormatException(
                "truncated archive: $what needs bytes $offset..${offset + length}, the archive has $archiveSize",
            )
        }
        val bytes: ByteArray? = files.readBytesRange(archivePath, offset, length)
        if (bytes == null || bytes.size != length) {
            throw TarFormatException(
                "truncated archive: read ${bytes?.size ?: 0} of $length bytes for $what at $offset",
            )
        }
        return bytes
    }

    /** ustar splits a long name into `prefix` + `/` + `name`. */
    private fun entryName(header: ByteArray): String {
        val name: String = text(header, NAME_OFFSET, NAME_LENGTH)
        val isUstar: Boolean = text(header, MAGIC_OFFSET, MAGIC_LENGTH).startsWith("ustar")
        val prefix: String = if (isUstar) text(header, PREFIX_OFFSET, PREFIX_LENGTH) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    /**
     * ⚠⚠ **The guard that keeps an archive inside its directory.** An absolute path or a `..`
     * segment would write wherever the name says, with the app's own file permissions.
     */
    private fun safePath(name: String): String {
        if (name.isEmpty()) throw TarFormatException("entry with an empty name")
        if (name.startsWith('/')) throw TarFormatException("entry '$name' is an absolute path")
        if ('\\' in name) throw TarFormatException("entry '$name' contains a backslash")
        val segments: List<String> = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) throw TarFormatException("entry '$name' contains a '..' segment")
        if (segments.isEmpty()) throw TarFormatException("entry '$name' names no file")
        return segments.joinToString("/")
    }

    private fun entrySize(header: ByteArray, path: String): Long {
        // GNU's base-256 form sets the high bit; a tower fits the octal field (8 GiB) many times over.
        if (header[SIZE_OFFSET].toInt() and 0x80 != 0) {
            throw TarFormatException("entry '$path' uses a base-256 size")
        }
        return octal(header, SIZE_OFFSET, SIZE_LENGTH)
            ?: throw TarFormatException("entry '$path' has an unreadable size")
    }

    /** The checksum is the byte sum of the header with its own field read as eight spaces. */
    private fun verifyChecksum(header: ByteArray, at: Long) {
        val declared: Long = octal(header, CHECKSUM_OFFSET, CHECKSUM_LENGTH)
            ?: throw TarFormatException("header at $at has an unreadable checksum")
        var sum = 0L
        for (i in 0 until BLOCK) {
            val inChecksum: Boolean = i >= CHECKSUM_OFFSET && i < CHECKSUM_OFFSET + CHECKSUM_LENGTH
            sum += if (inChecksum) ' '.code else header[i].toInt() and 0xFF
        }
        if (sum != declared) throw TarFormatException("header at $at fails its checksum: not a tar header")
    }

    private fun text(header: ByteArray, offset: Int, length: Int): String {
        var end: Int = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return header.decodeToString(offset, end)
    }

    private fun octal(header: ByteArray, offset: Int, length: Int): Long? {
        val digits: String = text(header, offset, length).trim()
        if (digits.isEmpty() || digits.any { it !in '0'..'7' }) return null
        return digits.toLong(radix = 8)
    }

    private const val NAME_OFFSET = 0
    private const val NAME_LENGTH = 100
    private const val SIZE_OFFSET = 124
    private const val SIZE_LENGTH = 12
    private const val CHECKSUM_OFFSET = 148
    private const val CHECKSUM_LENGTH = 8
    private const val TYPE_OFFSET = 156
    private const val MAGIC_OFFSET = 257
    private const val MAGIC_LENGTH = 6
    private const val PREFIX_OFFSET = 345
    private const val PREFIX_LENGTH = 155

    private const val TYPE_FILE = '0'.code
    private const val TYPE_FILE_LEGACY = 0
    private const val TYPE_DIRECTORY = '5'.code
}
