package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.sync.PushBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.ContentRefusedException
import org.tenshu.sync.common.PushOutcome
import org.tenshu.sync.proxy.ContentPropertyTagger
import org.tenshu.sync.proxy.PendingBlockEdgeStore
import org.tenshu.sync.proxy.PendingBlockReviveStore

internal class PushBlockImplementation(
    private val relay: SyncRelay,
    /** ⭐ B3 wire spec §11: the edges go out under keyed property tags, never schema ids. */
    private val propertyTags: ContentPropertyTagger,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val block: SyncBlock = params.read(PushBlock.Params.BLOCK)

        // ⭐ CR-2 B3d: the edges the emit fixed from this block's payload ride the same request
        // (`content-plane-cr2-b3-wire-spec.md` §3.1). None recorded ⇒ an empty set, which is right for
        // every block but a content-referencing type's standalone entity block.
        // ⭐ CR-2 B4 (§2.2): and the revive a refused earlier attempt persisted, if any.
        val blockId = block.blockId.get()
        // ⚠ No realm to tag under ⇒ no honest name to send: fail, and the block stays queued.
        val edges = propertyTags.wireEdges(block, PendingBlockEdgeStore(executionContext).edgesOf(blockId))
            ?: throw TenshuException(
                GenericError.create(executionContext.context, "No realm key to tag block $blockId's content edges under")
            )
        val outcome = relay.pushEdged(
            block,
            edges,
            PendingBlockReviveStore(executionContext).reviveOf(blockId),
        )
        return when (outcome) {
            PushOutcome.Accepted -> null as T
            // ⚠ Typed, so the drain that runs this op can resolve it (repair and revive, or retire).
            is PushOutcome.Refused -> throw ContentRefusedException(outcome.refusal)
            is PushOutcome.Failed -> throw TenshuException(outcome.error)
        }
    }
}
