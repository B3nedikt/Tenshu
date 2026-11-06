package org.tenshu.routine.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.routine.SaveRoutine

internal class SaveRoutineRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), SaveRoutine {

    override fun saveRoutine(
        routineId: String,
        name: String,
        timeZoneId: String,
        programJson: String,
        programVersion: Long,
        homeLatE5: Long,
        homeLonE5: Long,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(SaveRoutine.Params.ROUTINE_ID, routineId.toTString(context))
            put(SaveRoutine.Params.NAME, name.toTString(context))
            put(SaveRoutine.Params.TIME_ZONE_ID, timeZoneId.toTString(context))
            put(SaveRoutine.Params.PROGRAM_JSON, programJson.toTString(context))
            put(SaveRoutine.Params.PROGRAM_VERSION, programVersion.toTInteger(context))
            put(SaveRoutine.Params.HOME_LAT_E5, homeLatE5.toTInteger(context))
            put(SaveRoutine.Params.HOME_LON_E5, homeLonE5.toTInteger(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
