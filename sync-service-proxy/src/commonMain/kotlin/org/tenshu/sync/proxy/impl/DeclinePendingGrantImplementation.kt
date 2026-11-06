package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.DeclinePendingGrant
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.RealmRegistry
import org.tenshu.sync.proxy.SharedRealmGrantManager

/**
 * Grantee-side `DeclinePendingGrant` (GR-6): wraps
 * [SharedRealmGrantManager.declinePendingGrant] — tombstones the quarantined
 * `PendingGrant` from the given grantor.
 */
internal class DeclinePendingGrantImplementation(
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val relay: SyncRelay,
    private val realmRegistry: RealmRegistry,
    private val grantedRelayFactory: (RealmContext) -> SyncRelay?,
    private val nowMs: () -> Long,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val grantorFingerprint =
            (params?.get(DeclinePendingGrant.Params.GRANTOR_FINGERPRINT) as? TString)?.get()
                ?: error("DeclinePendingGrant requires grantorFingerprint")

        SharedRealmGrantManager(
            executionContext = executionContext,
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            relay = relay,
            realmRegistry = realmRegistry,
            grantedRelayFactory = grantedRelayFactory,
            nowMs = nowMs,
        ).declinePendingGrant(grantorFingerprint)

        return null as T
    }
}
