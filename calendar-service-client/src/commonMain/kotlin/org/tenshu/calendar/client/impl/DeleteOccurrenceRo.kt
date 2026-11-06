package org.tenshu.calendar.client.impl

import org.tenshu.calendar.DeleteOccurrence
import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class DeleteOccurrenceRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), DeleteOccurrence {

    override fun deleteOccurrence(
        eventId: String,
        recurrenceId: String,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(DeleteOccurrence.Params.EVENT_ID, eventId.toTString(context))
            put(DeleteOccurrence.Params.RECURRENCE_ID, recurrenceId.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
