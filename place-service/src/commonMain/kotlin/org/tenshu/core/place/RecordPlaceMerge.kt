package org.tenshu.core.place

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
 * Records (upserts) a [PlaceMerge] — "this place is really that one"
 * (`timeline-recognition-and-place-naming.md` §9.4). Idempotent by the folded-away `placeId`, so two
 * devices recording the same (deterministically chosen) merge converge on one row rather than
 * racing.
 *
 * House Rule #3: non-suspend at the interface, returning a `RequestObject`; the async gap is crossed
 * only here in the DSL extension via `asTaskInteractor`.
 */
suspend fun PlaceService.recordPlaceMerge(merge: PlaceMerge): TResult<Data?> =
    findOperation<RecordPlaceMerge>(RecordPlaceMerge.ID)
        .recordPlaceMerge(merge)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordPlaceMerge : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordPlaceMerge(merge: PlaceMerge): RequestObject

    companion object {
        const val ID = "tenshu/place/service/recordPlaceMerge"

        val Schema = operation(
            id = ID,
            receiver = PlaceService.Schema.toType(),
            params = {
                composite(id = Params.MERGE, type = PlaceMerge.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val MERGE = "$ID/merge"
    }
}
