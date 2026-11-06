package org.tenshu.health.client.impl

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.health.RecordWorkout
import org.tenshu.core.health.Workout
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class RecordWorkoutRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordWorkout {

    override fun recordWorkout(workout: Workout, streams: TByteArray?): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = buildMap {
                    put(RecordWorkout.Params.WORKOUT, workout)
                    if (streams != null) put(RecordWorkout.Params.STREAMS, streams)
                },
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
