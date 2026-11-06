package org.tenshu.sync.proxy.impl

import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.sync.proxy.security.offerResponseMessage
import org.tenshu.sync.common.SyncRelay
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.util.PairingUri
import org.tenshu.core.common.util.ParsedPairingUri
import org.tenshu.core.common.util.sha256
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.PairingOfferApprovalPrompt
import org.tenshu.sync.RespondToPairingOffer
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.proxy.security.derivePairingSas

/**
 * Responder op (an existing realm device, `sync-pairing-inverted-direction.md` §3.3 step 2-3):
 * parse the scanned `tenshu://offer?…`, mint an ephemeral X25519 keypair, derive the shared
 * secret + SAS, stash the responder session keyed by `SHA-256(secret)`, and return the approval
 * prompt (device name + SAS). privA + the scanned offerer identity stay proxy-side.
 */
internal class RespondToPairingOfferImplementation(
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val relay: SyncRelay,
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
        val scannedUri = (params?.get(RespondToPairingOffer.Params.SCANNED_URI) as TString).get()

        val offer = when (val parsed = PairingUri.parse(scannedUri)) {
            is ParsedPairingUri.Offer -> parsed
            else -> throw TenshuException(
                GenericError.create(ctx, "Not a Tenshu offer pairing code"),
            )
        }

        val ephemeralKeyPair = cryptoManager.generateKeyPair()
        val sharedSecret = cryptoManager.calculateSharedSecret(
            ephemeralKeyPair.privateKey, offer.offererPublicKey,
        )
        // SAS salt order (responderPublicKeyA, offererPublicKeyB) — the offerer derives the
        // identical value in GetOfferStatus, so the two screens match.
        val sas = derivePairingSas(
            sharedSecret = sharedSecret,
            publicKeyA = ephemeralKeyPair.publicKey,
            publicKeyB = offer.offererPublicKey,
        )
        val pairingSecretHash = sha256(offer.pairingSecret).toHexString()

        syncManager.startResponderSession(
            pairingSecretHash = pairingSecretHash,
            ephemeralKeyPair = ephemeralKeyPair,
            offererPublicKey = offer.offererPublicKey,
            offererSigningKey = offer.offererSigningKey,
            offererDeviceId = offer.offererDeviceId,
            deviceName = offer.deviceName,
        )

        // A2: announce the ephemeral key BEFORE the user decides, so the offerer can derive and
        // show the same SAS while this device's approval prompt is on screen. Signed exactly like
        // the terminal statuses — the relay refuses an unsigned announce, which is what stops a
        // passive QR observer planting a key on the offerer's screen.
        //
        // ⚠⚠ NON-FATAL by construction, and this is a rollout requirement rather than caution: an
        // un-upgraded relay 400s on `status: "scanned"`, and a responder that treated that as an
        // error would refuse to pair at all against a relay that works perfectly well for the
        // documented post-hoc flow. Degrade to today's behaviour; never block.
        val ownerId = syncManager.currentOwnerId
        if (ownerId != null) {
            val announceResult = runCatchingCancellable {
                relay.postOfferResponse(
                    ownerId = ownerId,
                    responderDeviceId = syncManager.currentDeviceId,
                    pairingSecretHash = pairingSecretHash,
                    status = "scanned",
                    publicKeyA = ephemeralKeyPair.publicKey,
                    payload = null,
                    hostSignature = cryptoManager.signEd25519(
                        privateKey = syncManager.getDeviceSigningKeyPair().privateKey,
                        message = offerResponseMessage(
                            "scanned",
                            pairingSecretHash,
                            ephemeralKeyPair.publicKey,
                            payload = null,
                        ),
                    ),
                )
            }
            // Reported, never thrown. A silent failure here would look identical to an old relay
            // and make "why is there no code on the other screen?" undiagnosable.
            announceResult.onFailure {
                println("RespondToPairingOffer -> scanned announce failed (degrading to post-hoc SAS): $it")
            }
        }

        return PairingOfferApprovalPrompt.create(
            context = ctx,
            pairingSecretHash = pairingSecretHash,
            deviceName = offer.deviceName,
            sas = sas,
        ) as T
    }
}
