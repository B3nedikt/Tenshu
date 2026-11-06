package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.RemoveSharedRealmMember
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.RealmRegistry
import org.tenshu.sync.proxy.SharedRealmGrantManager

/**
 * Owner-side `RemoveSharedRealmMember` (GR-3/GR-6, revoke-only): wraps
 * [SharedRealmGrantManager.removeMember], which resolves the member-authority public
 * key from the owner-side `GrantedMembership` roster given the in-realm `memberId`.
 */
internal class RemoveSharedRealmMemberImplementation(
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
        val realmId = (params?.get(RemoveSharedRealmMember.Params.REALM_ID) as? TString)?.get()
            ?: error("RemoveSharedRealmMember requires realmId")
        val memberId =
            (params?.get(RemoveSharedRealmMember.Params.MEMBER_ID) as? TString)?.get()
                ?: error("RemoveSharedRealmMember requires memberId")

        SharedRealmGrantManager(
            executionContext = executionContext,
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            relay = relay,
            realmRegistry = realmRegistry,
            grantedRelayFactory = grantedRelayFactory,
            nowMs = nowMs,
        ).removeMember(realmId, memberId)

        return null as T
    }
}
