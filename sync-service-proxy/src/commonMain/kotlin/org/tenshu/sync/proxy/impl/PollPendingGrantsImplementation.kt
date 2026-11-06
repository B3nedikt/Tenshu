package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.RealmRegistry
import org.tenshu.sync.proxy.SharedRealmGrantManager

/**
 * Grantee-side `PollPendingGrants` (GR-6): wraps [SharedRealmGrantManager.pollPendingGrants].
 * Sweeps grantable contacts' grant addresses, stores any picked-up grant as a
 * quarantined `PendingGrant`, and returns the full pending set.
 */
internal class PollPendingGrantsImplementation(
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
        val pending = SharedRealmGrantManager(
            executionContext = executionContext,
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            relay = relay,
            realmRegistry = realmRegistry,
            grantedRelayFactory = grantedRelayFactory,
            nowMs = nowMs,
        ).pollPendingGrants()

        return SimpleTList(pending) as T
    }
}
