package org.tenshu.sync.relay.auth

import org.tenshu.core.common.security.Ed25519Backend

/**
 * Master-identity Ed25519 signature verification helper. Used by:
 *
 * - `POST /sync/realm` — the first device sends a `masterSelfSignature` over
 *   `realmBootstrapMessage` (tagged + length-prefixed, `SignedMessageEncoding.kt`), signed by
 *   the master identity. The relay verifies against the same `masterPublicKey` carried
 *   in the body (TOFU on first contact for that ownerId).
 *
 * - `POST /sync/pairing/response` (after the master-delegation extension lands) —
 *   the host posts a `masterDelegation` over `pairingDelegationMessage`,
 *   signed by the master identity; verified against the realm's stored `masterPublicKey`.
 *
 * - `POST /sync/recovery-claim` — the recovering device proves possession of the master
 *   identity by signing a server-issued challenge.
 *
 * Verification dispatches to an injected [Ed25519Backend] — production wires
 * `BouncyCastleEd25519Backend`, tests can substitute a stub-compatible fake.
 */
object MasterDelegationVerifier {

    /**
     * Returns true if [signature] is a valid master signature over [message] under
     * [masterPublicKey]. Delegates to [ed25519] for the underlying primitive.
     */
    fun verify(
        masterPublicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
        ed25519: Ed25519Backend
    ): Boolean = ed25519.verify(masterPublicKey, message, signature)

    // ⚠⚠ The canonical bytes live in `:sync-service-common` (`SignedMessageEncoding.kt`) — one
    // tagged, length-prefixed builder per message, shared with every client signer. These are thin
    // aliases so relay call sites read as before; never re-spell the bytes here (B1, 2026-10-28).

    /** `POST /sync/realm` master self-signature. */
    fun realmBootstrapMessage(
        ownerId: String,
        masterPublicKey: ByteArray,
        firstDevicePublicKey: ByteArray,
        firstDeviceId: String
    ): ByteArray = org.tenshu.sync.common.realmBootstrapMessage(ownerId, masterPublicKey, firstDevicePublicKey, firstDeviceId)

    /** `POST /sync/recovery-claim` master signature over the relay-issued challenge. */
    fun recoveryClaimMessage(
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        challenge: ByteArray
    ): ByteArray = org.tenshu.sync.common.recoveryClaimMessage(newDevicePublicKey, newDeviceId, challenge)

    /** `POST /sync/pairing/response` and `/sync/admit-device` new-device delegation. */
    fun pairingResponseMessage(
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        epoch: Long
    ): ByteArray = org.tenshu.sync.common.pairingDelegationMessage(newDevicePublicKey, newDeviceId, epoch)

    /** `POST /sync/pairing/offer-response` responder A4 signature. */
    fun offerResponseMessage(
        status: String,
        pairingSecretHash: String,
        publicKeyA: ByteArray,
        payload: ByteArray?
    ): ByteArray = org.tenshu.sync.common.offerResponseMessage(status, pairingSecretHash, publicKeyA, payload)
}
