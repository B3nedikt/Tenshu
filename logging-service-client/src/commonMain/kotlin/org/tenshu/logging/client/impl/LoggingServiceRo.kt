package org.tenshu.logging.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.logging.ErrorEvent
import org.tenshu.logging.LoggingService

internal class LoggingServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : LoggingService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val events: TList<ErrorEvent>
        get() = serviceRelation(LoggingService.Relations.EVENTS, ErrorEvent.Schema)
}
