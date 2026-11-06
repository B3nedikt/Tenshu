package org.tenshu.routine.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.routine.DeleteRoutine

internal class DeleteRoutineRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), DeleteRoutine {

    override fun deleteRoutine(routineId: String): RequestObject {
        val params = buildMap<String, Data> {
            put(DeleteRoutine.Params.ROUTINE_ID, routineId.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
