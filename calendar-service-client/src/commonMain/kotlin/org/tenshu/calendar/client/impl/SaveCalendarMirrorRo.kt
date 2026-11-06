package org.tenshu.calendar.client.impl

import org.tenshu.calendar.SaveCalendarMirror
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

internal class SaveCalendarMirrorRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SaveCalendarMirror {

    override fun saveCalendarMirror(
        mirrorId: String,
        calendarId: String,
        deviceId: String,
        sourceKind: String,
        sourceKey: String,
        sourceLabel: String,
        remindersEnabled: Boolean,
    ): RequestObject {
        val params = mapOf<String, Data>(
            SaveCalendarMirror.Params.MIRROR_ID to mirrorId.toTString(context),
            SaveCalendarMirror.Params.CALENDAR_ID to calendarId.toTString(context),
            SaveCalendarMirror.Params.DEVICE_ID to deviceId.toTString(context),
            SaveCalendarMirror.Params.SOURCE_KIND to sourceKind.toTString(context),
            SaveCalendarMirror.Params.SOURCE_KEY to sourceKey.toTString(context),
            SaveCalendarMirror.Params.SOURCE_LABEL to sourceLabel.toTString(context),
            SaveCalendarMirror.Params.REMINDERS_ENABLED to remindersEnabled.toTBoolean(context),
        )
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
