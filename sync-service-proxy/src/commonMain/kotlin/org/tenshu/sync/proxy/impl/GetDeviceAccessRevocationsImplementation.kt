package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.sync.proxy.DeviceAccessRevocationOutbox

/** [org.tenshu.sync.GetDeviceAccessRevocations]: every row of this device's device-access revocation outbox. */
internal class GetDeviceAccessRevocationsImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T = SimpleTList(DeviceAccessRevocationOutbox(executionContext).statuses()) as T
}
