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
import org.tenshu.sync.GetOfferStatus

internal class GetOfferStatusRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), GetOfferStatus {

    override val schema: OperationSchema
        get() = GetOfferStatus.Schema

    override fun getOfferStatus(pairingSecretHash: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    GetOfferStatus.Params.PAIRING_SECRET_HASH to pairingSecretHash.toTString(context),
                ),
                schema = schema
            )
        )
        // Composite return type (OfferStatus) — bind to Data.Schema; the
        // concrete OfferStatus is materialised by the composite factory in
        // syncServiceCommonModule.
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
