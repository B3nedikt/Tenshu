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

/**
 * Records (upserts) a [DailyActivity]. Idempotent by [DailyActivity.date] (the
 * `DailyActivityDataIdProvider` folds re-imports of the same day to one row). Persists via
 * `ExecutionContext.saveData` on the proxy side → the aggregate syncs eagerly to every paired
 * device.
 *
 * House Rule #3: the operation method is non-suspend and returns a `RequestObject`; the async
 * gap is crossed only here in the DSL extension via `asTaskInteractor`.
 */
suspend fun HealthService.recordDailyActivity(dailyActivity: DailyActivity): TResult<Data?> =
    findOperation<RecordDailyActivity>(RecordDailyActivity.ID)
        .recordDailyActivity(dailyActivity)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordDailyActivity : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordDailyActivity(dailyActivity: DailyActivity): RequestObject

    companion object {
        const val ID = "tenshu/health/service/recordDailyActivity"

        val Schema = operation(
            id = ID,
            receiver = HealthService.Schema.toType(),
            params = {
                composite(id = Params.DAILY_ACTIVITY, type = DailyActivity.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val DAILY_ACTIVITY = "$ID/dailyActivity"
    }
}
