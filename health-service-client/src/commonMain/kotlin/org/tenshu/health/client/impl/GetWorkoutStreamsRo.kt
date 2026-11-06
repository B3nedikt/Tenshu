package org.tenshu.health.client.impl

import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.health.GetWorkoutStreams
import org.tenshu.core.health.Workout
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

// The request-object binding for the Workout.Streams on-demand resolver — the
// health twin of GetDataRo (read-scaling option B).
internal class GetWorkoutStreamsRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), GetWorkoutStreams {

    override fun getStreams(workout: Workout): TByteArray {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(GetWorkoutStreams.Params.WORKOUT to workout),
                schema = schema
            )
        )
        return context.roProvider()
            .createRequestObject(TByteArray.Schema.toType(), nextChain, context)
    }
}
