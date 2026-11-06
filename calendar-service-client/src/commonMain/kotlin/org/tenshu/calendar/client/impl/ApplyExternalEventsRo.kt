package org.tenshu.calendar.client.impl

import org.tenshu.calendar.ApplyExternalEvents
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.EventSource
import org.tenshu.calendar.Reminder
import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class ApplyExternalEventsRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), ApplyExternalEvents {

    override fun applyExternalEvents(
        events: List<Event>,
        sources: List<EventSource>,
        reminders: List<Reminder>,
        overrides: List<EventOverride>,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    ApplyExternalEvents.Params.EVENTS to TListDto(events),
                    ApplyExternalEvents.Params.SOURCES to TListDto(sources),
                    ApplyExternalEvents.Params.REMINDERS to TListDto(reminders),
                    ApplyExternalEvents.Params.OVERRIDES to TListDto(overrides),
                ),
                schema = schema,
            )
        )
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
