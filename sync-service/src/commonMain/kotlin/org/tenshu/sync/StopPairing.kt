package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Extension for triggering the StopPairing operation.
 */
suspend fun SyncService.stopPairing(): TResult<SyncStatus> =
    findOperation<StopPairing>(StopPairing.ID)
        .stopPairing()
        .asTaskInteractor<SyncStatus>()
        .execute()

interface StopPairing : Operation {

    override val schema get() = Schema

    fun stopPairing(): RequestObject

    companion object {
        const val ID = "tenshu/sync/stopPairing"
        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            returnType = SyncStatus.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
