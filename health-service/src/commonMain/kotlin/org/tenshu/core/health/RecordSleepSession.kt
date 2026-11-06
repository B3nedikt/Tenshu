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
 * Records (upserts) a [SleepSession]. Idempotent by [SleepSession.date] (the
 * `SleepSessionDataIdProvider` folds re-imports of the same night to one row). Persists via
 * `ExecutionContext.saveData` on the proxy side → the session syncs eagerly to every paired
 * device.
 *
 * House Rule #3: the operation method is non-suspend and returns a `RequestObject`; the async
 * gap is crossed only here in the DSL extension via `asTaskInteractor`.
 */
suspend fun HealthService.recordSleepSession(sleepSession: SleepSession): TResult<Data?> =
    findOperation<RecordSleepSession>(RecordSleepSession.ID)
        .recordSleepSession(sleepSession)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordSleepSession : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordSleepSession(sleepSession: SleepSession): RequestObject

    companion object {
        const val ID = "tenshu/health/service/recordSleepSession"

        val Schema = operation(
            id = ID,
            receiver = HealthService.Schema.toType(),
            params = {
                composite(id = Params.SLEEP_SESSION, type = SleepSession.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val SLEEP_SESSION = "$ID/sleepSession"
    }
}
