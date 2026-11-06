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
import org.tenshu.sync.OfferPairing

internal class OfferPairingRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), OfferPairing {

    override val schema: OperationSchema
        get() = OfferPairing.Schema

    override fun offerPairing(deviceName: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    OfferPairing.Params.DEVICE_NAME to deviceName.toTString(context),
                ),
                schema = schema
            )
        )
        // Composite return type (OfferPairingSession) — bind to the generic
        // Data.Schema like InitiatePairingRo / CreateRealmRo. Composites have
        // no request-object factory (only primitives do); the concrete
        // OfferPairingSession is materialised by the composite factory
        // registered in syncServiceCommonModule.
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
