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
 * Records (upserts) a canonical [Place]. Idempotent by [Place.placeId] (the
 * `PlaceDataIdProvider` folds re-records to one row). Persists via
 * `ExecutionContext.saveData` on the proxy side → the place syncs eagerly to every
 * paired device.
 *
 * House Rule #3: the operation method is non-suspend and returns a `RequestObject`;
 * the async gap is crossed only here in the DSL extension via `asTaskInteractor`.
 */
suspend fun PlaceService.recordPlace(place: Place): TResult<Data?> =
    findOperation<RecordPlace>(RecordPlace.ID)
        .recordPlace(place)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordPlace : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordPlace(place: Place): RequestObject

    companion object {
        const val ID = "tenshu/place/service/recordPlace"

        val Schema = operation(
            id = ID,
            receiver = PlaceService.Schema.toType(),
            params = {
                composite(id = Params.PLACE, type = Place.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PLACE = "$ID/place"
    }
}
