package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.proxy.DeviceRecords

/** [org.tenshu.sync.GetRemovedDevices]: the verified removal record deciding each removed device. */
internal class GetRemovedDevicesImplementation(
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T = DeviceRecords(executionContext, syncManager, cryptoManager).removals()
        .toTList(executionContext.context) as T
}
