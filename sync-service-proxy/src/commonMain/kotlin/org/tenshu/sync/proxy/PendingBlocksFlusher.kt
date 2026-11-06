package org.tenshu.sync.proxy

import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.PushOutcome
import org.tenshu.sync.common.SyncRelay

/**
 * Pushes the locally-queued pending [SyncBlock]s to the relay synchronously,
 * outside the [SyncScheduler]'s polling cadence.
 *
 * Why this exists: [SyncObserver] writes blocks to a local pending queue that
 * the scheduler drains on its `pollingIntervalMs` cadence (30s in production).
 * That's fine for most flows — receivers eventually pull and see the block —
 * but it opens a race at pairing approve time:
 *
 * The host's `CreateRealm` saves its `Device` entity, which queues a `SyncBlock`
 * with `hlc.physicalTime = T_h` (the host's wall clock at bootstrap). If the
 * user is fast enough that the joiner pairs and completes its first
 * `/sync/changes` pull before the host's scheduler has fired, the joiner's
 * mailbox query returns only the joiner's own block (just pushed during the
 * same tick) and advances `lastSyncTimestamp` to that block's hlc — which is
 * later than `T_h`. When the host's scheduler eventually drains and the
 * host's Device block lands in the mailbox, the relay's filter
 * `block.hlc.physicalTime > sinceTimestamp` drops it on every subsequent pull.
 * The joiner never sees the host's Device.
 *
 * Forcing a drain at the end of [org.tenshu.sync.proxy.impl.ApprovePairingRequestImplementation]
 * guarantees the host's pending state — both its CreateRealm-time `Device` and
 * the joiner's newly-authorised `Device` that approve itself authors — is in
 * the relay's mailbox before the joiner can possibly pull. Closes the pairing
 * race; the residual general-case cursor-advancement bug (any pre-pairing
 * blocks on the host that aren't `Device`s, e.g. files, has the same shape)
 * deserves its own fix at the relay/cursor layer.
 *
 * Same shape as [SyncScheduler.drainPendingBlocks] but doesn't go through
 * `executeAsync(PushBlock.ID)` — calls the relay directly because the caller
 * is already inside an operation implementation, and we don't want to risk
 * re-entrancy through the operation pipeline.
 *
 * Idempotent: blocks that successfully push are removed from the queue; if
 * the scheduler races us on the same iteration, the duplicate push is
 * harmless (relay mailbox is append-only and receivers process each block
 * by content, not arrival order). Partial failures leave remaining blocks
 * in the queue for the scheduler to retry on its next tick.
 */
internal suspend fun flushPendingBlocks(
    executionContext: ExecutionContext,
    syncRelay: SyncRelay,
    /**
     * ⭐ B3 wire spec §11: tags the edges for the wire. This queue is the personal realm's, so a
     * tagger over the [org.tenshu.sync.common.SyncManager] alone suffices.
     */
    propertyTags: ContentPropertyTagger,
    /**
     * ⭐ CR-2 B4 §2.2: the scheduler's resolver, for a block the relay refuses as naming freed content.
     * `null` (no scheduler yet, test wirings) ⇒ such a block is left queued for the scheduler's drain.
     */
    freedContentResolver: FreedContentResolver? = null,
) {
    val pendingBlocksRef = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = SyncBlock.ID,
            relationId = SyncService.Collections.PENDING_BLOCKS
        ),
        isEntity = false,
        elementSchemaId = SyncBlock.ID
    )

    val pending = executionContext.database.getArrayData(pendingBlocksRef) ?: return
    val queued = pending.toList().map { it as SyncBlock }.map { it to it.blockId.get() }
    val dropped = mutableSetOf<String>()
    for ((block, blockId) in queued) {
        if (blockId in dropped) continue
        // ⚠ Two attempts at most: a refusal resolved by a revive is pushed once more at once.
        for (attempt in 0 until 2) {
            // CR-2 B3d: the same edges `PushBlock` would send — the emit fixed them from the payload —
            // and CR-2 B4: the revive a refused earlier attempt persisted.
            // ⚠ No realm to tag under: left queued for the scheduler, like any failed push.
            val edges = propertyTags.wireEdges(block, PendingBlockEdgeStore(executionContext).edgesOf(blockId)) ?: break
            val outcome = syncRelay.pushEdged(
                block,
                edges,
                PendingBlockReviveStore(executionContext).reviveOf(blockId),
            )
            if (outcome is PushOutcome.Accepted) {
                val blockDataId = executionContext.getDataId(block) ?: break
                executionContext.database.removeFromArray(pendingBlocksRef, blockDataId)
                PendingBlockEdgeStore(executionContext).delete(blockId)
                PendingBlockReviveStore(executionContext).delete(blockId)
                break
            }
            if (outcome !is PushOutcome.Refused || freedContentResolver == null) break
            val resolution = freedContentResolver.resolve(
                listOf(pendingBlocksRef), block, blockId, outcome.refusal,
            )
            if (resolution is FreedContentResolver.Resolution.Dropped) dropped += resolution.droppedBlockIds
            if (resolution != FreedContentResolver.Resolution.Retry) break
        }
    }
}
