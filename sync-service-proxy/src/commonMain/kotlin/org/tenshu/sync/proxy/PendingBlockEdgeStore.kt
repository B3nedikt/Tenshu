package org.tenshu.sync.proxy

import org.tenshu.core.common.content.ContentEdge
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.sync.common.PendingBlockEdges
import org.tenshu.sync.common.clearedContentIds
import org.tenshu.sync.common.edgeList

/**
 * The edges each queued block owes its push, by `blockId` ([PendingBlockEdges]; CR-2 B3d,
 * `content-plane-cr2-b3-wire-spec.md` §5).
 *
 * ⚠⚠ Written by the emit **before** the block is queued, from the payload in hand, and read back by
 * the push. Stateless over the local store, so every session's observer, the scheduler's drains and
 * `PushBlock` see the same rows without sharing an instance.
 *
 * ⚠ Written through the `Database` API only, never `ExecutionContext.saveData`, so no observer sees
 * it and nothing about it is ever synced.
 */
class PendingBlockEdgeStore(private val executionContext: ExecutionContext) {

    /**
     * Persists [edges] against [blockId], with the content ids the save [cleared] (CR-2 B4 §2.3). Both
     * empty stores nothing (the push then carries no edges, and the block clears nothing).
     */
    fun save(blockId: String, edges: List<ContentEdge>, cleared: Set<String> = emptySet()) {
        if (edges.isEmpty() && cleared.isEmpty()) return
        executionContext.database.saveData(PendingBlockEdges.create(executionContext.context, blockId, edges, cleared))
    }

    /** The edges recorded against [blockId], or empty. */
    fun edgesOf(blockId: String): List<ContentEdge> = row(blockId)?.edgeList().orEmpty()

    /** The content ids [blockId]'s save cleared (B4 §2.3), or empty. */
    fun clearedOf(blockId: String): Set<String> = row(blockId)?.clearedContentIds().orEmpty()

    private fun row(blockId: String): PendingBlockEdges? =
        executionContext.database.getData(rowId(blockId)) as? PendingBlockEdges

    /** Drops [blockId]'s edges once its block is retired. Absent rows are ignored. */
    fun delete(blockId: String) {
        executionContext.database.deleteData(rowId(blockId))
    }

    private fun rowId(blockId: String): DataId = DataId.StringDataId.create(
        context = executionContext.context,
        type = PendingBlockEdges.ID,
        keyValue = blockId,
    )
}
