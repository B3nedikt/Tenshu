package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.sync.proxy.scheduledItemsRef

/**
 * Phase 5 (§3.2(b)): the tray's data source — every persisted `ScheduleItem`.
 */
internal class ListScheduledImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val items = executionContext.database
            .getArrayData(scheduledItemsRef(executionContext))
            ?.toList()
            .orEmpty()

        return items.toTList(executionContext.context) as T
    }
}
