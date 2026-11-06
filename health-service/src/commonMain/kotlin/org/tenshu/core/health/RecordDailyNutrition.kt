package org.tenshu.core.health

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun HealthService.recordDailyNutrition(dailyNutrition: DailyNutrition): TResult<Data?> =
    findOperation<RecordDailyNutrition>(RecordDailyNutrition.ID)
        .recordDailyNutrition(dailyNutrition)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordDailyNutrition : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordDailyNutrition(dailyNutrition: DailyNutrition): RequestObject

    companion object {
        const val ID = "tenshu/health/service/recordDailyNutrition"

        val Schema = operation(
            id = ID,
            receiver = HealthService.Schema.toType(),
            params = {
                composite(id = Params.DAILY_NUTRITION, type = DailyNutrition.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val DAILY_NUTRITION = "$ID/dailyNutrition"
    }
}
