package org.tenshu.calendar.client.impl

import org.tenshu.calendar.SaveEventSource
import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class SaveEventSourceRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SaveEventSource {

    override fun saveEventSource(eventId: String, raw: String, origin: String): RequestObject {
        val params = buildMap<String, Data> {
            put(SaveEventSource.Params.EVENT_ID, eventId.toTString(context))
            put(SaveEventSource.Params.RAW, raw.toTString(context))
            // ⚠ Omitted when blank rather than sent as "": the param is optional,
            // and an absent optional is what "no origin recorded" means.
            if (origin.isNotBlank()) {
                put(SaveEventSource.Params.ORIGIN, origin.toTString(context))
            }
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
