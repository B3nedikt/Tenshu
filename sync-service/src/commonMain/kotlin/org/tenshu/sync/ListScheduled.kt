package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.schema.typeOf
import org.tenshu.core.trait.TerminalOperation

/**
 * Phase 5 (§3.2(b)): the tray's data source — every persisted [ScheduleItem].
 */
suspend fun ScheduleService.listScheduled(): TResult<TList<ScheduleItem>> =
    findOperation<ListScheduled>(ListScheduled.ID)
        .listScheduled()
        .asTaskInteractor<TList<ScheduleItem>>()
        .execute()

interface ListScheduled : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun listScheduled(): RequestObject

    companion object {
        const val ID = "${ScheduleService.ID}/listScheduled"

        val Schema = operation(
            id = ID,
            receiver = ScheduleService.Schema.toType(),
            traits = listOf(TerminalOperation.Schema),
            returnType = TList.typeOf(ScheduleItem.Schema)
        )
    }
}
