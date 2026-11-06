package org.tenshu.timeline.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.timeline.RecordSnappedRoute
import org.tenshu.core.timeline.SnappedRoute

internal class RecordSnappedRouteRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordSnappedRoute {

    override fun recordSnappedRoute(route: SnappedRoute): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordSnappedRoute.Params.ROUTE to route
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
