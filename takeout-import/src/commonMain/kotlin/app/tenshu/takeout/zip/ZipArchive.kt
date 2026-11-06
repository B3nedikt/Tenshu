package app.tenshu.takeout.zip

/**
 * A seekable byte source — a file, usually.
 *
 * ⚠⚠ **Seekable, and that is not a detail.** A ZIP's index lives at the *end* of the file, so a
 * reader cannot stream forward from byte zero and know what it holds. Android hands out a
 * `content://` stream that is *not* seekable, which is why B2 has to copy a picked export into
 * cache before this can read it (`takeout-import-and-recall.md` §4).
 */
interface ZipSource {

    val size: Long

    /**
     * [length] bytes from [offset]. Returning fewer is allowed only at the end of the source;
     * the reader treats a short read as a truncated archive rather than retrying.
     */
    fun read(offset: Long, length: Int): ByteArray
}

/**
 * An archive already in memory.
 *
 * ⚠ Fine for a location-history export (megabytes) and wrong for a photo export (tens of
 * gigabytes) — those want a file-backed source, which is the platform's to provide (B2).
 */
class ByteArrayZipSource(private val bytes: ByteArray) : ZipSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        val from = offset.toInt().coerceIn(0, bytes.size)
        val to = (from + length).coerceIn(from, bytes.size)
        return bytes.copyOfRange(from, to)
    }
}

/** Raw DEFLATE (no zlib wrapper) → the original bytes. See the module KDoc for why it is injected. */
typealias Inflate = (deflated: ByteArray, uncompressedSize: Int) -> ByteArray

/** One file in the archive, as its central-directory record describes it. */
data class ZipEntry(
    val name: String,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val method: Int,
    val localHeaderOffset: Long,
) {
    /** ZIP marks a directory by a trailing slash and nothing else. */
    val isDirectory: Boolean get() = name.endsWith("/")
}

/**
 * A ZIP file, read through its **central directory** (B1).
 *
 * ⚠⚠ **The central directory, never the local headers.** A local header is allowed to carry zeros
 * for both sizes and defer them to a data descriptor *after* the compressed bytes (general-purpose
 * bit 3), which is exactly what a streaming writer emits — and Google's exporter is one. A reader
 * that trusts local headers reads nothing from those archives, and reads it silently.
 *
 * ⚠ **Zip64 is supported because a photo export needs it.** Past 4 GB — or 65 535 entries, which a
 * decade of photos passes long before the size does — the classic end-of-central-directory record
 * stores `0xFFFF…` sentinels and the real values live in the zip64 record. Treating the sentinel as
 * a count is how a reader "successfully" finds 65 535 of 200 000 photos.
 *
 * ⚠ **Untrusted input** (`timeline-ingestion.md` §6): a malformed, truncated or simply
 * not-a-ZIP file yields `null` from [open] and never throws. The user picked a file from a phone;
 * a parse failure must not be a crash.
 *
 * ⚠ Nothing here is held in memory but the directory. [read] fetches one entry's bytes on demand,
 * which is what makes a 40 GB export readable at all (TB-D1).
 */
class ZipArchive private constructor(
    private val source: ZipSource,
    private val inflate: Inflate,
    /** Every file in the archive, directories excluded, in central-directory order. */
    val entries: List<ZipEntry>,
) {

    /**
     * One entry's bytes, or `null` when the archive is lying about it (an unknown compression
     * method, a truncated payload, a local header that is not one).
     */
    fun read(entry: ZipEntry): ByteArray? = runCatching {
        val header = source.read(entry.localHeaderOffset, LOCAL_HEADER_SIZE)
        if (header.size < LOCAL_HEADER_SIZE || header.u32(0) != LOCAL_HEADER_SIGNATURE) return null
        // ⚠ The local header's *name* and *extra* lengths are trustworthy (they describe bytes
        // that are physically there); its sizes are not, and are taken from the directory instead.
        val dataAt = entry.localHeaderOffset + LOCAL_HEADER_SIZE + header.u16(26) + header.u16(28)
        val compressed = source.read(dataAt, entry.compressedSize.toIntOrThrow())
        if (compressed.size.toLong() != entry.compressedSize) return null

        when (entry.method) {
            METHOD_STORED -> compressed
            METHOD_DEFLATE -> inflate(compressed, entry.uncompressedSize.toIntOrThrow())
            else -> null
        }
    }.getOrNull()

    /**
     * This archive as a one-pass [app.tenshu.takeout.TakeoutArchive].
     *
     * ⚠ A seekable archive can do more than the seam asks for (it knows every name before reading
     * anything), and it is deliberately narrowed to the same shape so both readers feed one import
     * path — the streaming one on Android cannot do better, and two import paths is how they drift.
     */
    fun asTakeoutArchive(): app.tenshu.takeout.TakeoutArchive =
        app.tenshu.takeout.TakeoutArchive { wanted, consume ->
            entries.forEach { entry ->
                if (wanted(entry.name)) consume(entry.name, read(entry))
            }
        }

    companion object {

        /**
         * Reads [source]'s directory, or `null` if it does not hold one.
         *
         * ⚠ This is the whole cost of opening an archive: one tail read plus the directory. The
         * entries' bytes are never touched.
         */
        fun open(source: ZipSource, inflate: Inflate): ZipArchive? = runCatching {
            val eocd = findEndOfCentralDirectory(source) ?: return null
            val entries = readCentralDirectory(source, eocd) ?: return null
            ZipArchive(source, inflate, entries)
        }.getOrNull()

        /** Where the central directory starts, and how many records it holds. */
        private data class Eocd(val offset: Long, val count: Int)

        private fun findEndOfCentralDirectory(source: ZipSource): Eocd? {
            // The record is 22 bytes plus a comment of up to 64 KB, so the tail is where it is.
            val tailLength = minOf(source.size, (MAX_COMMENT + EOCD_SIZE).toLong()).toInt()
            if (tailLength < EOCD_SIZE) return null
            val tailAt = source.size - tailLength
            val tail = source.read(tailAt, tailLength)

            // ⚠ Scanned BACKWARDS: the signature is four ordinary bytes and may legitimately occur
            // inside a comment — or inside a stored file, if the comment is long. The last one is
            // the record.
            for (i in tail.size - EOCD_SIZE downTo 0) {
                if (tail.u32(i) != EOCD_SIGNATURE) continue
                val count = tail.u16(i + 10)
                val offset = tail.u32(i + 16)
                if (count != ZIP64_SENTINEL_16 && offset != ZIP64_SENTINEL_32) {
                    return Eocd(offset, count)
                }
                return zip64(source, tail, i, tailAt)
            }
            return null
        }

        /**
         * The zip64 records the classic one points at.
         *
         * ⚠ Reached whenever *either* sentinel is present — a 200 000-photo export trips the count
         * long before a 4 GB archive trips the offset, and treating `0xFFFF` as a real count reads
         * 65 535 of them and reports success.
         */
        private fun zip64(source: ZipSource, tail: ByteArray, eocdAt: Int, tailAt: Long): Eocd? {
            val locatorAt = eocdAt - ZIP64_LOCATOR_SIZE
            if (locatorAt < 0 || tail.u32(locatorAt) != ZIP64_LOCATOR_SIGNATURE) return null
            val recordAt = tail.u64(locatorAt + 8)
            val record = source.read(recordAt, ZIP64_EOCD_SIZE)
            if (record.size < ZIP64_EOCD_SIZE || record.u32(0) != ZIP64_EOCD_SIGNATURE) return null
            return Eocd(offset = record.u64(48), count = record.u64(32).toIntOrThrow())
        }

        private fun readCentralDirectory(source: ZipSource, eocd: Eocd): List<ZipEntry>? {
            val entries = ArrayList<ZipEntry>(minOf(eocd.count, 1_024))
            var at = eocd.offset
            repeat(eocd.count) {
                val header = source.read(at, CENTRAL_HEADER_SIZE)
                if (header.size < CENTRAL_HEADER_SIZE || header.u32(0) != CENTRAL_SIGNATURE) {
                    // A directory that ends early is a truncated archive: keep what was read
                    // rather than discarding an otherwise usable export.
                    return entries
                }
                val nameLength = header.u16(28)
                val extraLength = header.u16(30)
                val commentLength = header.u16(32)
                val name = source.read(at + CENTRAL_HEADER_SIZE, nameLength).decodeToString()
                val extra = source.read(at + CENTRAL_HEADER_SIZE + nameLength, extraLength)

                var compressed = header.u32(20)
                var uncompressed = header.u32(24)
                var localOffset = header.u32(42)
                if (compressed == ZIP64_SENTINEL_32 ||
                    uncompressed == ZIP64_SENTINEL_32 ||
                    localOffset == ZIP64_SENTINEL_32
                ) {
                    // ⚠ The zip64 extra field packs ONLY the fields that overflowed, in a fixed
                    // order and with no placeholders — so which value sits at which offset depends
                    // on which sentinels are present. Reading it positionally is the classic bug.
                    val zip64 = zip64ExtraField(extra) ?: return@repeat
                    var cursor = 0
                    if (uncompressed == ZIP64_SENTINEL_32) {
                        uncompressed = zip64.u64(cursor); cursor += 8
                    }
                    if (compressed == ZIP64_SENTINEL_32) {
                        compressed = zip64.u64(cursor); cursor += 8
                    }
                    if (localOffset == ZIP64_SENTINEL_32) {
                        localOffset = zip64.u64(cursor)
                    }
                }

                val entry = ZipEntry(
                    name = name,
                    compressedSize = compressed,
                    uncompressedSize = uncompressed,
                    method = header.u16(10),
                    localHeaderOffset = localOffset,
                )
                if (!entry.isDirectory) entries += entry
                at += CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
            }
            return entries
        }

        /** The `0x0001` extra field's payload, or `null` when the archive claims zip64 without one. */
        private fun zip64ExtraField(extra: ByteArray): ByteArray? {
            var at = 0
            while (at + 4 <= extra.size) {
                val id = extra.u16(at)
                val length = extra.u16(at + 2)
                if (at + 4 + length > extra.size) return null
                if (id == ZIP64_EXTRA_ID) return extra.copyOfRange(at + 4, at + 4 + length)
                at += 4 + length
            }
            return null
        }

        private const val EOCD_SIGNATURE = 0x06054b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
        private const val ZIP64_EOCD_SIGNATURE = 0x06064b50L

        private const val EOCD_SIZE = 22
        private const val CENTRAL_HEADER_SIZE = 46
        private const val LOCAL_HEADER_SIZE = 30
        private const val ZIP64_LOCATOR_SIZE = 20
        private const val ZIP64_EOCD_SIZE = 56
        private const val ZIP64_EXTRA_ID = 0x0001
        private const val MAX_COMMENT = 65_535

        private const val ZIP64_SENTINEL_16 = 0xFFFF
        private const val ZIP64_SENTINEL_32 = 0xFFFFFFFFL

        const val METHOD_STORED = 0
        const val METHOD_DEFLATE = 8
    }
}

// ZIP is little-endian throughout, and every width below is unsigned — hence the widening.

private fun ByteArray.u16(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32(at: Int): Long =
    (this[at].toLong() and 0xFF) or
        ((this[at + 1].toLong() and 0xFF) shl 8) or
        ((this[at + 2].toLong() and 0xFF) shl 16) or
        ((this[at + 3].toLong() and 0xFF) shl 24)

private fun ByteArray.u64(at: Int): Long {
    var value = 0L
    for (i in 7 downTo 0) value = (value shl 8) or (this[at + i].toLong() and 0xFF)
    return value
}

/**
 * ⚠ A single entry larger than 2 GB cannot be materialised as a `ByteArray` on any platform we
 * ship, so it throws here and [ZipArchive.read] reports it as unreadable rather than truncating.
 * The archive *containing* it is still fine — that is the zip64 case, and it works.
 */
private fun Long.toIntOrThrow(): Int {
    require(this in 0..Int.MAX_VALUE) { "entry too large to read into memory: $this bytes" }
    return toInt()
}
