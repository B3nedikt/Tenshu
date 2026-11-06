package org.tenshu.contact.proxy.impl

import org.tenshu.contact.PendingRedemptionInfo
import org.tenshu.contact.common.identityFingerprint
import org.tenshu.contact.proxy.ContactProxyComponents
import org.tenshu.core.Data
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.util.deriveContactSas

/**
 * `drive-manage-access-ux.md` §4.2 — the read surface over
 * `PendingRedemptionStore.all()` so the UI can resume `awaitInviteAck` after
 * an app restart. Projects each record down to (inviteId, redeemedAt,
 * peerFingerprint); `K_link` and the raw peer key never cross the boundary.
 */
internal class ListPendingRedemptionsImplementation(
    private val components: ContactProxyComponents,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        // SAS derivable as soon as the link is redeemed (own A13 + the
        // link-pinned inviter key) — "" while the realm is locked.
        val ownPublicKey = components.identityKeyPair()?.publicKey
        val rows = components.redemptionStore.all().map { redemption ->
            PendingRedemptionInfo.create(
                context = executionContext.context,
                inviteId = redemption.inviteId,
                redeemedAt = redemption.redeemedAtMs,
                peerFingerprint = identityFingerprint(redemption.peerA13PublicKey),
                sas = ownPublicKey
                    ?.let { deriveContactSas(it, redemption.peerA13PublicKey) }
                    .orEmpty(),
            )
        }
        return SimpleTList(rows) as T
    }
}
