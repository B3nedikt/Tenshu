package org.tenshu.file.common.slice

/**
 * Drive's projection of one row inside a `Folder.FILES` slice snapshot
 * (`sync-device-catch-up.md` §5.7.2, C8).
 *
 * ⚠⚠ **This is deliberately NOT a registered schema composite.** C8a decided the snapshot
 * envelope is platform-owned and typed while `entries` are **opaque bytes the owning service
 * seals** — so this type and its codec belong to file-service alone and never enter the
 * frozen set. Adding a field here costs a bump of [VERSION] and nothing else: no service
 * version, no migration, no peer fence. That is the entire point, and it is affordable
 * precisely because a slice is regenerable — a reader that cannot parse a newer entry format
 * falls back to an ordinary relation read.
 *
 * ## ⚠ What is deliberately absent
 *
 * The §5.7.2 sketch carried a `thumbnailRef` and a `childSliceRef`. Neither is needed, and
 * both would have been liabilities:
 *
 * - **`childSliceRef`** — a child folder's slice address is
 *   `RelationSliceSnapshot.ref(realmId, relationId, sliceKey = entityId)`, derivable from
 *   coordinates the reader already holds. Carrying it as a link would mean re-sealing every
 *   ancestor whenever a leaf changed.
 * - **`thumbnailRef`** — since C7 a thumbnail's address derives from the file's [contentId]
 *   (`FileBytesPropertyWriter.derivedThumbnailContentId`), so the reader computes it. Carrying
 *   it would duplicate a derivation that already exists and could drift from it.
 *
 * Both fall out of refs being *derived* rather than *stored*, which is the same property that
 * lets a slice be re-sealed in place.
 */
class FileSliceEntry(
    /**
     * The file's entity key — its path, which is what `File`'s `DataIdProvider` keys on.
     *
     * ⚠ Load-bearing (§5.7.2): a block for this file arriving *after* the snapshot must be
     * recognised as an update to a row the device already shows, not as a second file. The
     * share manifest this artifact is modelled on needs no such field because a link viewer
     * never syncs — this is the one place the joiner case genuinely diverges.
     */
    val entityId: String,
    /** Display name — the path's last segment, denormalised so a render needs no parsing. */
    val name: String,
    val isFolder: Boolean,
    /** Byte length; `0` for a folder. */
    val size: Long,
    /**
     * The file's content id, or empty for a folder / a file with no bytes yet. Lets a joiner
     * address the content plane — for the bytes themselves and, via the C7 derivation, for
     * either thumbnail class — without first holding the `File` entity.
     */
    val contentId: String,
    /** Whole-file plaintext hash, or empty when unknown. */
    val contentHash: String,
    val createdAtMillis: Long,
    val lastModifiedAtMillis: Long,
) {

    companion object {
        /**
         * Entry-format version, carried **inside** the opaque `entries` bytes.
         *
         * ⚠ Independent of `RelationSliceSnapshot.VERSION`: that one versions the platform
         * envelope, this one versions Drive's projection. A service bumping its own entries
         * must not touch the envelope's.
         */
        const val VERSION = 1

        /**
         * Encode [entries] into the opaque byte block `RelationSliceSnapshot.entries` carries.
         *
         * ```
         * varint(VERSION) varint(count) entry*count
         * entry := str(entityId) str(name) byte(isFolder) varint(size)
         *          str(contentId) str(contentHash) varint(createdAt) varint(lastModifiedAt)
         * str   := varint(byteLen) utf8Bytes
         * ```
         */
        fun encode(entries: List<FileSliceEntry>): ByteArray {
            val out = ArrayList<Byte>(64 + entries.size * 128)
            out.putVarint(VERSION.toLong())
            out.putVarint(entries.size.toLong())
            entries.forEach { entry ->
                out.putStr(entry.entityId)
                out.putStr(entry.name)
                out.add(if (entry.isFolder) 1 else 0)
                out.putVarint(entry.size)
                out.putStr(entry.contentId)
                out.putStr(entry.contentHash)
                out.putVarint(entry.createdAtMillis)
                out.putVarint(entry.lastModifiedAtMillis)
            }
            return out.toByteArray()
        }

        /**
         * Decode entries, or `null` if the bytes are truncated or carry a [VERSION] this build
         * does not understand.
         *
         * ⚠ Null rather than a throw, matching the envelope: an unreadable slice must make a
         * device fall back to a relation read, never fail.
         */
        fun decode(bytes: ByteArray): List<FileSliceEntry>? {
            val cursor = Cursor(bytes)
            val version = cursor.varint()?.toInt() ?: return null
            if (version > VERSION) return null
            val count = cursor.varint()?.toInt() ?: return null
            if (count < 0) return null
            val entries = ArrayList<FileSliceEntry>(minOf(count, 1024))
            repeat(count) {
                val entityId = cursor.str() ?: return null
                val name = cursor.str() ?: return null
                val isFolder = cursor.byte()?.let { it.toInt() != 0 } ?: return null
                val size = cursor.varint() ?: return null
                val contentId = cursor.str() ?: return null
                val contentHash = cursor.str() ?: return null
                val createdAt = cursor.varint() ?: return null
                val lastModifiedAt = cursor.varint() ?: return null
                entries += FileSliceEntry(
                    entityId = entityId,
                    name = name,
                    isFolder = isFolder,
                    size = size,
                    contentId = contentId,
                    contentHash = contentHash,
                    createdAtMillis = createdAt,
                    lastModifiedAtMillis = lastModifiedAt,
                )
            }
            return entries
        }

        private fun MutableList<Byte>.putVarint(value: Long) {
            require(value >= 0) { "varint must be non-negative: $value" }
            var remaining = value
            while (true) {
                val chunk = (remaining and 0x7F).toInt()
                remaining = remaining ushr 7
                if (remaining == 0L) {
                    add(chunk.toByte())
                    return
                }
                add((chunk or 0x80).toByte())
            }
        }

        private fun MutableList<Byte>.putStr(value: String) {
            val encoded = value.encodeToByteArray()
            putVarint(encoded.size.toLong())
            encoded.forEach { add(it) }
        }

        private class Cursor(private val bytes: ByteArray) {
            private var index = 0

            fun byte(): Byte? = if (index < bytes.size) bytes[index++] else null

            fun varint(): Long? {
                var result = 0L
                var shift = 0
                while (true) {
                    if (index >= bytes.size || shift > 63) return null
                    val byte = bytes[index++].toInt()
                    result = result or ((byte and 0x7F).toLong() shl shift)
                    if (byte and 0x80 == 0) return result
                    shift += 7
                }
            }

            fun str(): String? {
                val length = varint()?.toInt() ?: return null
                if (length < 0 || index + length > bytes.size) return null
                val slice = bytes.copyOfRange(index, index + length)
                index += length
                return slice.decodeToString()
            }
        }
    }
}
