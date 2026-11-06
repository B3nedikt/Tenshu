package org.tenshu.mapregion.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.mapregion.RailLinesIn
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType

/**
 * Client stub for [RailLinesIn]. Erases the return to `Data` like `InstalledRo`/`CatalogRo` — the
 * concrete `TList<RailLineGeometry>` is materialised from the wire response by the DTO factory
 * registered in `mapRegionServiceCommonModule` at `execute()` time.
 */
internal class RailLinesInRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), RailLinesIn {

    override val schema: OperationSchema get() = RailLinesIn.Schema

    override fun railLinesIn(
        minLatE7: Long,
        minLngE7: Long,
        maxLatE7: Long,
        maxLngE7: Long,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RailLinesIn.Params.MIN_LAT_E7 to minLatE7.toTInteger(context),
                    RailLinesIn.Params.MIN_LNG_E7 to minLngE7.toTInteger(context),
                    RailLinesIn.Params.MAX_LAT_E7 to maxLatE7.toTInteger(context),
                    RailLinesIn.Params.MAX_LNG_E7 to maxLngE7.toTInteger(context),
                ),
                schema = schema
            )
        )
        @Suppress("UNCHECKED_CAST")
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
