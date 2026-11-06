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
 * Records (upserts) a [Trip] — the curation produced when the user accepts a per-device
 * `TripSuggestion` (§5.2). Idempotent by [Trip.tripId]. Same shape as [RecordMoment].
 */
suspend fun TimelineService.recordTrip(trip: Trip): TResult<Data?> =
    findOperation<RecordTrip>(RecordTrip.ID)
        .recordTrip(trip)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordTrip : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordTrip(trip: Trip): RequestObject

    companion object {
        const val ID = "tenshu/timeline/service/recordTrip"

        val Schema = operation(
            id = ID,
            receiver = TimelineService.Schema.toType(),
            params = {
                composite(id = Params.TRIP, type = Trip.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val TRIP = "$ID/trip"
    }
}
