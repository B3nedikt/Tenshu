package org.tenshu.sync.proxy

/**
 * The holders a recursive delete removed along with each pending tombstone's own target, keyed by
 * `blockId`, until that tombstone's push has carried them to the relay as its cascade
 * (`content-plane-cr2-b3-wire-spec.md` §3.2).
 *
 * ## ⭐ Persisted, and read back from the store at push time
 *
 * A cascade is written to [store] **before** its block becomes pushable, and `PushTombstone` reads it
 * back by `blockId` when it builds the `CascadedTombstonePush`. So the names live exactly as long as
 * the pending tombstone they belong to, a restart in between loses nothing, and every
 * session's observer and the one drain agree without sharing an instance. Production wires
 * [DatabaseTombstoneCascadeStore].
 *
 * ⚠ A store failure is reported, never thrown: the delete itself must not fail because its cascade
 * could not be journalled. The tombstone is then pushed without its subtree, which leaves the
 * descendants' edges standing at the relay — a leak, never a loss (their own reaps, on every other
 * device, still remove them). Open only so a test can observe the moment a cascade is recorded.
 *
 * A move records no successor here: its new holder names its content with its own push.
 */
open class TombstoneCascades(private val store: TombstoneCascadeStore) {

    /** Records [holderNames] against [blockId]. ⚠ Must run before that block becomes pushable. */
    open fun record(blockId: String, holderNames: List<String>) {
        if (holderNames.isEmpty()) return
        runCatching { store.save(blockId, holderNames) }
            .onFailure { println("TombstoneCascades -> could not persist the cascade of $blockId (${holderNames.size} holders): $it") }
    }

    /** The names recorded against [blockId], or empty. Does not consume them. */
    fun holdersOf(blockId: String): List<String> =
        runCatching { store.load(blockId) }
            .onFailure { println("TombstoneCascades -> could not read the cascade of $blockId: $it") }
            .getOrDefault(emptyList())

    /** Drops the cascades of [blockIds] once their tombstones have been pushed. */
    fun forget(blockIds: Collection<String>) {
        if (blockIds.isEmpty()) return
        runCatching { store.delete(blockIds.toSet()) }
            .onFailure { println("TombstoneCascades -> could not drop ${blockIds.size} persisted cascades: $it") }
    }
}

/**
 * Durable storage for [TombstoneCascades]. Device-local; holds wire names only.
 */
interface TombstoneCascadeStore {
    /** Persists [holderNames] against [blockId], replacing any earlier entry. */
    fun save(blockId: String, holderNames: List<String>)

    /** The cascade persisted against [blockId], or empty. */
    fun load(blockId: String): List<String>

    /** Removes the entries for [blockIds]; absent ones are ignored. */
    fun delete(blockIds: Collection<String>)
}
