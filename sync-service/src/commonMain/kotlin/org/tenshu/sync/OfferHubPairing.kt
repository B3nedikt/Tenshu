package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Hub-side arming op for **headless pairing** (hub proposal §11, H2): the printed-static-key
 * offer variant. [printedUri] is this device's own provisioned `tenshu://hub?…` label — the
 * proxy validates it against its provisioned identity (device id + static Ed25519 key), mints
 * a FRESH ephemeral X25519 keypair, signs `{ephemeralPub, deviceId, deviceName}` with the
 * static key, POSTs the signed offer to the relay's `/sync/pairing/hub-offer` mailbox keyed
 * by `SHA-256(secret)`, and stashes the standard offer session under the **session-scoped
 * response hash** `SHA-256(secret || ephemeralPub)` — the returned
 * [OfferPairingSession.pairingSecretHash] the caller polls via [GetOfferStatus], exactly like
 * the on-screen offer flow. (Session-scoping keeps the single-use `offer-response` mailbox
 * semantics intact across re-arms of the long-lived printed secret.)
 */
suspend fun SyncService.offerHubPairing(
    printedUri: String,
    deviceName: String,
): TResult<OfferPairingSession> =
    findOperation<OfferHubPairing>(OfferHubPairing.ID)
        .offerHubPairing(printedUri, deviceName)
        .asTaskInteractor<OfferPairingSession>()
        .execute()

interface OfferHubPairing : Operation {

    fun offerHubPairing(printedUri: String, deviceName: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/offerHubPairing"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.PRINTED_URI)
                string(Params.DEVICE_NAME)
            },
            returnType = OfferPairingSession.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )

        /**
         * Well-known message prefix on failure when the relay predates the
         * `/sync/pairing/hub-offer` endpoint (404) — callers surface it as
         * "relay does not support hub pairing yet".
         */
        const val ERROR_RELAY_UNSUPPORTED = "HUB_PAIRING_UNSUPPORTED_BY_RELAY"
    }

    object Params {
        const val PRINTED_URI = "$ID/printedUri"
        const val DEVICE_NAME = "$ID/deviceName"
    }
}
