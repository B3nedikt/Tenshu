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
import org.tenshu.sync.CancelPairing

internal class CancelPairingRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), CancelPairing {

    override val schema: OperationSchema
        get() = CancelPairing.Schema

    override fun cancelPairing(pairingSecret: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    CancelPairing.Params.PAIRING_SECRET to pairingSecret.toTString(context),
                ),
                schema = schema,
            )
        )
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
