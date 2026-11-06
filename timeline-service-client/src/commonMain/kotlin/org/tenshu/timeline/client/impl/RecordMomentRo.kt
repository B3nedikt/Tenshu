package org.tenshu.timeline.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.RecordMoment

internal class RecordMomentRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordMoment {

    override fun recordMoment(moment: Moment): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordMoment.Params.MOMENT to moment
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
