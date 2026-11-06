package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
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
import org.tenshu.sync.OfferHubPairing
import org.tenshu.sync.OfferPairingSession
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.proxy.security.PairingRendezvous
import org.tenshu.sync.proxy.security.hubOfferMessage

/**
 * Hub-side arming op for headless pairing (hub proposal §11.3, H2). The hub's printed
 * `tenshu://hub?…` label is its own provisioned identity — this op validates the label
 * against the running proxy (device id + static Ed25519 device key), mints a **fresh
 * ephemeral X25519 keypair**, signs `{ephemeralPub, deviceId, deviceName}` with the static
 * key, POSTs the signed offer to `/sync/pairing/hub-offer` keyed by `SHA-256(secret)`, and
 * stashes the STANDARD offer session — from here the flow is identical to the on-screen
 * offerer role ([GetOfferStatusImplementation] polls, decrypts, installs keys).
 *
 * The session (and the responder's `offer-response` return leg) is keyed by the
 * **session-scoped hash** `SHA-256(secret || ephemeralPub)`, not `SHA-256(secret)`: the
 * printed secret is long-lived while the `offer-response` mailbox hash is single-use
 * (24 h consumed retention), so re-arming after a denial/timeout must land on a fresh
 * return-leg address. Both sides derive it independently (the phone learns
 * `ephemeralPub` from the verified offer).
 */
internal class OfferHubPairingImplementation(
    private val relay: org.tenshu.sync.common.SyncRelay,
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
        val printedUri = (params?.get(OfferHubPairing.Params.PRINTED_URI) as TString).get()
        val requestedDeviceName = (params[OfferHubPairing.Params.DEVICE_NAME] as TString).get()

        val label = when (val parsed = PairingUri.parse(printedUri)) {
            is ParsedPairingUri.Hub -> parsed
            else -> throw TenshuException(
                GenericError.create(ctx, "Not a Tenshu hub pairing label"),
            )
        }
        // The label must be THIS device's provisioned identity — a mismatch means the
        // data dir and the printed material have diverged (e.g. wiped state under a
        // still-taped-on label), which must fail loudly, not pair confusingly.
        if (label.hubDeviceId != syncManager.currentDeviceId) {
            throw TenshuException(
                GenericError.create(
                    ctx,
                    "Printed label device id does not match this device " +
                        "(label ${label.hubDeviceId}, device ${syncManager.currentDeviceId})",
                ),
            )
        }
        val signingKeyPair = syncManager.getDeviceSigningKeyPair()
        if (!label.hubVerifyKey.contentEquals(signingKeyPair.publicKey)) {
            throw TenshuException(
                GenericError.create(ctx, "Printed label verify key does not match this device's signing key"),
            )
        }

        val ephemeralKeyPair = cryptoManager.generateKeyPair()
        // Fit the name to the relay's sealed-name cap before the signature covers it, so the
        // signed, sealed and session copies are one string.
        val deviceName = PairingRendezvous.fitHubDeviceName(
            ephemeralPublicKey = ephemeralKeyPair.publicKey,
            deviceId = label.hubDeviceId,
            deviceName = requestedDeviceName,
        )
        val signature = cryptoManager.signEd25519(
            privateKey = signingKeyPair.privateKey,
            message = hubOfferMessage(
                ephemeralPublicKey = ephemeralKeyPair.publicKey,
                deviceId = label.hubDeviceId,
                deviceName = deviceName,
            ),
        )
        val posted = relay.postHubOffer(
            // Domain-separated (T-P-17); the phone derives the same from the label's secret.
            pairingSecretHash = PairingRendezvous.hubOfferHash(label.pairingSecret),
            ephemeralPublicKey = ephemeralKeyPair.publicKey,
            deviceId = label.hubDeviceId,
            // F7: the name is sealed under a key derived from the PRINTED secret, which the relay
            // never sees (it holds `SHA-256(secret)` only). The signature above still covers the
            // clear name; the phone unseals and then verifies.
            sealedDeviceName = PairingRendezvous.sealHubDeviceName(
                crypto = cryptoManager,
                printedSecret = label.pairingSecret,
                ephemeralPublicKey = ephemeralKeyPair.publicKey,
                deviceId = label.hubDeviceId,
                deviceName = deviceName,
            ),
            signature = signature,
        )
        if (posted is TResult.Failure<*>) throw TenshuException(posted.error)

        val responseHash = sha256(label.pairingSecret + ephemeralKeyPair.publicKey).toHexString()
        syncManager.startOfferSession(responseHash, ephemeralKeyPair, deviceName)

        return OfferPairingSession.create(
            context = ctx,
            offerUri = printedUri,
            pairingSecretHash = responseHash,
        ) as T
    }
}
