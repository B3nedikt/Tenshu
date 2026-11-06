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
import org.tenshu.core.toTString
import org.tenshu.sync.ApprovePairingOffer

internal class ApprovePairingOfferRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), ApprovePairingOffer {

    override val schema: OperationSchema
        get() = ApprovePairingOffer.Schema

    override fun approvePairingOffer(pairingSecretHash: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    ApprovePairingOffer.Params.PAIRING_SECRET_HASH to pairingSecretHash.toTString(context),
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
