package org.tenshu.health.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.health.DailyActivity
import org.tenshu.core.health.RecordDailyActivity
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class RecordDailyActivityRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordDailyActivity {

    override fun recordDailyActivity(dailyActivity: DailyActivity): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordDailyActivity.Params.DAILY_ACTIVITY to dailyActivity
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
