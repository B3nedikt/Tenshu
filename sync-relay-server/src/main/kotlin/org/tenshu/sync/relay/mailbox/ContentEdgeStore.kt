package org.tenshu.sync.relay.mailbox

import org.tenshu.core.common.util.HlcStamp

/**
 * In-memory [ContentEdgeRegistry] — the dev-mode / test twin of `SqliteContentEdgeStore`, and the
 * fallback when no `DATABASE_URL` is configured.
 *
 * ⚠ Correct but not durable, so a restart forgets every register and watermark. On a durable relay
 * that would read as "nothing references anything" — which is why the production path is the SQLite
 * one and `durable:false` is a health signal, not a detail.
 *
 * ⚠ One lock for everything, and [atomically] holds it, so a push's block append and its edge
 * writes are not interleaved with another request's. It cannot roll back the mailbox; only the
 * SQLite pair is one transaction.
 */
class ContentEdgeStore(
    /** The relay's clock for B5r's `since` stamps; a test passes its own. */
    private val clock: () -> Long = System::currentTimeMillis,
) : ContentEdgeRegistry {

    private data class Key(val ownerId: String, val holderHash: String, val propertyId: String)
    private data class HolderKey(val ownerId: String, val holderHash: String)
    private data class ContentKey(val ownerId: String, val contentId: String)

    private val lock = Any()
    private val registers = HashMap<Key, ContentEdgeRegistry.Register>()
    private val watermarks = HashMap<HolderKey, HlcStamp>()

    /** CR-2 B5r's clock, the twin of `content_unreferenced`. */
    private val unreferenced = HashMap<ContentKey, Long>()

    override fun <T> atomically(block: () -> T): T = synchronized(lock) { block() }

    override fun writeEdges(
        ownerId: String,
        holderHash: String,
        stamp: HlcStamp,
        edges: List<ContentEdgeRegistry.EdgeWrite>,
    ): Int = synchronized(lock) {
        if (ownerId.isBlank() || holderHash.isBlank()) return 0
        val watermark = watermarks[HolderKey(ownerId, holderHash)]
        var changed = 0
        val lost = mutableSetOf<String>()
        for (edge in edges) {
            if (edge.propertyId.isBlank()) continue
            val key = Key(ownerId, holderHash, edge.propertyId)
            val existing = registers[key]
            if (!edgeWriteApplies(existing?.stamp, watermark, stamp)) continue
            val named = edge.contentId?.takeIf { it.isNotBlank() }
            registers[key] = ContentEdgeRegistry.Register(named, stamp)
            existing?.contentId?.takeIf { it != named }?.let { lost += it }
            if (named != null) unreferenced.remove(ContentKey(ownerId, named))
            changed++
        }
        stampOneToZero(ownerId, lost)
        changed
    }

    /** B5r: each of [contentIds] lost a live edge in this lock section; those left with none start the clock. */
    private fun stampOneToZero(ownerId: String, contentIds: Collection<String>) {
        if (contentIds.isEmpty()) return
        val now = clock()
        for (contentId in contentIds) {
            if (!hasLiveEdge(ownerId, contentId)) unreferenced.putIfAbsent(ContentKey(ownerId, contentId), now)
        }
    }

    private fun hasLiveEdge(ownerId: String, contentId: String): Boolean =
        registers.entries.any { it.key.ownerId == ownerId && it.value.contentId == contentId }

    override fun removeHolders(ownerId: String, holderHashes: Collection<String>, at: HlcStamp): Int =
        synchronized(lock) {
            if (ownerId.isBlank()) return 0
            var cleared = 0
            val lost = mutableSetOf<String>()
            for (holderHash in holderHashes.toSet()) {
                if (holderHash.isBlank()) continue
                val holder = HolderKey(ownerId, holderHash)
                watermarks[holder] = maxOf(watermarks[holder] ?: at, at)
                for ((key, register) in registers.entries.filter { it.key.ownerId == ownerId && it.key.holderHash == holderHash }) {
                    if (register.stamp > at) continue
                    if (register.contentId != null) {
                        cleared++
                        lost += register.contentId
                    }
                    registers[key] = ContentEdgeRegistry.Register(contentId = null, stamp = at)
                }
            }
            stampOneToZero(ownerId, lost)
            cleared
        }

    override fun register(ownerId: String, holderHash: String, propertyId: String): ContentEdgeRegistry.Register? =
        synchronized(lock) { registers[Key(ownerId, holderHash, propertyId)] }

    override fun watermark(ownerId: String, holderHash: String): HlcStamp? =
        synchronized(lock) { watermarks[HolderKey(ownerId, holderHash)] }

    override fun referencedContentIds(ownerId: String): Set<String> = synchronized(lock) {
        registers.entries
            .filter { it.key.ownerId == ownerId }
            .mapNotNullTo(mutableSetOf()) { it.value.contentId }
    }

    override fun holdersOf(ownerId: String, contentId: String): Set<String> = synchronized(lock) {
        registers.entries
            .filter { it.key.ownerId == ownerId && it.value.contentId == contentId }
            .mapTo(mutableSetOf()) { it.key.holderHash }
    }

    override fun unreferencedSince(ownerId: String, contentId: String): Long? =
        synchronized(lock) { unreferenced[ContentKey(ownerId, contentId)] }

    override fun unreferencedContents(ownerId: String): Map<String, Long> = synchronized(lock) {
        unreferenced.entries.filter { it.key.ownerId == ownerId }.associate { it.key.contentId to it.value }
    }

    override fun expiredUnreferenced(cutoffMs: Long, limit: Int): List<ContentEdgeRegistry.Unreferenced> =
        synchronized(lock) {
            unreferenced.entries
                .filter { it.value <= cutoffMs }
                .sortedWith(compareBy({ it.value }, { it.key.ownerId }, { it.key.contentId }))
                .take(limit)
                .map { ContentEdgeRegistry.Unreferenced(it.key.ownerId, it.key.contentId, it.value) }
        }

    override fun unreferencedCount(): Int = synchronized(lock) { unreferenced.size }

    override fun clearUnreferenced(ownerId: String, contentId: String): Boolean =
        synchronized(lock) { unreferenced.remove(ContentKey(ownerId, contentId)) != null }

    override fun startClockIfUnreferenced(ownerId: String, contentId: String, nowMs: Long): Boolean =
        synchronized(lock) {
            if (ownerId.isBlank() || contentId.isBlank() || hasLiveEdge(ownerId, contentId)) return false
            unreferenced.putIfAbsent(ContentKey(ownerId, contentId), nowMs) == null
        }

    /** Account closure (F12): registers, watermarks and clock rows — the three SQLite tables' twins. */
    override fun eraseOwner(ownerId: String): Int = synchronized(lock) {
        requireErasableOwner(ownerId)
        val r = registers.keys.filter { it.ownerId == ownerId }.onEach { registers.remove(it) }.size
        val w = watermarks.keys.filter { it.ownerId == ownerId }.onEach { watermarks.remove(it) }.size
        val u = unreferenced.keys.filter { it.ownerId == ownerId }.onEach { unreferenced.remove(it) }.size
        r + w + u
    }
}

/**
 * Rule 1's decision (wire spec §2), shared by both stores so they cannot order differently: a write at
 * [stamp] lands iff the holder was not removed at or above it, and it is strictly newer than the
 * register it would replace (or there is none).
 *
 * ⚠ Strictly greater, so a replayed push at the same stamp is a no-op rather than a rewrite.
 */
internal fun edgeWriteApplies(existing: HlcStamp?, watermark: HlcStamp?, stamp: HlcStamp): Boolean =
    (watermark == null || watermark < stamp) && (existing == null || stamp > existing)
