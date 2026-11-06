package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.PushTombstone
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.DatabaseTombstoneCascadeStore
import org.tenshu.sync.proxy.TombstoneCascades
import org.tenshu.sync.proxy.TombstoneWireType

internal class PushTombstoneImplementation(
    private val relay: SyncRelay,
    /**
     * ⭐ `sync-envelope-authentication.md` §3.1: the identity key of the realm whose `ownerId` the
     * tombstone carries, or `null` when this device holds none (locked, or a realm it left). The
     * drain then fails and the tombstone stays queued — an untagged one would be refused anyway.
     */
    private val identityKeyFor: (ownerId: String) -> ByteArray?,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val block: DeleteBlock = params.read(PushTombstone.Params.BLOCK)

        // ⭐ CR-2 B3d: the subtree this delete removed, from B3b's persisted cascade, rides the same
        // request (`content-plane-cr2-b3-wire-spec.md` §3.2). Read from the store by `blockId`, so a
        // tombstone queued before a restart still names its descendants.
        val cascade = TombstoneCascades(DatabaseTombstoneCascadeStore(executionContext)).holdersOf(block.blockId.get())
        val identityKey = identityKeyFor(block.ownerId.get())?.takeIf { it.isNotEmpty() }
            ?: throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "No realm key to authenticate tombstone ${block.blockId.get()} under",
                )
            )
        // ⭐ EI-4 (sync-entity-identity.md §5b): the type narrows to device-or-not HERE, at drain time,
        // so a tombstone queued before the upgrade does not name its concrete type after it — and the
        // tag is computed over exactly that wire form, which exists nowhere else.
        val result = relay.pushTombstone(TombstoneWireType.forWire(executionContext.context, block, identityKey), cascade)
        if (result is TResult.Failure<*>) {
            throw TenshuException(result.error)
        }
        return (result as TResult.Success<*>).value as T
    }
}
