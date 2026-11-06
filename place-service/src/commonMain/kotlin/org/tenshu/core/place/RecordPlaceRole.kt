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
 * Records (upserts) a [PlaceRole] — "this place is the user's home / work / explicitly neither".
 * Idempotent by [PlaceRole.placeId], so an inference and the user's later answer land on one row
 * rather than accumulating, and two devices deciding the same role converge instead of racing.
 *
 * ⚠ The op is a plain upsert; it does **not** itself enforce "inference never overwrites the user"
 * — that rule lives where it can actually be applied with knowledge of what is already stored
 * (the producer's read-before-write locally, `PlaceRoleMerger` across devices).
 *
 * House Rule #3: non-suspend at the interface, returning a `RequestObject`; the async gap is crossed
 * only here in the DSL extension via `asTaskInteractor`.
 */
suspend fun PlaceService.recordPlaceRole(role: PlaceRole): TResult<Data?> =
    findOperation<RecordPlaceRole>(RecordPlaceRole.ID)
        .recordPlaceRole(role)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordPlaceRole : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordPlaceRole(role: PlaceRole): RequestObject

    companion object {
        const val ID = "tenshu/place/service/recordPlaceRole"

        val Schema = operation(
            id = ID,
            receiver = PlaceService.Schema.toType(),
            params = {
                composite(id = Params.ROLE, type = PlaceRole.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val ROLE = "$ID/role"
    }
}
