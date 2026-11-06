package org.tenshu.sync.common

import org.tenshu.core.entity.DataId
import org.tenshu.core.time.HybridLogicalClock

/**
 * What a subtree reaper needs from sync to reap **consistently** (`content-plane-cr2-quorumless.md`
 * §10.12 Q4, revised and accepted 2026-10-02): one ordering point per deleted folder — its tombstone's
 * HLC T — the same on every device and at the relay.
 *
 * The rule a reaper applies with it:
 *  - a descendant whose version on this device is **≤ T** is reaped, and [recordReap] is called for it
 *    **before** its delete, so this device refuses any block for it at or below T from then on,
 *    restart included;
 *  - a descendant **newer than T** is kept. It is a live row everywhere; nothing is removed silently.
 *  - a descendant with no version at all (a placeholder) counts as ≤ T.
 *
 * Implemented by sync (`TombstoneRegistry`); reached by `:file-service-proxy` through
 * [SubtreeReapOrderingHolder], since file and sync initialise in either order.
 */
interface SubtreeReapOrdering {

    /**
     * The ordering point at which [dataId] was deleted on this device: its tombstone's HLC, or the T
     * it was reaped at. Null when neither is known — the reaper then keeps today's behaviour.
     */
    fun deletionPointOf(dataId: DataId): HybridLogicalClock?

    /**
     * This device's last known version of [dataId]: the later of the last block it ingested for it
     * and its own last local write. Null for a row with neither (a placeholder).
     */
    fun versionOf(dataId: DataId): HybridLogicalClock?

    /** Records that [dataId] is reaped at [point] (T). ⚠ Must run before the entity's delete. */
    fun recordReap(dataId: DataId, point: HybridLogicalClock)
}

/** Late-bound [SubtreeReapOrdering], like [TombstoneEmitterHolder]. Null ⇒ reap as before. */
class SubtreeReapOrderingHolder {
    var ordering: SubtreeReapOrdering? = null
}
