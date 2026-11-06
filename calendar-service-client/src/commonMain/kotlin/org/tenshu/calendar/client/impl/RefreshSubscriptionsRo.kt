package org.tenshu.calendar.client.impl

import org.tenshu.calendar.RefreshSubscriptions
import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class RefreshSubscriptionsRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), RefreshSubscriptions {

    override fun refreshSubscriptions(): RequestObject {
        // ⚠ No params: a `ScheduleItem` carries none, so the op finds its own
        // work when it lands.
        val nextChain = requestChain.addArray(OperationDto(params = emptyMap(), schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
