package org.tenshu.ingest.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.ingest.SetSourceEnabled
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

internal class SetSourceEnabledRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SetSourceEnabled {

    override fun setSourceEnabled(
        sourceId: String,
        deviceId: String,
        enabled: Boolean,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    SetSourceEnabled.Params.SOURCE_ID to sourceId.toTString(context),
                    SetSourceEnabled.Params.DEVICE_ID to deviceId.toTString(context),
                    SetSourceEnabled.Params.ENABLED to enabled.toTBoolean(context),
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
