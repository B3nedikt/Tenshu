package org.tenshu.mapregion.client.impl

import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.mapregion.StreamRegionRange
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class StreamRegionRangeRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), StreamRegionRange {

    override val schema: OperationSchema
        get() = StreamRegionRange.Schema

    override fun streamRegionRange(regionId: String, offset: Long, length: Int): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    StreamRegionRange.Params.REGION_ID to regionId.toTString(context),
                    StreamRegionRange.Params.OFFSET to offset.toTInteger(context),
                    StreamRegionRange.Params.LENGTH to length.toTInteger(context),
                ),
                schema = schema
            )
        )
        @Suppress("UNCHECKED_CAST")
        return context.roProvider().createRequestObject<TByteArray>(
            type = TByteArray.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
