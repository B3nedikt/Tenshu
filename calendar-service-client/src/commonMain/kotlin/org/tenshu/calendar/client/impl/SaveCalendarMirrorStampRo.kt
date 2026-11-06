package org.tenshu.calendar.client.impl

import org.tenshu.calendar.SaveCalendarMirrorStamp
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

internal class SaveCalendarMirrorStampRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SaveCalendarMirrorStamp {

    override fun saveCalendarMirrorStamp(
        mirrorId: String,
        deviceId: String,
        lastRefreshedEpochMs: Long,
    ): RequestObject {
        val params = mapOf<String, Data>(
            SaveCalendarMirrorStamp.Params.MIRROR_ID to mirrorId.toTString(context),
            SaveCalendarMirrorStamp.Params.DEVICE_ID to deviceId.toTString(context),
            SaveCalendarMirrorStamp.Params.LAST_REFRESHED_EPOCH_MS to lastRefreshedEpochMs.toTInteger(context),
        )
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
