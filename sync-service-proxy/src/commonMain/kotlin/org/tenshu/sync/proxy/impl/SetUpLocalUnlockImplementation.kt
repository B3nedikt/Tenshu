package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.toTBoolean
import org.tenshu.sync.SetUpLocalUnlock
import org.tenshu.sync.common.SyncManager

internal class SetUpLocalUnlockImplementation(
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
        val passphrase =
            (params?.get(SetUpLocalUnlock.Params.PASSPHRASE) as TString).get()
        val ok = syncManager.setUpLocalUnlock(passphrase)
        return ok.toTBoolean(executionContext.context) as T
    }
}
