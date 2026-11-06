package org.tenshu.sync.client.impl

import org.tenshu.core.TBoolean
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.sync.GetPairingStatus

internal class GetPairingStatusRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), GetPairingStatus {

    override val schema: OperationSchema
        get() = GetPairingStatus.Schema

    override fun getPairingStatus(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    GetPairingStatus.Params.PAIRING_SECRET to pairingSecret.toTString(context),
                    GetPairingStatus.Params.OTHER_DEVICE_PUBLIC_KEY to otherDevicePublicKey.toTByteArray(
                        context
                    )
                ),
                schema = schema
            )
        )
        return context.roProvider().createRequestObject<TBoolean>(
            type = TBoolean.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
