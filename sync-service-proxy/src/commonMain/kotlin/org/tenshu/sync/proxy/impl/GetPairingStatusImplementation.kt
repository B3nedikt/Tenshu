package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTBoolean
import org.tenshu.sync.GetPairingStatus
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay

/**
 * Poll-once requester-side pairing status (Device B). One [PairingCompletion]
 * attempt: `Success(true)` once the relay has delivered the encrypted master
 * key and the handshake completed, `Success(false)` while still pending. The
 * wizard polls this on JS / pre-Phase-L2 callers; the cross-platform §7.5
 * shape is `AwaitPairing` (a cancellable `BackgroundOperation`) which loops
 * the *same* [PairingCompletion] — no duplicated completion logic.
 */
internal class GetPairingStatusImplementation(
    private val relay: SyncRelay,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val pairingSecret = (params?.get(GetPairingStatus.Params.PAIRING_SECRET) as TString).get()
        val otherDevicePublicKey =
            (params[GetPairingStatus.Params.OTHER_DEVICE_PUBLIC_KEY] as TByteArray).get()

        val completed = PairingCompletion(relay, syncManager, cryptoManager)
            .tryComplete(executionContext, pairingSecret, otherDevicePublicKey)

        return completed.toTBoolean(executionContext.context) as T
    }
}
