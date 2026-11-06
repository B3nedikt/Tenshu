package org.tenshu.calendar.client.impl

import org.tenshu.calendar.SaveEvent
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

internal class SaveEventRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SaveEvent {

    override fun saveEvent(
        eventId: String,
        title: String,
        startLocal: String,
        endLocal: String?,
        calendarId: String?,
        timeZoneId: String?,
        allDay: Boolean,
        location: String?,
        notes: String?,
        recurrenceRule: String?,
        exceptionDates: String?,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(SaveEvent.Params.EVENT_ID, eventId.toTString(context))
            put(SaveEvent.Params.TITLE, title.toTString(context))
            put(SaveEvent.Params.START_LOCAL, startLocal.toTString(context))
            if (endLocal != null) put(SaveEvent.Params.END_LOCAL, endLocal.toTString(context))
            if (calendarId != null) put(SaveEvent.Params.CALENDAR_ID, calendarId.toTString(context))
            if (timeZoneId != null) put(SaveEvent.Params.TIME_ZONE_ID, timeZoneId.toTString(context))
            put(SaveEvent.Params.ALL_DAY, allDay.toTBoolean(context))
            if (location != null) put(SaveEvent.Params.LOCATION, location.toTString(context))
            if (notes != null) put(SaveEvent.Params.NOTES, notes.toTString(context))
            if (recurrenceRule != null) put(SaveEvent.Params.RECURRENCE_RULE, recurrenceRule.toTString(context))
            if (exceptionDates != null) put(SaveEvent.Params.EXCEPTION_DATES, exceptionDates.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
