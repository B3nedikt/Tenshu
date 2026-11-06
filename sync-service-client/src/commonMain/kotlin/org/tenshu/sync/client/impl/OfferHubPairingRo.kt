package org.tenshu.sync.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.sync.OfferHubPairing

internal class OfferHubPairingRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), OfferHubPairing {

    override val schema: OperationSchema
        get() = OfferHubPairing.Schema

    override fun offerHubPairing(printedUri: String, deviceName: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    OfferHubPairing.Params.PRINTED_URI to printedUri.toTString(context),
                    OfferHubPairing.Params.DEVICE_NAME to deviceName.toTString(context),
                ),
                schema = schema
            )
        )
        // Composite return type (OfferPairingSession) — bind to the generic Data.Schema
        // like OfferPairingRo; the concrete composite is materialised by the factory
        // registered in syncServiceCommonModule.
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
