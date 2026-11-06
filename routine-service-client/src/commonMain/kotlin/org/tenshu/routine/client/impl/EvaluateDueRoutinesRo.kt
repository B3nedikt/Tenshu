package org.tenshu.routine.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.routine.EvaluateDueRoutines

internal class EvaluateDueRoutinesRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), EvaluateDueRoutines {

    override fun evaluateDueRoutines(): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(params = emptyMap<String, Data>(), schema = schema)
        )
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
