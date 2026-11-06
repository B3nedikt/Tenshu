package org.tenshu.mapregion.client.impl

import org.tenshu.core.TString
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.mapregion.LinesServing
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType

internal class LinesServingRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), LinesServing {

    override val schema: OperationSchema
        get() = LinesServing.Schema

    override fun linesServing(
        fromLatE7: Long,
        fromLngE7: Long,
        toLatE7: Long,
        toLngE7: Long,
        withinM: Long,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    LinesServing.Params.FROM_LAT_E7 to fromLatE7.toTInteger(context),
                    LinesServing.Params.FROM_LNG_E7 to fromLngE7.toTInteger(context),
                    LinesServing.Params.TO_LAT_E7 to toLatE7.toTInteger(context),
                    LinesServing.Params.TO_LNG_E7 to toLngE7.toTInteger(context),
                    LinesServing.Params.WITHIN_M to withinM.toTInteger(context),
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
