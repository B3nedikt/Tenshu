package org.tenshu.preference.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.preference.WriteSetting

internal class WriteSettingRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), WriteSetting {

    override fun writeSetting(
        preferenceId: String,
        value: Data,
        deviceId: String?,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(WriteSetting.Params.PREFERENCE_ID, preferenceId.toTString(context))
            put(WriteSetting.Params.VALUE, value)
            if (deviceId != null) {
                put(WriteSetting.Params.DEVICE_ID, deviceId.toTString(context))
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
