package org.tenshu.sync.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.sync.common.PendingBlockRevive
import org.tenshu.sync.common.RemovedContents
import org.tenshu.sync.common.RevivedContent
import org.tenshu.sync.common.contentIdSet
import org.tenshu.sync.common.reviveList

/**
 * A refused block's revive, by `blockId` ([PendingBlockRevive]; CR-2 B4 §2.2).
 *
 * ⚠ Stateless over the local store, like [PendingBlockEdgeStore], and written through the `Database`
 * API only, so no observer sees it and nothing about it is synced.
 */
class PendingBlockReviveStore(private val executionContext: ExecutionContext) {

    fun save(blockId: String, revive: List<RevivedContent>) {
        if (revive.isEmpty()) return
        executionContext.database.saveData(PendingBlockRevive.create(executionContext.context, blockId, revive))
    }

    /** The revive recorded against [blockId], or empty. */
    fun reviveOf(blockId: String): List<RevivedContent> =
        (executionContext.database.getData(rowId(blockId)) as? PendingBlockRevive)?.reviveList().orEmpty()

    fun delete(blockId: String) {
        executionContext.database.deleteData(rowId(blockId))
    }

    private fun rowId(blockId: String): DataId = DataId.StringDataId.create(
        context = executionContext.context,
        type = PendingBlockRevive.ID,
        keyValue = blockId,
    )
}

/**
 * The content each removed holder named, by wire name ([RemovedContents]; CR-2 B4 §2.3).
 *
 * ⚠ Stateless over the local store; written through the `Database` API only.
 */
class RemovedContentsStore(private val executionContext: ExecutionContext) {

    /** Adds [contentIds] to [holder]'s entry (a holder removed twice keeps both sets until consumed). */
    fun record(holder: String, contentIds: Set<String>) {
        if (contentIds.isEmpty()) return
        val merged = contentsOf(holder) + contentIds
        executionContext.database.saveData(RemovedContents.create(executionContext.context, holder, merged))
    }

    fun contentsOf(holder: String): Set<String> =
        (executionContext.database.getData(rowId(holder)) as? RemovedContents)?.contentIdSet().orEmpty()

    /** Drops [holder]'s entry once the removal naming it was accepted. Absent rows are ignored. */
    fun consume(holder: String) {
        executionContext.database.deleteData(rowId(holder))
    }

    private fun rowId(holder: String): DataId = DataId.StringDataId.create(
        context = executionContext.context,
        type = RemovedContents.ID,
        keyValue = holder,
    )
}
