package org.tenshu.core.common.app.impl

import org.tenshu.core.Data
import org.tenshu.core.app.SetAppGrant
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class SetAppGrantRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SetAppGrant {

    override fun setAppGrant(
        appId: String,
        serviceId: String,
        operationId: String,
        grant: String,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(SetAppGrant.Params.APP_ID, appId.toTString(context))
            put(SetAppGrant.Params.SERVICE_ID, serviceId.toTString(context))
            put(SetAppGrant.Params.OPERATION_ID, operationId.toTString(context))
            put(SetAppGrant.Params.GRANT, grant.toTString(context))
        }
        val nextChain = requestChain.addArray(
            OperationDto(
                params = params,
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
