package org.tenshu.core.timeline

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
 * Records (upserts) a [MovementTrace] — the path of a captured movement (M2,
 * `docs/proposals/timeline-movement-capture.md` §6). Idempotent by [MovementTrace.traceId]. Same
 * shape as [RecordMoment]/[RecordTrip]; persists via `ExecutionContext.saveData` → syncs eagerly.
 */
suspend fun TimelineService.recordMovementTrace(trace: MovementTrace): TResult<Data?> =
    findOperation<RecordMovementTrace>(RecordMovementTrace.ID)
        .recordMovementTrace(trace)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordMovementTrace : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordMovementTrace(trace: MovementTrace): RequestObject

    companion object {
        const val ID = "tenshu/timeline/service/recordMovementTrace"

        val Schema = operation(
            id = ID,
            receiver = TimelineService.Schema.toType(),
            params = {
                composite(id = Params.TRACE, type = MovementTrace.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val TRACE = "$ID/trace"
    }
}
