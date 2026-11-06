package org.tenshu.sync.proxy

import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncService

/**
 * How many emitted tombstones are still waiting to be pushed.
 *
 * `TombstoneEmitter.emit` does not push — it appends to this durable array and wakes the scheduler,
 * which pushes and then `removeAllFromArray`s what left. So an empty array is the honest answer to
 * *"has my tombstone actually reached the relay?"*, and the only one available locally.
 *
 * ⚠ The caller that needs this is the one destroying its own identity
 * (`ControlSurface.wipeDeviceState`): the push JWT is signed with the A4 key it is about to delete,
 * so a tombstone still queued when the key goes can **never** be pushed — it dies in the queue, and
 * the wipe manufactures exactly the orphaned `Device` row it was meant to prevent (`tech-debt.md`,
 * *"a `Device` row that outlives the identity it describes"*).
 */
fun ExecutionContext.pendingTombstoneCount(): Int {
    val ref = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = context,
            type = DeleteBlock.ID,
            relationId = SyncService.Collections.PENDING_TOMBSTONES,
        ),
        isEntity = false,
        elementSchemaId = DeleteBlock.ID,
    )
    return database.getArrayData(ref)?.toList()?.size ?: 0
}
