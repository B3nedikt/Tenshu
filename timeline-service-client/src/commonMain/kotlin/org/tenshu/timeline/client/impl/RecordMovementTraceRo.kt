package org.tenshu.timeline.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.timeline.MovementTrace
import org.tenshu.core.timeline.RecordMovementTrace

internal class RecordMovementTraceRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordMovementTrace {

    override fun recordMovementTrace(trace: MovementTrace): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordMovementTrace.Params.TRACE to trace
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
