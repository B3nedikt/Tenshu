package org.tenshu.health.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.health.RegisterHealthSource
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class RegisterHealthSourceRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RegisterHealthSource {

    override fun registerHealthSource(
        kind: String,
        deviceId: String,
        deviceLabel: String,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RegisterHealthSource.Params.KIND to kind.toTString(context),
                    RegisterHealthSource.Params.DEVICE_ID to deviceId.toTString(context),
                    RegisterHealthSource.Params.DEVICE_LABEL to deviceLabel.toTString(context),
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
