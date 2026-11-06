package org.tenshu.core.common.proxy.db.sqlite

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue

/**
 * ⚠⚠ **A relation array kept as a chain of segment rows** (`docs/proposals/ios-shared-store-sqlite.md`
 * Appendix B.19, option (ii) of §4.2.1's "And relation arrays", decided 2026-10-30).
 *
 * One logical `TList` row (`TList.ID`, every `LongValue` a member) is stored as:
 * - **the head**, at the list's own hash, type `tenshu/core/list`: the **first** segment's members
 *   in its fields, and, only when more segments follow, the hash of the next one under [NEXT_KEY];
 * - **segment rows**, type [SEGMENT_TYPE], each holding the next run of members, its [HEAD_KEY]
 *   (the list it belongs to) and its own [NEXT_KEY] link, or none at the end.
 *
 * A head with no link is exactly the single row every other store writes, byte for byte, so a
 * list of at most one segment is unchanged and a legacy row needs no conversion to be read
 * (B.19 (331)). Every row stays [org.tenshu.core.common.proxy.db.DbEntryCodec] bytes, so the
 * migrator's sweep, the walk and the snapshot read them as rows like any other.
 *
 * Nothing above `ObjectStore` sees any of this: [SharedReplicaStore] assembles the chain on read
 * and plans it on write, and the reserved keys never leave it.
 */
internal object ReplicaListSegments {

    /**
     * ⚠⚠ **The stamp that keeps an older build out** (B.19.9 (343)): a pseudo-service this build
     * declares in its [ReplicaBuild], so it is recorded in `schema_service_versions` like a
     * service. A build that predates segments does not declare it, and the open judges a store
     * that records a service it does not know as **above** it: `Skew`, the update surface, never a
     * read that sees one segment as the whole list and a write that drops the rest.
     * Not a schema service: no `DbEntry`, registry or peer ever names it.
     */
    const val FORMAT_SERVICE = "tenshu/store/list-segments"
    const val FORMAT_VERSION = 1

    /** The type of a segment row. Not a schema type: no service, step or caller ever names it. */
    const val SEGMENT_TYPE = "tenshu/store/list-segment"

    /** The next segment's hash. A NUL-led key, which no schema property id can be. */
    const val NEXT_KEY = "\u0000next"

    /** On a segment: the hash of the list it belongs to. */
    const val HEAD_KEY = "\u0000head"

    /**
     * The longest chain a read follows before it calls the list damaged: a guard against a cycle
     * a bug could make, far above any real list (at 8,000 members a segment, 2³⁰ members).
     */
    const val MAX_SEGMENTS = 1 shl 17

    /** A plain member array: the only shape this store segments. Anything else stays one row. */
    fun isPlainList(entry: DbEntry): Boolean =
        entry.type == TList.ID && entry.properties.isEmpty() && entry.fields.all { it is DbValue.LongValue }

    /** True when [entry] uses a name this store reserves: a caller may never write one. */
    fun usesReservedNames(entry: DbEntry): Boolean =
        entry.type == SEGMENT_TYPE || entry.properties.keys.any { it.startsWith('\u0000') }

    /** The link of a stored head or segment, or null at the end of its chain. */
    fun nextOf(entry: DbEntry): Long? {
        val link = entry.properties[NEXT_KEY] ?: return null
        return when (link) {
            is DbValue.LongValue -> link.value
            else -> throw ListSegmentsDamaged(entry.dataIdHash, "its link is not a hash")
        }
    }

    /** A head with a link: the first row of a list of more than one segment. */
    fun isLinkedHead(entry: DbEntry): Boolean = entry.type == TList.ID && entry.properties.containsKey(NEXT_KEY)

    /** The head a stored segment names. */
    fun headOf(segment: DbEntry): Long? {
        val head = segment.properties[HEAD_KEY] ?: return null
        return when (head) {
            is DbValue.LongValue -> head.value
            else -> null
        }
    }

    fun headEntry(head: Long, members: List<DbValue>, next: Long?): DbEntry =
        DbEntry(TList.ID, head, members, if (next == null) emptyMap() else mapOf(NEXT_KEY to DbValue.LongValue(next)))

    fun segmentEntry(hash: Long, head: Long, members: List<DbValue>, next: Long?): DbEntry {
        val properties = LinkedHashMap<String, DbValue>(2)
        properties[HEAD_KEY] = DbValue.LongValue(head)
        if (next != null) properties[NEXT_KEY] = DbValue.LongValue(next)
        return DbEntry(SEGMENT_TYPE, hash, members, properties)
    }

    /**
     * The `i`th candidate hash for a new segment of [head] (from 1): SplitMix64's finaliser over the
     * head and the counter, so candidates of one list never repeat and are spread over the whole
     * key space. A candidate already taken by any row is skipped by the caller (B.19 (333)).
     */
    fun candidate(head: Long, i: Int): Long {
        var z = head + GOLDEN * i.toLong()
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private const val GOLDEN = -0x61c8864680b583ebL

    /** One stored row of a chain, as the planner needs it. [payloadBytes]: the row's payload as the budget counts it. */
    class Stored(val hash: Long, val members: List<DbValue>, val next: Long?, val payloadBytes: Int) {
        val size: Int get() = members.size
    }

    /** What a write of one list does to the rows, in the order the statements run. */
    sealed class Op {
        abstract val hash: Long

        /** The row at [hash] becomes [entry]; [oldPayloadBytes] is the row it replaces, or -1 for none. */
        class Write(val entry: DbEntry, val oldPayloadBytes: Int) : Op() {
            override val hash: Long get() = entry.dataIdHash
        }

        class Delete(override val hash: Long, val oldPayloadBytes: Int) : Op()
    }

    /**
     * ⚠⚠ **The diff** (B.19 (334)–(337)): the rows that must change for the list at [head], stored as
     * [old] (empty when there is no row; `old[0]` is the head), to hold [members], with at most
     * [perSegment] members a segment. [fresh] hands out a hash for a new segment.
     *
     * 1. **Prefix and suffix.** Whole old segments are kept from the front while each is still
     *    exactly the next run of [members], and from the back while each is exactly the run
     *    before the kept tail (never the head: it must stay first). A segment over [perSegment]
     *    members (a legacy row) or with none is never kept.
     * 2. **The tail reopens.** When nothing is kept behind the change and the last kept segment
     *    has room, it rejoins the change: an append rewrites the last segment in place instead
     *    of starting a new one.
     * 3. **The middle is laid out again**: its members split evenly into `ceil(n / perSegment)`
     *    segments (at least one when it holds the head). ⚠ **A middle of one small segment (under
     *    a quarter) takes the kept segment after it in**, once, so every segment but the last
     *    keeps at least a quarter of [perSegment] and a list cannot fragment.
     * 4. **Hashes are reused in order**: the head first, then the middle's old segments; new ones
     *    come from [fresh], and old ones left over are deleted. The last kept segment before the
     *    middle is rewritten only if its link must change; a reused segment whose members and
     *    link are unchanged is not written.
     *
     * So an append writes one segment (two when the last is full: its link and a new one), a
     * removal or an insertion one or two, and only a change spread over the list writes more.
     */
    fun plan(head: Long, old: List<Stored>, members: List<DbValue>, perSegment: Int, fresh: () -> Long): List<Op> {
        require(perSegment >= 4) { "a segment holds at least four members" }
        require(old.isEmpty() || old[0].hash == head) { "the chain must start at its head" }
        val k = old.size
        val n = members.size
        fun keepable(segment: Stored) = segment.size in 1..perSegment
        fun matchesAt(at: Int, segment: Stored): Boolean {
            if (at < 0 || at + segment.size > n) return false
            for (i in 0 until segment.size) if (members[at + i] != segment.members[i]) return false
            return true
        }

        // 1. The kept prefix and suffix.
        var p = 0
        var off = 0
        while (p < k && keepable(old[p]) && matchesAt(off, old[p])) {
            off += old[p].size
            p++
        }
        var q = k
        var end = n
        while (q - 1 >= maxOf(p, 1) && keepable(old[q - 1]) && end - old[q - 1].size >= off && matchesAt(end - old[q - 1].size, old[q - 1])) {
            end -= old[q - 1].size
            q--
        }
        if (k > 0 && p == k && off == n) return emptyList() // the same list
        // 2. The tail reopens: nothing kept behind the change, and room in the last kept segment.
        if (q == k && p > 0 && old[p - 1].size < perSegment) {
            p--
            off -= old[p].size
        }
        // 3. The middle, and the small-segment rule.
        fun segmentsFor(length: Int) = maxOf((length + perSegment - 1) / perSegment, if (p == 0) 1 else 0)
        var c = segmentsFor(end - off)
        while (c == 1 && end - off < perSegment / 4 && q < k) {
            end += old[q].size
            q++
            c = segmentsFor(end - off)
        }
        // 4. Hashes and links.
        val reusable = old.subList(p, q)
        val hashes = LongArray(c) { t ->
            when {
                t < reusable.size -> reusable[t].hash
                p == 0 && t == 0 -> head // a list with no row yet
                else -> fresh()
            }
        }
        val after = if (q < k) old[q].hash else null
        val ops = ArrayList<Op>(c + reusable.size + 1)
        if (p > 0) {
            val last = old[p - 1]
            val first = if (c > 0) hashes[0] else after
            if (last.next != first) ops += Op.Write(entryFor(head, last.hash, last.members, first), last.payloadBytes)
        }
        val length = end - off
        var at = off
        for (t in 0 until c) {
            val size = length / c + if (t < length % c) 1 else 0
            val slice = members.subList(at, at + size)
            at += size
            val next = if (t + 1 < c) hashes[t + 1] else after
            val reused = reusable.getOrNull(t)
            if (reused != null && reused.next == next && reused.members == slice) continue
            ops += Op.Write(entryFor(head, hashes[t], ArrayList(slice), next), reused?.payloadBytes ?: -1)
        }
        for (t in c until reusable.size) ops += Op.Delete(reusable[t].hash, reusable[t].payloadBytes)
        return ops
    }

    private fun entryFor(head: Long, hash: Long, members: List<DbValue>, next: Long?): DbEntry =
        if (hash == head) headEntry(head, members, next) else segmentEntry(hash, head, members, next)
}

/**
 * A list's chain does not hold together: a link to a missing row, a row that is not a segment of
 * this list, a cycle. ⚠ Like a row that does not decode, it is **not** the corruption path (SQLite
 * read every byte without complaint, so no second reading could confirm anything) and it fences
 * nothing: the read or write of that one list fails, and every other row is served (B.19 (339)).
 * Never expected: every write of a chain is one transaction.
 */
class ListSegmentsDamaged(val head: Long, why: String) : IllegalStateException("[replica] the list at $head is damaged: $why")

/**
 * A write of one list would need more of the `-wal` than a transaction can have even on a log just
 * reset (B.19 (338)), so it could never commit: refused before its first statement. Only a change
 * spread over more of a list than [capacityPages] allows (B.19.4), never an append, an insertion or
 * a removal of one member.
 *
 * ⚠ **It fences nothing** (B.19.9 (345)): no statement of the list was run, and the same write
 * would be refused on every attempt, so a fence would only take the whole store down with it, at
 * every foreground. The access goes on as for any exception of its caller (D4).
 */
class ListRewriteTooLarge(val head: Long, val segments: Int, val pages: Int, val capacityPages: Int) :
    IllegalStateException(
        "[replica] a rewrite of the list at $head ($segments segments stored) needs $pages pages of the -wal, " +
            "past the $capacityPages a transaction has on a log just reset",
    )

/** [build] as this store's code declares it: with the list-segment format's stamp (B.19.9 (343)). */
fun ReplicaBuild.withListSegments(): ReplicaBuild = copy(
    services = services + (ReplicaListSegments.FORMAT_SERVICE to ReplicaListSegments.FORMAT_VERSION),
    serviceFloors = serviceFloors + (ReplicaListSegments.FORMAT_SERVICE to ReplicaListSegments.FORMAT_VERSION),
)

/** Does [build] declare the list-segment format, so that a store judged compatible with it holds its stamp? */
internal fun ReplicaBuild.declaresListSegments(): Boolean =
    services[ReplicaListSegments.FORMAT_SERVICE] == ReplicaListSegments.FORMAT_VERSION

/**
 * A row was written at a hash a list segment holds (B.19 (333)): about one in 2⁶⁴ per pair, and
 * refused rather than overwriting the segment, which would tear its list. Rule (a).
 */
class SegmentHashCollision(val hash: Long) :
    ReplicaAccessFailed(ReplicaFence.COMMIT_FAILED, "a row was written at $hash, which holds a list segment")

/**
 * A migration step reads `tenshu/core/list` rows and met a list of several segments (B.19 (340)):
 * it would see the first segment as the whole list. Counted like any row a step fails on. No
 * step on any platform reads list rows today; one that does needs the chain assembled first.
 */
class SegmentedListNotMigratable(val head: Long) :
    IllegalStateException("the list at $head is stored in segments, and a migration step cannot read it whole")
