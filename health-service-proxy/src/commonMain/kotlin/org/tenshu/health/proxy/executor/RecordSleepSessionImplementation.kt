package org.tenshu.health.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.health.RecordSleepSession
import org.tenshu.core.health.SleepSession

internal class RecordSleepSessionImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val sleepSession: SleepSession = params.read(RecordSleepSession.Params.SLEEP_SESSION)

        // Persist via `ExecutionContext.saveData` — fires `SyncObserver` (outbound SyncBlock)
        // and `SleepSessionRelationMaintainer` (adds to `HealthService.Relations.SLEEP_SESSIONS`).
        // Never `Database.saveData` / `addDataToArray`, which bypass `SyncObserver`.
        executionContext.saveData(sleepSession)

        return null as T
    }
}
