package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.sync.UnlockStatus
import org.tenshu.sync.common.SyncManager

internal class GetUnlockStatusImplementation(
    private val syncManager: SyncManager,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val status = syncManager.getUnlockStatus()
        return UnlockStatus.create(
            context = executionContext.context,
            isSetUp = status.isSetUp,
            isLocked = status.isLocked,
            hasPassphrase = status.hasPassphrase,
        ) as T
    }
}
