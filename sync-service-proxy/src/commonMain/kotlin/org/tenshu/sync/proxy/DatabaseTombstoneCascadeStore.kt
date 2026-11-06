package org.tenshu.sync.proxy

import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.PendingTombstoneCascade
import org.tenshu.sync.common.holderNameList

/**
 * [TombstoneCascadeStore] over the local store: one [PendingTombstoneCascade] row per pending
 * tombstone, in [SyncService.Collections.PENDING_TOMBSTONE_CASCADES] (CR-2 B3b, B2 audit W5).
 *
 * ⚠ Written through the `Database` array API only, never `ExecutionContext.saveData`, so no observer
 * sees it and nothing about it is ever synced.
 */
class DatabaseTombstoneCascadeStore(private val executionContext: ExecutionContext) : TombstoneCascadeStore {

    override fun save(blockId: String, holderNames: List<String>) {
        // ⚠ `addToArray` appends without deduplicating, so a re-record of the same block first drops
        // the old membership. A block id is fresh per tombstone, so in practice this is a no-op.
        executionContext.database.removeFromArray(ref(), rowId(blockId))
        executionContext.database.addToArray(
            ref(),
            PendingTombstoneCascade.create(executionContext.context, blockId, holderNames),
        )
    }

    override fun load(blockId: String): List<String> =
        (executionContext.database.getData(rowId(blockId)) as? PendingTombstoneCascade)?.holderNameList().orEmpty()

    override fun delete(blockIds: Collection<String>) {
        for (blockId in blockIds) {
            val id = rowId(blockId)
            executionContext.database.removeFromArray(ref(), id)
            // ⚠⚠ The row too, not just the membership — the leak `PendingContentEmitRetarget` names:
            // an orphaned row reads as nothing pending and accumulates silently.
            executionContext.database.deleteData(id)
        }
    }

    private fun rowId(blockId: String): DataId = DataId.StringDataId.create(
        context = executionContext.context,
        type = PendingTombstoneCascade.ID,
        keyValue = blockId,
    )

    private fun ref() = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = PendingTombstoneCascade.ID,
            relationId = SyncService.Collections.PENDING_TOMBSTONE_CASCADES,
        ),
        isEntity = false,
        elementSchemaId = PendingTombstoneCascade.ID,
    )
}
