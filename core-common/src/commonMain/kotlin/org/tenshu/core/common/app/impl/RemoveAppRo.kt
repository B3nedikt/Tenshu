package org.tenshu.core.common.app.impl

import org.tenshu.core.Data
import org.tenshu.core.app.RemoveApp
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class RemoveAppRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), RemoveApp {

    override fun removeApp(
        appId: String,
        deviceId: String?,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(RemoveApp.Params.APP_ID, appId.toTString(context))
            if (deviceId != null) {
                put(RemoveApp.Params.DEVICE_ID, deviceId.toTString(context))
            }
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
