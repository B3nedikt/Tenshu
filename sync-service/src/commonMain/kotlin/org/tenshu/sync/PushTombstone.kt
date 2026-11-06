package org.tenshu.sync

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Pushes a single [DeleteBlock] (tombstone) to the relay. Sibling of [PushBlock] on the
 * tombstone wire path per `docs/adr/0006-sync-tombstones.md` — the relay treats
 * tombstones as "wire-distinct from SyncBlock" and we mirror that distinction at the
 * operation level so the typing stays clean (no `Composite`-vs-`SyncBlock` polymorphism
 * leaking into the SyncRelay surface).
 *
 * Driven by [org.tenshu.sync.proxy.SyncScheduler.drainPendingTombstones] — emits queued
 * tombstones from `SyncService.Collections.PENDING_TOMBSTONES`.
 */
suspend fun SyncService.pushTombstone(block: DeleteBlock): TResult<Data?> =
    findOperation<PushTombstone>(PushTombstone.ID)
        .pushTombstone(block)
        .asTaskInteractor<Data?>()
        .execute()

interface PushTombstone : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun pushTombstone(block: DeleteBlock): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/pushTombstone"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            traits = listOf(TerminalOperation.Schema, IoOperation.Schema),
            params = {
                composite(Params.BLOCK, type = DeleteBlock.Schema)
            },
            returnType = Data.Schema.toType()
        )
    }

    object Params {
        const val BLOCK = "$ID/block"
    }
}
