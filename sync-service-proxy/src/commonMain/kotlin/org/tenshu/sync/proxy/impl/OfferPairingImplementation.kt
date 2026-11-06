package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.util.nextSecureRandomBytes
import org.tenshu.core.common.util.sha256
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.common.util.PairingUri
import org.tenshu.sync.OfferPairing
import org.tenshu.sync.OfferPairingSession
import org.tenshu.sync.common.SyncManager

/**
 * Offerer op (the new device): mint an ephemeral X25519 keypair + a 128-bit pairing secret,
 * stash the offer session keyed by `SHA-256(secret)` (hex), and return the `tenshu://offer?…`
 * QR/URI + that hash. The QR carries the offerer's device signing key + id so the responder
 * can register it (`sync-pairing-inverted-direction.md` §3.2 P3/P4). privB stays proxy-side.
 */
internal class OfferPairingImplementation(
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
        val deviceName = (params?.get(OfferPairing.Params.DEVICE_NAME) as TString).get()

        val ephemeralKeyPair = cryptoManager.generateKeyPair()
        val secret = nextSecureRandomBytes(SECRET_BYTES)
        val pairingSecretHash = sha256(secret).toHexString()

        val offerUri = PairingUri.offerUri(
            offererPublicKey = ephemeralKeyPair.publicKey,
            offererSigningKey = syncManager.getDeviceSigningKeyPair().publicKey,
            offererDeviceId = syncManager.currentDeviceId,
            pairingSecret = secret,
            deviceName = deviceName,
        )
        syncManager.startOfferSession(pairingSecretHash, ephemeralKeyPair, deviceName)

        return OfferPairingSession.create(
            context = executionContext.context,
            offerUri = offerUri,
            pairingSecretHash = pairingSecretHash,
        ) as T
    }

    private companion object {
        const val SECRET_BYTES = 16 // 128-bit CSPRNG secret (improvements §7)
    }
}
