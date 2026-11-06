package org.tenshu.sync

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.TerminalOperation

/**
 * Phase 5 (§3.2(b)): remove the pending [ScheduleItem] with [dedupKey]. A
 * no-op if none matches. (Cancelling work already mid-dispatch degenerates to
 * the in-process Layer-2 cancel — §6.2; Phase 5 has no platform scheduler to
 * tear down yet.)
 */
suspend fun ScheduleService.cancelScheduled(dedupKey: String): TResult<Data?> =
    findOperation<CancelScheduled>(CancelScheduled.ID)
        .cancelScheduled(dedupKey)
        .asTaskInteractor<Data?>()
        .execute()

interface CancelScheduled : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun cancelScheduled(dedupKey: String): RequestObject

    companion object {
        const val ID = "${ScheduleService.ID}/cancelScheduled"

        val Schema = operation(
            id = ID,
            receiver = ScheduleService.Schema.toType(),
            traits = listOf(TerminalOperation.Schema),
            params = {
                string(Params.DEDUP_KEY)
            }
        )
    }

    object Params {
        const val DEDUP_KEY = "$ID/dedupKey"
    }
}
