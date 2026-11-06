package org.tenshu.timeline.common.slice

/**
 * Timeline's projection of one row inside a `MOMENTS` slice snapshot
 * (`sync-device-catch-up.md` §5.7.3, C9) — the second service to join the joiner snapshot,
 * and the first whose relation is a **realm-wide singleton** rather than one instance per
 * parent entity.
 *
 * ⚠⚠ **Not a registered schema composite**, for the same reason [FileSliceEntry]'s KDoc gives:
 * C8a made the envelope platform-owned and typed while `entries` stay opaque bytes the owning
 * service seals. Adding a field here costs a bump of [VERSION] and nothing else — no service
 * version, no migration, no peer fence — which is affordable precisely because a slice is
 * regenerable.
 *
 * ## ⚠ What is deliberately absent
 *
 * - **`sources`** — the cross-source provenance list `MomentMerger` unions under
 *   `ConflictStrategy.Merge`. A slice row is **provisional**: it exists so a joiner can render
 *   before it has replayed anything, and the first real block replaces it wholesale. Carrying a
 *   half of a mergeable set into a create-only applier would risk a row that looks merged and is
 *   not, which is worse than a row that is plainly incomplete.
 * - **the hydrated subject** — [subjectKind] + [subjectId] are the reference, exactly as stored.
 *   The projector hydrates a `Place`/`Workout` from its own service, and a snapshot that inlined
 *   the hydrated value would go stale independently of the row it describes.
 */
class MomentSliceEntry(
    /** The moment's own stable key — what `MomentDataIdProvider` keys on. */
    val momentId: String,
    /** Epoch millis. The reader re-groups by **local** day from this; see the producer's KDoc. */
    val timestampMillis: Long,
    /** `Activity` / `Visit` / `Event` / `Quiet` / `Movement` — drives the feed's anatomy. */
    val kind: String,
    val title: String,
    /** `SubjectKind` — `Place`, `Workout`, `Trace`, or none. */
    val subjectKind: String,
    /** The subject's id within its own service; empty when the moment has no subject. */
    val subjectId: String,
    val durationSec: Long,
    /**
     * The canonical this moment was folded into, or empty when it is live. Carried because the
     * projector **hides** superseded moments — omitting it would make a joiner render duplicates
     * that its peers have already reconciled away.
     */
    val supersededBy: String,
) {

    companion object {
        /**
         * Entry-format version, carried **inside** the opaque `entries` bytes and independent of
         * `RelationSliceSnapshot.VERSION` (which versions the platform envelope).
         */
        const val VERSION = 1

        /**
         * ```
         * varint(VERSION) varint(count) entry*count
         * entry := str(momentId) varint(timestampMillis) str(kind) str(title)
         *          str(subjectKind) str(subjectId) varint(durationSec) str(supersededBy)
         * str   := varint(byteLen) utf8Bytes
         * ```
         */
        fun encode(entries: List<MomentSliceEntry>): ByteArray {
            val out = ArrayList<Byte>(64 + entries.size * 96)
            out.putVarint(VERSION.toLong())
            out.putVarint(entries.size.toLong())
            entries.forEach { entry ->
                out.putStr(entry.momentId)
                out.putVarint(entry.timestampMillis)
                out.putStr(entry.kind)
                out.putStr(entry.title)
                out.putStr(entry.subjectKind)
                out.putStr(entry.subjectId)
                out.putVarint(entry.durationSec)
                out.putStr(entry.supersededBy)
            }
            return out.toByteArray()
        }

        /**
         * Decode, or `null` for truncated bytes or a newer [VERSION].
         *
         * ⚠ Null rather than a throw, matching the envelope and `FileSliceEntry`: an unreadable
         * slice must make a device fall back to an ordinary relation read, never fail.
         */
        fun decode(bytes: ByteArray): List<MomentSliceEntry>? {
            val cursor = Cursor(bytes)
            val version = cursor.varint()?.toInt() ?: return null
            if (version > VERSION) return null
            val count = cursor.varint()?.toInt() ?: return null
            if (count < 0) return null
            val entries = ArrayList<MomentSliceEntry>(minOf(count, 1024))
            repeat(count) {
                val momentId = cursor.str() ?: return null
                val timestampMillis = cursor.varint() ?: return null
                val kind = cursor.str() ?: return null
                val title = cursor.str() ?: return null
                val subjectKind = cursor.str() ?: return null
                val subjectId = cursor.str() ?: return null
                val durationSec = cursor.varint() ?: return null
                val supersededBy = cursor.str() ?: return null
                entries += MomentSliceEntry(
                    momentId = momentId,
                    timestampMillis = timestampMillis,
                    kind = kind,
                    title = title,
                    subjectKind = subjectKind,
                    subjectId = subjectId,
                    durationSec = durationSec,
                    supersededBy = supersededBy,
                )
            }
            return entries
        }

        /**
         * The slice key for an instant — a **UTC** calendar day, `YYYY-MM-DD`.
         *
         * ⚠⚠ **UTC, deliberately, even though the feed groups by LOCAL day.** A slice key is an
         * address two devices must agree on: seal it in the writer's local zone and a peer three
         * time zones away asks for a bucket whose contents it cannot predict. Grouping stays a
         * *rendering* concern — entries carry [timestampMillis], so the UI re-groups locally from
         * the same bytes.
         *
         * ⚠ The cost, stated: a local day straddles two UTC buckets for most of the world, which
         * is why the producer's seed fetches **today and yesterday** rather than today alone.
         */
        fun dayKey(epochMillis: Long): String {
            val days = floorDiv(epochMillis, MILLIS_PER_DAY)
            var year = 1970
            var remaining = days
            while (true) {
                val length = if (isLeapYear(year)) 366 else 365
                if (remaining >= length) {
                    remaining -= length
                    year++
                } else if (remaining < 0) {
                    year--
                    remaining += if (isLeapYear(year)) 366 else 365
                } else {
                    break
                }
            }
            val monthLengths = intArrayOf(
                31, if (isLeapYear(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31,
            )
            var month = 0
            while (remaining >= monthLengths[month]) {
                remaining -= monthLengths[month]
                month++
            }
            val day = remaining.toInt() + 1
            return "$year-${two(month + 1)}-${two(day)}"
        }

        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        private fun floorDiv(a: Long, b: Long): Long {
            val q = a / b
            return if (a % b != 0L && (a xor b) < 0) q - 1 else q
        }

        private fun isLeapYear(year: Int): Boolean =
            (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

        private fun two(value: Int): String = if (value < 10) "0$value" else "$value"

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
