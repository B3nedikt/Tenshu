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
import org.tenshu.preference.ClearSetting

internal class ClearSettingRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), ClearSetting {

    override fun clearSetting(
        preferenceId: String,
        deviceId: String?,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(ClearSetting.Params.PREFERENCE_ID, preferenceId.toTString(context))
            if (deviceId != null) {
                put(ClearSetting.Params.DEVICE_ID, deviceId.toTString(context))
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
