package org.tenshu.sync.common

import org.tenshu.core.common.util.sha256
import org.tenshu.core.common.util.toHexLower

/**
 * One **slice** of a relation, frozen at a point in the relay log — the artifact that lets a
 * joining device be *usable before it is complete* (`sync-device-catch-up.md` §5.7, C8).
 *
 * A joiner fetches the slices its first screen needs (a folder's children; today's moments),
 * renders from them immediately, sets its forward cursor to [asOfSeq] so replay covers only
 * the delta, and backfills the rest behind that — or never.
 *
 * ## ⚠⚠ Envelope typed, entries opaque (C8a)
 *
 * The split is the whole design. **This envelope is platform-owned** and parseable without
 * knowing any service: `version`, [asOfSeq], [relationId], [sliceKey], [entryCount].
 * **[entries] is opaque** — the owning service seals and parses its own projection, carrying
 * its own version discriminator inside those bytes.
 *
 * Why not a typed per-service list: `genericData` cannot hold an array, and a `TList` member
 * needs a `DataIdProvider` — which snapshot rows have nothing to key on (`SchemaFenceStatus`,
 * `RealmSchemaEpoch` and `TombstoneTypeCounts` all work around exactly that). ⭐ And the
 * decisive reason: registering each projection as a composite would buy baseline pinning,
 * migrations and epoch fences — machinery built to protect **irreplaceable history**. A slice
 * snapshot is *regenerable derived state*; a missing or stale one already degrades to an
 * ordinary relation read. Paying the frozen-set tax on something you can discard and rebuild
 * is backwards.
 *
 * ⭐ **A device that cannot parse a service's entries can still read this envelope**, so it
 * learns the slice exists and what it reflects — enough to skip it deliberately rather than
 * mistake it for absent.
 *
 * ## ⚠ Not a wire composite, deliberately
 *
 * Positional composites cannot gain a field (HR #9), which is exactly the constraint this
 * artifact must not inherit — so it is a content-plane blob with its own codec, and it
 * **starts** versioned ([VERSION]) so it can grow later without inheriting that problem.
 */
class RelationSliceSnapshot(
    /**
     * ⚠⚠ **The field that makes this a snapshot rather than a cache.** The relay sequence this
     * slice reflects: a bootstrapping device sets its forward cursor here, so the replay that
     * follows covers only the delta rather than all of history. Without it a joiner would hold
     * a tree and no idea which part of the log it had effectively already applied.
     */
    val asOfSeq: Long,
    /** WHICH relation — e.g. `tenshu/file/folder/files`. */
    val relationId: String,
    /**
     * WHICH slice of it. `Folder.FILES` uses the folder's entity id; a time-bucketed relation
     * uses e.g. `"2026-08"`; an unsliced relation uses `""`.
     */
    val sliceKey: String,
    /** Number of entries [entries] encodes — readable without parsing them. */
    val entryCount: Int,
    /** The owning service's sealed projection. Opaque here, by design. */
    val entries: ByteArray,
) {

    companion object {
        /**
         * Envelope version. ⚠ Bump only for an envelope reshape — a service changing its own
         * projection versions *inside* [entries] and must not touch this.
         */
        const val VERSION = 1

        /**
         * The largest [encode]d slice the writer publishes: 384 KiB (`sync-device-catch-up.md`
         * §11.4.4). A slice over it is **withdrawn**: its address gets [withdrawn] instead, so a joiner
         * applies nothing for it and its first screen fills as replay delivers rows.
         *
         * ⚠⚠ Withdraw, never truncate. A slice claims to be the whole of its relation's slice; cutting
         * it to fit would render a folder or a day with entries silently missing, which is worse
         * than no slice.
         *
         * ⚠ A slice is a content-plane blob, never a store row, so `ROW_MAX` (512 KiB, the iOS
         * shared store) is not what binds it. The budget sits under it anyway, with room for the
         * seal and any row envelope, so that persisting a fetched slice locally would still fit
         * in one row (`ios-shared-store-sqlite.md` §4.2.1). The relay's own cap, 8 MiB, is far
         * above it.
         */
        const val MAX_ENCODED_BYTES = 384 * 1024

        /**
         * The envelope version of a **withdrawn** slice: reserved, never a real version.
         *
         * ⚠⚠ Why the writer overwrites rather than just skips (§11.4.4). A slice that grows over
         * [MAX_ENCODED_BYTES] after an earlier pass sealed it would otherwise leave that earlier blob
         * at its address for good, and a joiner would seed from a state that keeps getting older.
         * Every reader ever shipped refuses an envelope newer than its own [VERSION] (see [decode]),
         * so this marker is declined everywhere and the joiner falls back to the relation read.
         */
        const val WITHDRAWN_VERSION = Int.MAX_VALUE

        /** The bytes sealed at a withdrawn slice's address: the version alone, nothing else. */
        fun withdrawn(): ByteArray {
            val out = ArrayList<Byte>(8)
            out.putVarint(WITHDRAWN_VERSION.toLong())
            return out.toByteArray()
        }

        /**
         * The content-plane address of the **current** slice for [realmId].
         *
         * ⚠ Must satisfy the relay's `ContentRouting.REF_PATTERN` (`^blob-[0-9a-f]{64}$`,
         * enforced on PUT **and** GET) — copying the shape of `manifestContentRef`. A ref
         * outside it is 400-rejected, and only `:system-test-utils` catches that.
         *
         * ⚠⚠ **`asOfSeq` is deliberately NOT in the address, and this is load-bearing.** A
         * slice is a *latest-state* pointer, not an immutable historical artifact, so a re-seal
         * must land on the **same** ref and overwrite. Including `asOfSeq` would be wrong twice
         * over:
         *
         *  1. **it cascades.** A folder entry points at its child folder's slice; if the address
         *     moved with the child's `asOfSeq`, re-sealing one leaf would force every ancestor
         *     to be re-sealed to update the link, all the way to root — turning a local change
         *     into a tree-wide rewrite;
         *  2. **it orphans.** Every re-seal would strand its predecessor as garbage, needing a
         *     GC pass that does not exist.
         *
         * With `asOfSeq` out of the address, a reader derives "the current slice for folder X"
         * from coordinates it already knows, and learns *what it got* from the decoded envelope.
         * ⚠ This is the opposite call from C7's per-property `contentId` — there, two
         * **different** artifacts would collide on one address, which is a bug; here one
         * artifact is being *replaced*, which is the intent.
         *
         * Derived from ids the relay already cannot invert (a realm id and an entity id), and
         * hashed so the relay learns no path or folder name from the address itself.
         */
        fun ref(
            realmId: String,
            relationId: String,
            sliceKey: String,
        ): String = "blob-" + sha256(
            "snapshot:$realmId:$relationId:$sliceKey".encodeToByteArray()
        ).toHexLower()

        /**
         * Encode to the bytes that get sealed onto the content plane.
         *
         * Format (all lengths varint, mirroring [BlockPayloadEnvelope]'s idiom):
         * ```
         * varint(VERSION) varint(asOfSeq)
         * str(relationId) str(sliceKey)
         * varint(entryCount) varint(entries.size) entries
         * str := varint(byteLen) utf8Bytes
         * ```
         */
        fun encode(snapshot: RelationSliceSnapshot): ByteArray {
            val out = ArrayList<Byte>(64 + snapshot.entries.size)
            out.putVarint(VERSION.toLong())
            out.putVarint(snapshot.asOfSeq)
            out.putStr(snapshot.relationId)
            out.putStr(snapshot.sliceKey)
            out.putVarint(snapshot.entryCount.toLong())
            out.putVarint(snapshot.entries.size.toLong())
            snapshot.entries.forEach { out.add(it) }
            return out.toByteArray()
        }

        /**
         * Decode [bytes]. Returns `null` for anything unreadable — a truncated blob, or an
         * envelope [VERSION] newer than this build understands.
         *
         * ⚠ Null is the *correct* answer rather than a throw: a snapshot is an optimisation,
         * and every reader must already fall back to an ordinary relation read (§5.7.5). A
         * device that cannot read a slice should be slower, never broken.
         */
        fun decode(bytes: ByteArray): RelationSliceSnapshot? {
            val cursor = Cursor(bytes)
            val version = cursor.varint()?.toInt() ?: return null
            if (version > VERSION) return null
            val asOfSeq = cursor.varint() ?: return null
            val relationId = cursor.str() ?: return null
            val sliceKey = cursor.str() ?: return null
            val entryCount = cursor.varint()?.toInt() ?: return null
            val entriesLength = cursor.varint()?.toInt() ?: return null
            val entries = cursor.take(entriesLength) ?: return null
            return RelationSliceSnapshot(
                asOfSeq = asOfSeq,
                relationId = relationId,
                sliceKey = sliceKey,
                entryCount = entryCount,
                entries = entries,
            )
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

            fun take(length: Int): ByteArray? {
                if (length < 0 || index + length > bytes.size) return null
                val slice = bytes.copyOfRange(index, index + length)
                index += length
                return slice
            }

            fun str(): String? {
                val length = varint()?.toInt() ?: return null
                return take(length)?.decodeToString()
            }
        }
    }
}
