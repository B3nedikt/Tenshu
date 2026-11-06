package org.tenshu.health.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.health.MenstruationDay
import org.tenshu.core.health.RecordMenstruationDay
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class RecordMenstruationDayRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordMenstruationDay {

    override fun recordMenstruationDay(menstruationDay: MenstruationDay): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordMenstruationDay.Params.MENSTRUATION_DAY to menstruationDay
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
