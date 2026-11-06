package org.tenshu.calendar.client.impl

import org.tenshu.calendar.SaveEventOverride
import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class SaveEventOverrideRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SaveEventOverride {

    override fun saveEventOverride(
        eventId: String,
        recurrenceId: String,
        startLocal: String?,
        endLocal: String?,
        title: String?,
        location: String?,
        notes: String?,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(SaveEventOverride.Params.EVENT_ID, eventId.toTString(context))
            put(SaveEventOverride.Params.RECURRENCE_ID, recurrenceId.toTString(context))
            if (startLocal != null) put(SaveEventOverride.Params.START_LOCAL, startLocal.toTString(context))
            if (endLocal != null) put(SaveEventOverride.Params.END_LOCAL, endLocal.toTString(context))
            if (title != null) put(SaveEventOverride.Params.TITLE, title.toTString(context))
            if (location != null) put(SaveEventOverride.Params.LOCATION, location.toTString(context))
            if (notes != null) put(SaveEventOverride.Params.NOTES, notes.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
