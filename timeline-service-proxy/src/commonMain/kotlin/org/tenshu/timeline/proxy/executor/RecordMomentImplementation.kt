package org.tenshu.timeline.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.RecordMoment

internal class RecordMomentImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val moment: Moment = params.read(RecordMoment.Params.MOMENT)

        // Persist via `ExecutionContext.saveData` — fires `SyncObserver` (outbound SyncBlock)
        // and `MomentRelationMaintainer` (adds to `TimelineService.Relations.MOMENTS`).
        executionContext.saveData(moment)

        return null as T
    }
}
