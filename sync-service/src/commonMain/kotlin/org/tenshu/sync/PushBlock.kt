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
 * Extension function for DSL usage.
 */
suspend fun SyncService.pushBlock(block: SyncBlock): TResult<Data?> {
    val operation = findOperation<PushBlock>(PushBlock.ID)
    return operation.pushBlock(block).asTaskInteractor<Data?>().execute()
}

/**
 * Operation for pushing a sync block to the backend.
 */
interface PushBlock : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun pushBlock(block: SyncBlock): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/pushBlock"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            traits = listOf(TerminalOperation.Schema, IoOperation.Schema),
            params = {
                composite(Params.BLOCK, type = SyncBlock.Schema)
            },
            returnType = Data.Schema.toType()
        )
    }

    object Params {
        const val BLOCK = "$ID/block"
    }
}
