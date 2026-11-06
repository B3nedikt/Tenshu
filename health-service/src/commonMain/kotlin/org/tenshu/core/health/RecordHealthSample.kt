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
 * Records (upserts) a [HealthSample]. Idempotent by [HealthSample.metric] (the
 * `HealthSampleDataIdProvider` folds a newer reading of the same metric onto one row → latest
 * per metric). Persists via `ExecutionContext.saveData` on the proxy side → syncs eagerly to
 * every paired device.
 *
 * House Rule #3: the operation method is non-suspend and returns a `RequestObject`; the async
 * gap is crossed only here in the DSL extension via `asTaskInteractor`.
 */
suspend fun HealthService.recordHealthSample(healthSample: HealthSample): TResult<Data?> =
    findOperation<RecordHealthSample>(RecordHealthSample.ID)
        .recordHealthSample(healthSample)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordHealthSample : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordHealthSample(healthSample: HealthSample): RequestObject

    companion object {
        const val ID = "tenshu/health/service/recordHealthSample"

        val Schema = operation(
            id = ID,
            receiver = HealthService.Schema.toType(),
            params = {
                composite(id = Params.HEALTH_SAMPLE, type = HealthSample.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val HEALTH_SAMPLE = "$ID/healthSample"
    }
}
