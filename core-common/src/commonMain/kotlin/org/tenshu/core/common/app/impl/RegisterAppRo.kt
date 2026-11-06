package org.tenshu.core.common.app.impl

import org.tenshu.core.Data
import org.tenshu.core.app.RegisterApp
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

internal class RegisterAppRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), RegisterApp {

    override fun registerApp(
        appId: String,
        deviceId: String,
        verified: Boolean,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(RegisterApp.Params.APP_ID, appId.toTString(context))
            put(RegisterApp.Params.DEVICE_ID, deviceId.toTString(context))
            put(RegisterApp.Params.VERIFIED, verified.toTBoolean(context))
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
