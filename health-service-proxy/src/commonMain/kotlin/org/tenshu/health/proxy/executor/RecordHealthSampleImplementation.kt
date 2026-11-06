package org.tenshu.health.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.health.HealthSample
import org.tenshu.core.health.RecordHealthSample

internal class RecordHealthSampleImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val healthSample: HealthSample = params.read(RecordHealthSample.Params.HEALTH_SAMPLE)

        // Persist via `ExecutionContext.saveData` — fires `SyncObserver` (outbound SyncBlock)
        // and `HealthSampleRelationMaintainer` (adds to `HealthService.Relations.HEALTH_SAMPLES`).
        // Never `Database.saveData` / `addDataToArray`, which bypass `SyncObserver`.
        executionContext.saveData(healthSample)

        return null as T
    }
}
