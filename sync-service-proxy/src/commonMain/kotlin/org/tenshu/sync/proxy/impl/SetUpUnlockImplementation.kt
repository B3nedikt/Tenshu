package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.toTBoolean
import org.tenshu.sync.SetUpUnlock
import org.tenshu.sync.common.SyncManager

internal class SetUpUnlockImplementation(
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
        val passphrase = (params?.get(SetUpUnlock.Params.PASSPHRASE) as TString).get()
        // Empty array ⇒ passphrase-only (see SetUpUnlock wire-shape KDoc).
        val wrapKeyBytes = (params?.get(SetUpUnlock.Params.BIOMETRIC_WRAP_KEY) as TByteArray).get()
        val ok = syncManager.setUpUnlock(passphrase, wrapKeyBytes.takeIf { it.isNotEmpty() })
        return ok.toTBoolean(executionContext.context) as T
    }
}
