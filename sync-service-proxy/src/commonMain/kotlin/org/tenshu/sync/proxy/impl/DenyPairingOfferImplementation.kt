package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTBoolean
import org.tenshu.sync.DenyPairingOffer
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.security.offerResponseMessage

/**
 * Responder deny (`sync-pairing-inverted-direction.md` §3.3): POST a signed `status:"denied"`
 * offer-response (no payload, no registration) so the offerer's poll terminates, then drop the
 * responder session. Idempotent — if no session (already resolved) just returns success.
 */
internal class DenyPairingOfferImplementation(
    private val relay: SyncRelay,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val ctx = executionContext.context
        val hash = (params?.get(DenyPairingOffer.Params.PAIRING_SECRET_HASH) as TString).get()
        val session = syncManager.getResponderSession(hash)
            ?: return true.toTBoolean(ctx) as T

        val ownerId = syncManager.currentOwnerId
            ?: throw TenshuException(GenericError.create(ctx, "No account is set up on this device"))
        val publicKeyA = session.ephemeralKeyPair.publicKey
        val hostSignature = cryptoManager.signEd25519(
            privateKey = syncManager.getDeviceSigningKeyPair().privateKey,
            message = offerResponseMessage("denied", hash, publicKeyA, payload = null),
        )
        relay.postOfferResponse(
            ownerId = ownerId,
            responderDeviceId = syncManager.currentDeviceId,
            pairingSecretHash = hash,
            status = "denied",
            publicKeyA = publicKeyA,
            payload = null,
            hostSignature = hostSignature,
        )
        syncManager.clearResponderSession(hash)
        return true.toTBoolean(ctx) as T
    }
}
