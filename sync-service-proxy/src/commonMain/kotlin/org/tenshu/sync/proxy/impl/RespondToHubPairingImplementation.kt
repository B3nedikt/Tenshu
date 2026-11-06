package org.tenshu.sync.proxy.impl

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
import org.tenshu.sync.RelayRouteUnsupportedError
import org.tenshu.sync.PairingFailedError
import org.tenshu.sync.PairingFailure
import org.tenshu.sync.PairingOfferApprovalPrompt
import org.tenshu.sync.RespondToHubPairing
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.deviceIdMatchesKey
import org.tenshu.sync.proxy.security.PairingRendezvous
import org.tenshu.sync.proxy.security.hubOfferMessage

/**
 * Phone-side responder entry for headless hub pairing (hub proposal §11.3, H2): parse the
 * scanned printed label, fetch the hub's signed ephemeral offer from the relay, and
 * **verify its Ed25519 signature against the printed verify key** — in this flow the
 * printed key plays the anti-MITM role the QR-carried ephemeral key plays in the standard
 * offer flow (§11.4): the relay cannot substitute an ephemeral key that verifies against
 * what the user physically scanned. Verification failure is a hard error ("code doesn't
 * match this hub"); there is **no SAS** (`sas` returns empty) — the signature replaces it.
 *
 * On success the standard responder session is stashed under the session-scoped response
 * hash `SHA-256(secret || ephemeralPub)` and [ApprovePairingOfferImplementation] /
 * [DenyPairingOfferImplementation] run unchanged: the hub's static key doubles as its A4
 * device signing key, so the printed `d`/`g` fields feed the same registration delegation
 * the offer QR's `d`/`g` fields feed.
 */
internal class RespondToHubPairingImplementation(
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
        val scannedUri = (params?.get(RespondToHubPairing.Params.SCANNED_URI) as TString).get()

        val label = when (val parsed = PairingUri.parse(scannedUri)) {
            is ParsedPairingUri.Hub -> parsed
            else -> throw TenshuException(
                GenericError.create(ctx, "Not a Tenshu hub pairing label"),
            )
        }
        // Workstream B: a label's `d` is the id of its `g` key. One that is not is either tampered
        // with or printed before ids were derived — either way its printed identity does not verify,
        // the same typed hard error as a bad offer signature below (re-provisioning the hub prints a
        // label that does). Refused before touching the network.
        if (!deviceIdMatchesKey(label.hubDeviceId, label.hubVerifyKey)) {
            throw TenshuException(PairingFailedError.create(ctx, PairingFailure.VerifyFailed))
        }

        val offer = when (val fetch = relay.getHubOffer(PairingRendezvous.hubOfferHash(label.pairingSecret))) {
            is SyncRelay.HubOfferFetch.Found -> fetch
            SyncRelay.HubOfferFetch.NotFound -> throw TenshuException(
                PairingFailedError.create(ctx, PairingFailure.OfferNotFound),
            )
            SyncRelay.HubOfferFetch.RelayUnsupported -> throw TenshuException(
                RelayRouteUnsupportedError.create(ctx, "/sync/pairing/hub-offer"),
            )
            is SyncRelay.HubOfferFetch.Error -> throw TenshuException(
                GenericError.create(ctx, fetch.message),
            )
        }

        // The trust anchor: the fetched offer must (a) claim the printed device id and
        // (b) carry a signature by the printed static key over the canonical message.
        // Anything else — relay substitution, a stale offer for another hub under a
        // colliding hash, tampering — is the same hard error.
        //
        // F7: the name arrives sealed under a key derived from the printed secret. It is opened
        // first and then verified as part of the signed message, so a blob that does not open
        // (relay tamper, another arm's ciphertext) and a signature that does not verify are the
        // same hard error.
        val deviceName = if (offer.deviceId == label.hubDeviceId) {
            runCatching {
                PairingRendezvous.openHubDeviceName(
                    crypto = cryptoManager,
                    printedSecret = label.pairingSecret,
                    ephemeralPublicKey = offer.ephemeralPublicKey,
                    deviceId = offer.deviceId,
                    sealedDeviceName = offer.sealedDeviceName,
                )
            }.getOrNull()
        } else {
            null
        }
        val verified = deviceName != null &&
            cryptoManager.verifyEd25519(
                publicKey = label.hubVerifyKey,
                message = hubOfferMessage(
                    ephemeralPublicKey = offer.ephemeralPublicKey,
                    deviceId = offer.deviceId,
                    deviceName = deviceName,
                ),
                signature = offer.signature,
            )
        if (!verified || deviceName == null) {
            throw TenshuException(
                PairingFailedError.create(ctx, PairingFailure.VerifyFailed),
            )
        }

        val ephemeralKeyPair = cryptoManager.generateKeyPair()
        // Session-scoped return-leg address — see OfferHubPairingImplementation.
        val responseHash = sha256(label.pairingSecret + offer.ephemeralPublicKey).toHexString()

        syncManager.startResponderSession(
            pairingSecretHash = responseHash,
            ephemeralKeyPair = ephemeralKeyPair,
            offererPublicKey = offer.ephemeralPublicKey,
            offererSigningKey = label.hubVerifyKey,
            offererDeviceId = label.hubDeviceId,
            deviceName = deviceName,
        )

        // sas = "" by design: the printed-key signature verification replaces the SAS
        // ceremony a headless device cannot render (§11.4). The approval UI keys off the
        // hub branch, not this field — but an empty SAS also renders harmlessly.
        return PairingOfferApprovalPrompt.create(
            context = ctx,
            pairingSecretHash = responseHash,
            deviceName = deviceName,
            sas = "",
        ) as T
    }
}
