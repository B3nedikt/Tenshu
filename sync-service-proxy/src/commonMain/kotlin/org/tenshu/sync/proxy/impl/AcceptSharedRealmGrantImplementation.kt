package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.sync.AcceptSharedRealmGrant
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.RealmRegistry
import org.tenshu.sync.proxy.SharedRealmGrantManager

/**
 * Grantee-side `AcceptSharedRealmGrant` (GR-3): wraps [SharedRealmGrantManager.accept].
 * Returns the joined realm's `ownerId`.
 */
internal class AcceptSharedRealmGrantImplementation(
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
            (params?.get(AcceptSharedRealmGrant.Params.GRANTOR_FINGERPRINT) as? TString)?.get()
                ?: error("AcceptSharedRealmGrant requires grantorFingerprint")

        val realmId = SharedRealmGrantManager(
            executionContext = executionContext,
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            relay = relay,
            realmRegistry = realmRegistry,
            grantedRelayFactory = grantedRelayFactory,
            nowMs = nowMs,
        ).accept(grantorFingerprint)

        return realmId.toTString(executionContext.context) as T
    }
}
