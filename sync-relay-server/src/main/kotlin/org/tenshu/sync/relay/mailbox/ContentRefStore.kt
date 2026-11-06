package org.tenshu.sync.relay.mailbox

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ContentRefRegistry] — the dev-mode / test twin of `SqliteContentRefStore`.
 *
 * ⚠ Same shape as [ContentEdgeStore]: correct but not durable, so a restart forgets every
 * declaration. On a durable relay that would make every blob look orphaned, which is why the
 * production path is the SQLite one and an enumeration pass must refuse to run against this.
 */
class ContentRefStore : ContentRefRegistry {

    private data class Key(val ownerId: String, val contentId: String)

    private val refs = ConcurrentHashMap<Key, Set<String>>()

    override fun declareRefs(ownerId: String, contentId: String, refs: Collection<String>) {
        if (ownerId.isBlank() || contentId.isBlank()) return
        val set = refs.filterTo(mutableSetOf()) { it.isNotBlank() }
        // ⚠ Whole-set replace in one map write — the in-memory shape of the atomicity the SQLite
        // twin gets from a transaction. Mutating a stored set in place would expose a partial one.
        if (set.isEmpty()) this.refs.remove(Key(ownerId, contentId)) else this.refs[Key(ownerId, contentId)] = set
    }

    override fun refsOf(ownerId: String, contentId: String): Set<String> =
        refs[Key(ownerId, contentId)] ?: emptySet()

    override fun declaredContentIds(ownerId: String): Set<String> =
        refs.keys.filterTo(mutableSetOf()) { it.ownerId == ownerId }.mapTo(mutableSetOf()) { it.contentId }

    override fun describedOwnerIds(): Set<String> = refs.keys.mapTo(mutableSetOf()) { it.ownerId }

    override fun refsByContent(ownerId: String, contentIds: Collection<String>): Map<String, Set<String>> {
        if (contentIds.isEmpty()) return emptyMap()
        return contentIds.distinct()
            .mapNotNull { id -> refs[Key(ownerId, id)]?.let { id to it } }
            .toMap()
    }

    override fun referencedRefs(ownerId: String, contentIds: Collection<String>): Set<String> =
        contentIds.flatMapTo(mutableSetOf()) { refsOf(ownerId, it) }

    override fun forgetContent(ownerId: String, contentId: String): Int =
        refs.remove(Key(ownerId, contentId))?.size ?: 0

    /** Account closure (F12). Counts ref rows, matching the SQLite twin. */
    override fun eraseOwner(ownerId: String): Int {
        requireErasableOwner(ownerId)
        return refs.keys.filter { it.ownerId == ownerId }.sumOf { refs.remove(it)?.size ?: 0 }
    }
}
