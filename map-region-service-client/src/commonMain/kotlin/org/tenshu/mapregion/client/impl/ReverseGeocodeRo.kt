package org.tenshu.mapregion.client.impl

import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.mapregion.GeocodedPlace
import org.tenshu.core.mapregion.ReverseGeocodePlace
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType

internal class ReverseGeocodeRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), ReverseGeocodePlace {

    override val schema: OperationSchema
        get() = ReverseGeocodePlace.Schema

    override fun reverseGeocodePlace(latE7: Long, lngE7: Long): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    ReverseGeocodePlace.Params.LAT_E7 to latE7.toTInteger(context),
                    ReverseGeocodePlace.Params.LNG_E7 to lngE7.toTInteger(context),
                ),
                schema = schema
            )
        )
        @Suppress("UNCHECKED_CAST")
        return context.roProvider().createRequestObject<GeocodedPlace>(
            type = GeocodedPlace.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
