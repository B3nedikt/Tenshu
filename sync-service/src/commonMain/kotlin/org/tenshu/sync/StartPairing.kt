package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.BackgroundOperation
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Extension for triggering the StartPairing operation.
 */
suspend fun SyncService.startPairing(): TResult<SyncStatus> =
    findOperation<StartPairing>(StartPairing.ID)
        .startPairing()
        .asTaskInteractor<SyncStatus>()
        .execute()

interface StartPairing : Operation {

    override val schema get() = Schema

    fun startPairing(): RequestObject

    companion object {
        const val ID = "tenshu/sync/startPairing"
        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            returnType = SyncStatus.Schema.toType(),
            traits = listOf(
                IoOperation.Schema,
                TerminalOperation.Schema,
                BackgroundOperation.Schema
            )
        )
    }
}
