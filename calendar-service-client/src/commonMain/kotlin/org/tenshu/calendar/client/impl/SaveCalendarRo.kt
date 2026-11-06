package org.tenshu.calendar.client.impl

import org.tenshu.calendar.SaveCalendar
import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.core.toTBoolean
import org.tenshu.core.number.toTInteger

internal class SaveCalendarRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SaveCalendar {

    override fun saveCalendar(
        calendarId: String,
        name: String,
        colorArgb: Int,
        sensitive: Boolean,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(SaveCalendar.Params.CALENDAR_ID, calendarId.toTString(context))
            put(SaveCalendar.Params.NAME, name.toTString(context))
            put(SaveCalendar.Params.COLOR_ARGB, colorArgb.toTInteger(context))
            put(SaveCalendar.Params.SENSITIVE, sensitive.toTBoolean(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
