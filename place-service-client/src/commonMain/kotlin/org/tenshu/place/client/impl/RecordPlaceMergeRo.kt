package org.tenshu.place.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.place.PlaceMerge
import org.tenshu.core.place.RecordPlaceMerge
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class RecordPlaceMergeRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordPlaceMerge {

    override fun recordPlaceMerge(merge: PlaceMerge): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordPlaceMerge.Params.MERGE to merge
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
