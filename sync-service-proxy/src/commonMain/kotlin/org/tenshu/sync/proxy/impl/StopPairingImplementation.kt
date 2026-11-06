package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.common.SyncManager

internal class StopPairingImplementation(
    private val syncManager: SyncManager
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        syncManager.stopPairing()

        return SyncStatus.create(
            context = executionContext.context,
            deviceId = syncManager.currentDeviceId,
            ownerId = syncManager.currentOwnerId,
            isEnabled = syncManager.isEnabled(),
            pairingString = null
        ) as T
    }
}
