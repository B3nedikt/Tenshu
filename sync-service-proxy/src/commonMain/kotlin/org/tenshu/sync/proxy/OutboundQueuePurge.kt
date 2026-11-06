package org.tenshu.sync.proxy

import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncDelta
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.PendingContentEmit
import org.tenshu.sync.common.RekeyMigrationMarker

/**
 * Drop everything this device has staged to *send*, because it is leaving the realm those payloads
 * are addressed to (`ios-identity-reinstall-gate.md` §4, RG-1).
 *
 * ## Why a discard needs this
 *
 * `wipeDeviceState` clears the custody bucket and **deliberately never touches the object store**.
 * That asymmetry is not a rough edge, it is a live incident: the identity went, the *queues stayed*,
 * and a queued `Device` tombstone executed weeks of state later against a device that had since
 * re-paired — the 2026-08-18 stale-retirement bug in `tech-debt.md`. Anything authored by an
 * identity you are discarding is addressed to a realm this device is leaving, so it can only ever
 * be wrong when it finally lands.
 *
 * ## ⚠⚠ Removing from the array is not enough
 *
 * `addToArray` persisted each element as its own row; `removeFromArray` only rewrites the list's
 * refs. Doing just that leaves the row immortal — **99.87 % of measured `sync/block` rows were
 * exactly this garbage** (`core-local-store-orphan-gc.md` G1). So each element goes through
 * [deleteRetiredBlock], which also collects the child composites keyed off it, and both halves run
 * in one transaction so a crash between them cannot strand a row that is neither queued nor
 * collectable.
 *
 * ## What is deliberately NOT purged
 *
 * - `PENDING_REKEY_MIGRATIONS` — a local repair journal, not an outbound queue. A marker means a
 *   `DataId` migration was interrupted mid-flight; dropping it while keeping the store it describes
 *   leaves that store half-migrated with nothing left to finish the job.
 *
 * ⚠ Its phase-2 twin `PENDING_REKEY_TOMBSTONES` falls on the *other* side of the line and is
 * purged below: by then the local retirement is done, and all the marker still holds is a tombstone
 * owed to the realm being left. Carried across a discard it is emitted into the *next* realm, where
 * the same path resolves to a different entity — the 2026-08-18 stale-retirement shape exactly.
 *
 * ⚠ The distinction is "addressed outward" vs "describes local state", not "is a queue". The one
 * above is a queue.
 */
fun ExecutionContext.purgeOutboundQueues(residentOwnerId: String? = null): Int {
    var purged = 0
    // ⚠⚠ The rekey retry journal is **owner-scoped** (`…/<ownerId>`, see `DataIdMigrator`), so the
    // bare relation names an array nothing ever writes. Purging that one silently purged nothing:
    // forget-and-rejoin the same realm then replays a stale marker on unlock, and if the old path
    // has been recreated on a peer meanwhile, the replay deletes the peer's new file. ⇒ the caller
    // passes the realm it is leaving; with none, there is no owner-scoped array to clear.
    val queues = OUTBOUND_QUEUES + listOfNotNull(
        residentOwnerId?.let {
            RekeyMigrationMarker.ID to
                SyncService.Collections.PENDING_REKEY_TOMBSTONES + "/" + it
        }
    )
    queues.forEach { (elementTypeId, relationId) ->
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = context,
                type = elementTypeId,
                relationId = relationId,
            ),
            isEntity = false,
            elementSchemaId = elementTypeId,
        )
        val elements = database.getArrayData(ref)?.toList().orEmpty()
        if (elements.isEmpty()) return@forEach

        elements.forEach { element ->
            val dataId = getDataId(element) ?: return@forEach
            database.transaction {
                database.removeFromArray(ref, dataId)
                deleteRetiredBlock(element)
            }
            purged++
        }
    }
    return purged
}

/**
 * The queues a discard drops, as (element type, relation) pairs.
 */
internal val OUTBOUND_QUEUES: List<Pair<String, String>> = listOf(
    SyncBlock.ID to SyncService.Collections.PENDING_BLOCKS,
    DeleteBlock.ID to SyncService.Collections.PENDING_TOMBSTONES,
    // CR-2 B3d: reaps owed to the relay of the realm being left — addressed outward, like a tombstone.
    org.tenshu.sync.common.PendingReapRemoval.ID to SyncService.Collections.PENDING_REAP_REMOVALS,
    SyncDelta.ID to SyncService.Collections.PENDING_LANE_DELTAS,
    PendingContentEmit.ID to SyncService.Collections.PENDING_CONTENT_EMITS,
)
