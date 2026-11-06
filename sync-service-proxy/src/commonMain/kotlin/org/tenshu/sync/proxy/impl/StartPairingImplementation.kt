package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.sync.SyncStatus
import org.tenshu.sync.common.SyncManager

internal class StartPairingImplementation(
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
        // Hosting a pairing session requires a realm-bound owner (the QR encodes it and
        // the host polls the relay under it). Without one — the account isn't set up yet —
        // return a status with no pairing string and a null owner instead of minting a
        // placeholder-owner session: the host UI then surfaces "set up your account first"
        // rather than arming a poll that 401s and reads as "can't reach the relay".
        val ownerId = syncManager.currentOwnerId
        val pairingString = if (ownerId != null) {
            syncManager.generatePairingContext()
            syncManager.getPairingString()
        } else {
            null
        }

        return SyncStatus.create(
            context = executionContext.context,
            deviceId = syncManager.currentDeviceId,
            ownerId = ownerId,
            isEnabled = syncManager.isEnabled(),
            pairingString = pairingString
        ) as T
    }
}
