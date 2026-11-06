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
 * Records (upserts) a [Moment]. Idempotent by [Moment.momentId]. Persists via
 * `ExecutionContext.saveData` on the proxy side → syncs eagerly to every paired device.
 * House Rule #3: non-suspend op method returning a `RequestObject`; the async gap is crossed
 * only in this DSL extension via `asTaskInteractor`.
 */
suspend fun TimelineService.recordMoment(moment: Moment): TResult<Data?> =
    findOperation<RecordMoment>(RecordMoment.ID)
        .recordMoment(moment)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordMoment : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordMoment(moment: Moment): RequestObject

    companion object {
        const val ID = "tenshu/timeline/service/recordMoment"

        val Schema = operation(
            id = ID,
            receiver = TimelineService.Schema.toType(),
            params = {
                composite(id = Params.MOMENT, type = Moment.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val MOMENT = "$ID/moment"
    }
}
