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
import org.tenshu.sync.GetOfferStatus
import org.tenshu.sync.OfferStatus
import org.tenshu.sync.PairingThisDeviceRemovedError
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.DEVICE_REMOVED_REJECTION_MARKER
import org.tenshu.sync.proxy.security.derivePairingSas

/**
 * Offerer poll (`sync-pairing-inverted-direction.md` §3.3 step 4): peek the rendezvous by
 * `pairingSecretHash`. `pending` while empty; on `denied` drop the session; on `approved`
 * derive the SAS, install the keys via the shared [installReceivedPairingPayload], and return
 * `approved` + SAS. The relay registers the offerer's device on the very poll that serves the
 * approval (before the payload leaves), so its subsequent JWT-authed sync verifies; a `403`
 * carrying `device-removed` there means the device was removed between approval and fetch.
 */
internal class GetOfferStatusImplementation(
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
        val hash = (params?.get(GetOfferStatus.Params.PAIRING_SECRET_HASH) as TString).get()
        val session = syncManager.getOfferSession(hash)
            ?: throw TenshuException(GenericError.create(ctx, "No active offer session"))

        val status: OfferStatus = when (val fetch = relay.getOfferResponse(hash)) {
            is SyncRelay.OfferResponseFetch.Error -> {
                // ⚠ F2′ re-checked at delivery: THIS device was removed from the realm and the
                // relay will never admit it under this identity. Terminal, so typed — a generic
                // error here read as a transport blip and was polled on until the window closed.
                if (fetch.message.contains(DEVICE_REMOVED_REJECTION_MARKER)) {
                    syncManager.clearOfferSession(hash)
                    throw TenshuException(PairingThisDeviceRemovedError.create(ctx))
                }
                // A genuine transport failure — surface it rather than silently poll on
                // (mirrors PairingCompletion's Failure-vs-404 distinction).
                throw TenshuException(GenericError.create(ctx, fetch.message))
            }

            SyncRelay.OfferResponseFetch.NotFound ->
                OfferStatus.create(ctx, OfferStatus.PENDING)

            is SyncRelay.OfferResponseFetch.Found -> when (fetch.status) {
                "denied" -> {
                    syncManager.clearOfferSession(hash)
                    OfferStatus.create(ctx, OfferStatus.DENIED)
                }

                // A2: the responder has scanned and announced its ephemeral key, but has NOT
                // decided yet. Derive the SAS now so the offerer can show it while the approval
                // prompt is still on screen — the pre-approval comparison the offer flow
                // structurally could not have before, and PIN the key the lock below checks.
                "scanned" -> {
                    val sharedSecret = cryptoManager.calculateSharedSecret(
                        session.ephemeralKeyPair.privateKey, fetch.publicKeyA,
                    )
                    val sas = derivePairingSas(
                        sharedSecret = sharedSecret,
                        publicKeyA = fetch.publicKeyA,
                        publicKeyB = session.ephemeralKeyPair.publicKey,
                    )
                    syncManager.pinAnnouncedOfferKey(hash, fetch.publicKeyA)
                    OfferStatus.create(ctx, OfferStatus.SCANNED, sas)
                }

                "approved" -> {
                    val payload = fetch.payload
                        ?: throw TenshuException(GenericError.create(ctx, "Approved offer-response carried no payload"))
                    // ⚠⚠ THE key-equality lock (§2.3), and it must run BEFORE anything is
                    // decrypted or installed. If an announce was seen, the device that approves
                    // must be the device that scanned; a different `publicKeyA` means a third
                    // party posted the terminal response, and the sealed payload it carries is
                    // one we must not open. Rejecting here turns T-P-07 from detect-after-install
                    // into reject-before-install.
                    //
                    // ⚠ A null pin is "no announce was seen" — an old responder or an
                    // un-upgraded relay — NOT "any key is fine". In that case we fall through to
                    // the documented post-hoc posture rather than inventing a lock we cannot
                    // enforce, which is what makes A2 safe to ship before A3.
                    val pinned = session.announcedPublicKeyA
                    if (pinned != null && !pinned.contentEquals(fetch.publicKeyA)) {
                        syncManager.clearOfferSession(hash)
                        throw TenshuException(
                            GenericError.create(
                                ctx,
                                "The approval came from a different device than the one that " +
                                    "scanned this code. Nothing was installed — start over.",
                            ),
                        )
                    }
                    // SAS over (responderPublicKeyA, offererPublicKeyB) — same order the
                    // responder used (RespondToPairingOffer), so both screens match.
                    val sharedSecret = cryptoManager.calculateSharedSecret(
                        session.ephemeralKeyPair.privateKey, fetch.publicKeyA,
                    )
                    val sas = derivePairingSas(
                        sharedSecret = sharedSecret,
                        publicKeyA = fetch.publicKeyA,
                        publicKeyB = session.ephemeralKeyPair.publicKey,
                    )
                    installReceivedPairingPayload(
                        executionContext = executionContext,
                        syncManager = syncManager,
                        cryptoManager = cryptoManager,
                        relay = relay,
                        myEphemeralPrivateKey = session.ephemeralKeyPair.privateKey,
                        otherDevicePublicKey = fetch.publicKeyA,
                        ownerId = fetch.ownerId,
                        encryptedBlob = payload,
                        deviceName = session.deviceName,
                    )
                    syncManager.clearOfferSession(hash)
                    OfferStatus.create(ctx, OfferStatus.APPROVED, sas)
                }

                else -> throw TenshuException(
                    GenericError.create(ctx, "Unexpected offer-response status: ${fetch.status}"),
                )
            }
        }
        return status as T
    }
}
