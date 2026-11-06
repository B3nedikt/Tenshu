package org.tenshu.routine.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import org.tenshu.routine.SetRoutineEnabled

internal class SetRoutineEnabledRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), SetRoutineEnabled {

    override fun setRoutineEnabled(routineId: String, enabled: Boolean): RequestObject {
        val params = buildMap<String, Data> {
            put(SetRoutineEnabled.Params.ROUTINE_ID, routineId.toTString(context))
            put(SetRoutineEnabled.Params.ENABLED, enabled.toTBoolean(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
