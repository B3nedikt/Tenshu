package org.tenshu.sync.proxy

import org.tenshu.core.entity.DataId
import org.tenshu.sync.common.ProvisionalRows
import org.tenshu.sync.common.SubtreeReapOrdering
import org.tenshu.sync.common.SyncManager

/**
 * CR-2 B5 §4: a row is **provisional** while this device is still catching up and holds no version of
 * it at all — neither an ingested block (`SyncMetadata`) nor a write of its own (`LocalWriteMetadata`).
 * That is exactly the state `SliceSnapshotProducer.applySlice` leaves a row in, and it ends the moment
 * either record appears: the real block arrives, or the user writes the row themselves.
 *
 * ⚠⚠ **Gated on catch-up, deliberately.** "No version" alone also describes a row written before
 * `LocalWriteMetadata` shipped and never ingested back, and a row whose wire name cannot be computed
 * while the realm is locked. Refusing those for ever would strand them. Snapshots are applied only
 * while `backfillCursor != 0` (`SliceSnapshotReader.bootstrapOnce`), and once the walk completes every
 * real block has been ingested, so no row can still be provisional. Inside the walk the rule
 * over-approximates — a legacy row is refused too — and that is the safe side: the refusal is
 * temporary and says so, while a wrong pass is a peer's newer content overwritten everywhere.
 *
 * ⚠ Outside a realm there is no snapshot and no peer to mislead, so nothing is provisional.
 */
class ProvisionalRowProbe(
    private val syncManager: SyncManager,
    private val ordering: SubtreeReapOrdering,
) : ProvisionalRows {

    override fun mayExist(): Boolean =
        syncManager.residentOwnerId != null && syncManager.backfillCursor != 0L

    override fun isProvisional(dataId: DataId): Boolean =
        mayExist() && ordering.versionOf(dataId) == null
}
