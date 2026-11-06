package org.tenshu.notifications.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.notifications.DismissPendingRequest
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class DismissPendingRequestRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), DismissPendingRequest {

    override fun dismissPendingRequest(requestId: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    DismissPendingRequest.Params.REQUEST_ID to requestId.toTString(context)
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
