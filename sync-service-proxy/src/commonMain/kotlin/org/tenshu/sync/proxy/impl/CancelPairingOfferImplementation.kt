package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.toTBoolean
import org.tenshu.sync.CancelPairingOffer
import org.tenshu.sync.common.SyncManager

/** Offerer teardown: zero privB + drop the offer session. Idempotent. */
internal class CancelPairingOfferImplementation(
    private val syncManager: SyncManager,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val hash = (params?.get(CancelPairingOffer.Params.PAIRING_SECRET_HASH) as TString).get()
        syncManager.clearOfferSession(hash)
        return true.toTBoolean(executionContext.context) as T
    }
}
