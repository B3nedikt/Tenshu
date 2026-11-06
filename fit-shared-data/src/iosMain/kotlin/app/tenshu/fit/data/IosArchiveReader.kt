package app.tenshu.fit.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.NSMakeRange
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.subdataWithRange
import platform.posix.memcpy
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.uByteVar
import platform.zlib.z_stream

/**
 * Reads the usable members of a Takeout archive on **iOS** — the twin of [JvmArchiveReader], which
 * is `java.util.zip` and therefore JVM-only. Without this, iOS Fit had no route to Google Fit
 * history at all: Google Fit never feeds HealthKit either, so a Takeout export is the only source,
 * and the app could not open one.
 *
 * **No third-party dependency.** Kotlin/Native ships `platform.zlib` for every Apple target, so the
 * DEFLATE half is the system zlib; the container half (central directory, local headers, ZIP64) is
 * parsed here. That was the deciding factor against ZIPFoundation or an SPM package — a health
 * importer is not worth a supply-chain edge, and the format is small enough to read directly.
 *
 * **Central directory, not a sequential scan.** [JvmArchiveReader] streams front-to-back because
 * `ZipInputStream` does; this seeks the directory at the end of the file instead, which is both
 * more robust (a streamed local header may defer its sizes to a trailing data descriptor, which a
 * sequential reader has to infer) and cheaper — every member's *name* is known before a single
 * compressed byte is touched, so [TakeoutParser.isRelevant] filters out the photos and mail of a
 * mixed export for free.
 *
 * **Memory.** The archive is opened `NSDataReadingMappedIfSafe`, so the file is mapped rather than
 * read: a 96 MB (or 10 GB) export costs address space, not resident memory. Only one member's
 * inflated bytes are live at a time, matching the JVM twin's contract — the consumer parses,
 * persists and drops each file before the next is read.
 *
 * The same two caps as the JVM twin guard a hostile archive: a per-member ceiling (this runs in the
 * app's process) and a member count well above a real 10-year export.
 */
object IosArchiveReader {

    /** A single member's uncompressed bytes are refused past this — see the class note. */
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** Cap on members read, so a pathological archive cannot exhaust the import by count. */
    private const val MAX_ENTRIES = 50_000

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val ZIP64_EOCD_LOCATOR_SIGNATURE = 0x07064b50
    private const val ZIP64_EOCD_SIGNATURE = 0x06064b50
    private const val CENTRAL_ENTRY_SIGNATURE = 0x02014b50

    /** EOCD is 22 bytes plus up to 64 KiB of comment, so it lives in the last ~64 KiB. */
    private const val EOCD_SEARCH_BYTES = 66 * 1024

    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATE = 8

    /** Raw DEFLATE (no zlib header) — what a zip member's payload is. */
    private const val RAW_DEFLATE_WINDOW_BITS = -15

    /**
     * A lazily-read [ActivityFileSource] over the zip at [path] (a plain filesystem path, not a
     * `file://` URL). Returns `null` when the file cannot be opened or is not a readable zip, so
     * the caller can fall back to treating the pick as a single activity file rather than failing
     * the import outright.
     *
     * `count` is known here — unlike the streamed JVM twin — because the central directory lists
     * the relevant members before anything is decompressed, which makes the import's progress read
     * as "n of m" instead of an open-ended count-up.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun source(
        path: String,
        isRelevant: (String) -> Boolean,
        /** The archive as the user picked it — recorded on the import run (DS-2 / D6). */
        archiveName: String? = path.substringAfterLast('/').takeIf { it.isNotBlank() },
        archiveBytes: Long? = null,
    ): ActivityFileSource? {
        val data = NSData.dataWithContentsOfFile(path, NSDataReadingMappedIfSafe, null)
        if (data == null) {
            fitLog("archive: could not open $path")
            return null
        }
        val entries = runCatching { readCentralDirectory(data, isRelevant) }
            .onFailure { fitLog("archive: not a readable zip — falling back to a single-file pick", it) }
            .getOrNull()
            ?: return null

        fitLog("archive: ${entries.size} usable member(s) in the directory")
        return object : ActivityFileSource {
            override val count: Int get() = entries.size
            override val displayName: String? get() = archiveName
            // The mapped NSData's length is the archive's own size — free here, unlike the
            // streamed JVM twin, which never sees the whole file at once.
            override val totalBytes: Long get() = archiveBytes ?: data.length.toLong()

            override suspend fun forEach(consume: suspend (ActivityFile) -> Unit) {
                var kept = 0
                entries.forEach { entry ->
                    // Each member's bytes become collectable once `consume` returns and before the
                    // next is inflated — the whole point of the lazy source.
                    val bytes = runCatching { readEntry(data, entry) }
                        .onFailure { fitLog("archive: ${entry.name} failed to inflate — skipped", it) }
                        .getOrNull()
                        ?: return@forEach
                    kept++
                    consume(ActivityFile(name = entry.name, bytes = bytes))
                }
                fitLog("archive: read $kept usable member(s)")
            }
        }
    }

    // ── central directory ───────────────────────────────────────────────────────

    /**
     * The relevant members, in directory order. Throws on a malformed container — the caller turns
     * that into "not a zip" rather than a failed import.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun readCentralDirectory(data: NSData, isRelevant: (String) -> Boolean): List<Entry> {
        val fileSize = data.length.toLong()
        val tailLength = minOf(fileSize, EOCD_SEARCH_BYTES.toLong()).toInt()
        val tail = data.slice(fileSize - tailLength, tailLength)
        val eocd = tail.lastIndexOfSignature(EOCD_SIGNATURE)
            ?: error("no end-of-central-directory record")

        var entryCount = tail.u16(eocd + 10).toLong()
        var directorySize = tail.u32(eocd + 12)
        var directoryOffset = tail.u32(eocd + 16)

        // ZIP64: the 16/32-bit fields saturate and the real values live in a separate record. A
        // full Takeout (photos + mail) can genuinely exceed 65 535 members, so this is not academic.
        if (entryCount == 0xFFFFL || directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL) {
            val locator = tail.lastIndexOfSignature(ZIP64_EOCD_LOCATOR_SIGNATURE)
                ?: error("zip64 sizes but no zip64 locator")
            val zip64Offset = tail.u64(locator + 8)
            val zip64 = data.slice(zip64Offset, 56)
            require(zip64.u32(0) == ZIP64_EOCD_SIGNATURE.toLong()) { "bad zip64 end-of-central-directory" }
            entryCount = zip64.u64(32)
            directorySize = zip64.u64(40)
            directoryOffset = zip64.u64(48)
        }

        require(directoryOffset >= 0 && directoryOffset + directorySize <= fileSize) {
            "central directory runs past the end of the file"
        }
        val directory = data.slice(directoryOffset, directorySize.toInt())

        val entries = ArrayList<Entry>()
        var at = 0
        var seen = 0L
        while (seen < entryCount && at + 46 <= directory.size) {
            require(directory.u32(at) == CENTRAL_ENTRY_SIGNATURE.toLong()) { "bad central directory entry" }
            val method = directory.u16(at + 10)
            var compressedSize = directory.u32(at + 20)
            var uncompressedSize = directory.u32(at + 24)
            val nameLength = directory.u16(at + 28)
            val extraLength = directory.u16(at + 30)
            val commentLength = directory.u16(at + 32)
            var localOffset = directory.u32(at + 42)
            val name = directory.utf8(at + 46, nameLength)

            // Per-entry ZIP64 extra field — present only for the fields that saturated, in a fixed
            // order, so each is consumed conditionally.
            if (compressedSize == 0xFFFFFFFFL || uncompressedSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                var extraAt = at + 46 + nameLength
                val extraEnd = extraAt + extraLength
                while (extraAt + 4 <= extraEnd) {
                    val headerId = directory.u16(extraAt)
                    val size = directory.u16(extraAt + 2)
                    if (headerId == 0x0001) {
                        var field = extraAt + 4
                        if (uncompressedSize == 0xFFFFFFFFL) { uncompressedSize = directory.u64(field); field += 8 }
                        if (compressedSize == 0xFFFFFFFFL) { compressedSize = directory.u64(field); field += 8 }
                        if (localOffset == 0xFFFFFFFFL) { localOffset = directory.u64(field) }
                        break
                    }
                    extraAt += 4 + size
                }
            }

            at += 46 + nameLength + extraLength + commentLength
            seen++

            val isDirectory = name.endsWith("/")
            if (isDirectory || !isRelevant(name)) continue
            if (uncompressedSize > MAX_ENTRY_BYTES) {
                fitLog("archive: $name exceeds ${MAX_ENTRY_BYTES / 1024 / 1024} MiB — skipped")
                continue
            }
            if (entries.size >= MAX_ENTRIES) {
                fitLog("archive: stopping at the $MAX_ENTRIES-member cap")
                break
            }
            entries += Entry(name, method, compressedSize, uncompressedSize, localOffset)
        }
        return entries
    }

    // ── one member ──────────────────────────────────────────────────────────────

    /** The member's uncompressed bytes. Throws on a truncated or unsupported entry. */
    @OptIn(ExperimentalForeignApi::class)
    private fun readEntry(data: NSData, entry: Entry): ByteArray {
        // The local header repeats the name/extra lengths, and they may differ from the central
        // directory's — the data offset must come from the local header, never from the directory.
        val header = data.slice(entry.localOffset, 30)
        val nameLength = header.u16(26)
        val extraLength = header.u16(28)
        val dataAt = entry.localOffset + 30 + nameLength + extraLength
        require(dataAt + entry.compressedSize <= data.length.toLong()) { "${entry.name} runs past the end of the file" }

        val compressed = data.slice(dataAt, entry.compressedSize.toInt())
        return when (entry.method) {
            METHOD_STORED -> compressed
            METHOD_DEFLATE -> inflateRaw(compressed, entry.uncompressedSize.toInt())
            else -> error("${entry.name} uses unsupported compression method ${entry.method}")
        }
    }

    /**
     * Raw DEFLATE through the system zlib. The output size is known from the directory, so this is
     * a single-shot inflate into an exactly-sized buffer rather than a grow-and-copy loop.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun inflateRaw(compressed: ByteArray, expectedSize: Int): ByteArray {
        if (expectedSize == 0) return ByteArray(0)
        val out = ByteArray(expectedSize)
        memScoped {
            val stream = alloc<z_stream>()
            require(inflateInit2(stream.ptr, RAW_DEFLATE_WINDOW_BITS) == Z_OK) { "inflateInit2 failed" }
            try {
                compressed.usePinned { input ->
                    out.usePinned { output ->
                        stream.next_in = input.addressOf(0).reinterpret()
                        stream.avail_in = compressed.size.convert()
                        stream.next_out = output.addressOf(0).reinterpret()
                        stream.avail_out = expectedSize.convert()
                        val result = inflate(stream.ptr, 0)
                        // Z_BUF_ERROR with the output filled means the member ended exactly on the
                        // buffer boundary — the directory's size was right, so this is not an error.
                        require(result == Z_STREAM_END || (result == Z_BUF_ERROR && stream.avail_out.toInt() == 0)) {
                            "inflate failed ($result)"
                        }
                    }
                }
            } finally {
                inflateEnd(stream.ptr)
            }
        }
        return out
    }

    private class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localOffset: Long,
    )

    // ── little-endian readers ───────────────────────────────────────────────────

    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.slice(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && offset + length <= this.length.toLong()) { "read past the end of the file" }
        if (length == 0) return ByteArray(0)
        val sub = subdataWithRange(NSMakeRange(offset.convert(), length.convert()))
        val out = ByteArray(length)
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), sub.bytes, sub.length) }
        return out
    }

    private fun ByteArray.u16(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(at: Int): Long {
        var value = 0L
        for (i in 3 downTo 0) value = (value shl 8) or (this[at + i].toLong() and 0xFF)
        return value
    }

    private fun ByteArray.u64(at: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (this[at + i].toLong() and 0xFF)
        return value
    }

    private fun ByteArray.utf8(at: Int, length: Int): String =
        decodeToString(at, at + length)

    /** The last offset in this buffer carrying [signature], or null. */
    private fun ByteArray.lastIndexOfSignature(signature: Int): Int? {
        for (at in size - 4 downTo 0) {
            if (u32(at) == signature.toLong()) return at
        }
        return null
    }
}
