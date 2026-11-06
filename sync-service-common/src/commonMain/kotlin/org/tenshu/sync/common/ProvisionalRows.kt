package org.tenshu.sync.common

import org.tenshu.core.entity.DataId

/**
 * Which local rows are still **provisional**: materialised from a relation-slice snapshot (C8,
 * `SliceSnapshotProducer.applySlice`) and not yet reached by the log (`content-plane-cr2-b5-spec.md`
 * §4, `content-plane-cr2-quorumless.md` §10.11).
 *
 * ⚠⚠ Why a move must ask. A snapshot row names its content id **as of the snapshot**. It is harmless
 * while it only renders, because the first real block replaces it (a row with no `SyncMetadata` loses
 * to anything incoming). A **move** of it, though, pushes that possibly stale id under the new path at
 * a fresh HLC and tombstones the old path at a fresh HLC too, so every peer follows the joiner's stale
 * copy over the newer one — and G3 refuses the push only once the stale id is already freed.
 *
 * Implemented by sync (`ProvisionalRowProbe`, served by `SyncObserver`); reached by
 * `:file-service-proxy` through [TombstoneEmitter.provisionalRows], which already reaches every
 * runtime's file ops.
 */
interface ProvisionalRows {

    /**
     * Whether **any** row on this device can be provisional right now. Cheap, and false for nearly
     * every device nearly all the time, so a caller asks this before walking a subtree.
     */
    fun mayExist(): Boolean

    /** Whether the row at [dataId] is provisional. Only meaningful while [mayExist] is true. */
    fun isProvisional(dataId: DataId): Boolean
}
