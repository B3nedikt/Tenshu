package org.tenshu.health.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.health.DailyActivity
import org.tenshu.core.health.RecordDailyActivity
import org.tenshu.health.proxy.DailyActivityFold

internal class RecordDailyActivityImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val dailyActivity: DailyActivity = params.read(RecordDailyActivity.Params.DAILY_ACTIVITY)

        // Fold onto whatever is already stored for this day rather than replacing it: the row is
        // keyed by date alone, and the sources that write a day are partial in different ways (a
        // steps-only Health Connect refresh must not zero a Takeout import's distance/energy).
        // See [DailyActivityFold].
        val folded = DailyActivityFold.foldOntoStored(executionContext, dailyActivity)

        // Persist via `ExecutionContext.saveData` — fires `SyncObserver` (outbound SyncBlock)
        // and `DailyActivityRelationMaintainer` (adds to
        // `HealthService.Relations.DAILY_ACTIVITIES`). Never `Database.saveData` /
        // `addDataToArray`, which bypass `SyncObserver`.
        executionContext.saveData(folded)

        return null as T
    }
}
