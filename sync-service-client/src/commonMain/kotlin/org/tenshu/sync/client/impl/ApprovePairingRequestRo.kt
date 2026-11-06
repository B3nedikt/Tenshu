package org.tenshu.sync.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.sync.ApprovePairingRequest

internal class ApprovePairingRequestRo(
    override val requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), ApprovePairingRequest {

    override val schema: OperationSchema
        get() = ApprovePairingRequest.Schema

    override fun approvePairingRequest(deviceId: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(ApprovePairingRequest.Params.DEVICE_ID to deviceId.toTString(context)),
                schema = schema
            )
        )
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
