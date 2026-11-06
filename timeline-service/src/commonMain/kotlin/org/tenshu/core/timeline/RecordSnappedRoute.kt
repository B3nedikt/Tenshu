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
 * Records (upserts) a [SnappedRoute] — a movement trace's path corrected onto the rail geometry it
 * ran on (B4, `docs/proposals/timeline-movement-capture.md` §14). Idempotent by
 * [SnappedRoute.traceId], so a re-run of the refinement sweep folds rather than accumulating.
 *
 * Same shape as [RecordMovementTrace]; persists via `ExecutionContext.saveData` → syncs eagerly.
 */
suspend fun TimelineService.recordSnappedRoute(route: SnappedRoute): TResult<Data?> =
    findOperation<RecordSnappedRoute>(RecordSnappedRoute.ID)
        .recordSnappedRoute(route)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordSnappedRoute : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordSnappedRoute(route: SnappedRoute): RequestObject

    companion object {
        const val ID = "tenshu/timeline/service/recordSnappedRoute"

        val Schema = operation(
            id = ID,
            receiver = TimelineService.Schema.toType(),
            params = {
                composite(id = Params.ROUTE, type = SnappedRoute.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val ROUTE = "$ID/route"
    }
}
