package org.tenshu.mapregion.client.impl

import org.tenshu.core.TString
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.mapregion.NearestTransitStop
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType

internal class NearestTransitStopRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), NearestTransitStop {

    override val schema: OperationSchema
        get() = NearestTransitStop.Schema

    override fun nearestTransitStop(latE7: Long, lngE7: Long, withinM: Long): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    NearestTransitStop.Params.LAT_E7 to latE7.toTInteger(context),
                    NearestTransitStop.Params.LNG_E7 to lngE7.toTInteger(context),
                    NearestTransitStop.Params.WITHIN_M to withinM.toTInteger(context),
                ),
                schema = schema
            )
        )
        @Suppress("UNCHECKED_CAST")
        return context.roProvider().createRequestObject<TString>(
            type = TString.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
