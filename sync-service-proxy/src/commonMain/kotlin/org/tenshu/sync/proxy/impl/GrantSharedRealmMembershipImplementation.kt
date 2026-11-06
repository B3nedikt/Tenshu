package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.sync.GrantSharedRealmMembership
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.RealmRegistry
import org.tenshu.sync.proxy.SharedRealmGrantManager

/**
 * Owner-side `GrantSharedRealmMembership` (GR-3): wraps [SharedRealmGrantManager.grant].
 * Returns the minted member-authority public key (hex) for later removal.
 */
internal class GrantSharedRealmMembershipImplementation(
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
        val realmId = (params?.get(GrantSharedRealmMembership.Params.REALM_ID) as? TString)?.get()
            ?: error("GrantSharedRealmMembership requires realmId")
        val personId = (params?.get(GrantSharedRealmMembership.Params.PERSON_ID) as? TString)?.get()
            ?: error("GrantSharedRealmMembership requires personId")
        val label = (params?.get(GrantSharedRealmMembership.Params.LABEL) as? TString)?.get() ?: ""

        val authorityPublicKeyHex = SharedRealmGrantManager(
            executionContext = executionContext,
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            relay = relay,
            realmRegistry = realmRegistry,
            grantedRelayFactory = grantedRelayFactory,
            nowMs = nowMs,
        ).grant(realmId, personId, label)

        return authorityPublicKeyHex.toTString(executionContext.context) as T
    }
}
