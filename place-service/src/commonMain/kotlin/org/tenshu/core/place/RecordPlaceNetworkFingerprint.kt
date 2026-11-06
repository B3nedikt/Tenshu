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
 * Records (upserts) a [PlaceNetworkFingerprint] — "this network was seen at this place"
 * (`timeline-recognition-and-place-naming.md` §9.3). Idempotent by `networkTag`, so re-observing a
 * known network folds to the one row rather than growing the set.
 *
 * House Rule #3: non-suspend at the interface, returning a `RequestObject`; the async gap is crossed
 * only here in the DSL extension via `asTaskInteractor`.
 */
suspend fun PlaceService.recordPlaceNetworkFingerprint(
    fingerprint: PlaceNetworkFingerprint,
): TResult<Data?> =
    findOperation<RecordPlaceNetworkFingerprint>(RecordPlaceNetworkFingerprint.ID)
        .recordPlaceNetworkFingerprint(fingerprint)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordPlaceNetworkFingerprint : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordPlaceNetworkFingerprint(fingerprint: PlaceNetworkFingerprint): RequestObject

    companion object {
        const val ID = "tenshu/place/service/recordPlaceNetworkFingerprint"

        val Schema = operation(
            id = ID,
            receiver = PlaceService.Schema.toType(),
            params = {
                composite(id = Params.FINGERPRINT, type = PlaceNetworkFingerprint.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val FINGERPRINT = "$ID/fingerprint"
    }
}
