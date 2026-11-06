package org.tenshu.health.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.health.DailyNutrition
import org.tenshu.core.health.RecordDailyNutrition
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class RecordDailyNutritionRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordDailyNutrition {

    override fun recordDailyNutrition(dailyNutrition: DailyNutrition): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordDailyNutrition.Params.DAILY_NUTRITION to dailyNutrition
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
