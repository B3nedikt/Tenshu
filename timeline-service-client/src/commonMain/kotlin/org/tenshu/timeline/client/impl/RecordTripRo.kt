package org.tenshu.timeline.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.timeline.RecordTrip
import org.tenshu.core.timeline.Trip

internal class RecordTripRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordTrip {

    override fun recordTrip(trip: Trip): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordTrip.Params.TRIP to trip
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
