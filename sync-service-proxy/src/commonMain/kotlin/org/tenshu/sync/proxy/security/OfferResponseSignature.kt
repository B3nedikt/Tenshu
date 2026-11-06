package org.tenshu.sync.proxy.security

/**
 * Client-side name for the canonical bytes the responder's device key signs in an offer-response
 * (`sync-pairing-inverted-direction.md` §3.4). ⚠ The bytes live in `:sync-service-common`
 * (`org.tenshu.sync.common.offerResponseMessage`, tagged + length-prefixed), shared with the relay.
 */
fun offerResponseMessage(
    status: String,
    pairingSecretHash: String,
    publicKeyA: ByteArray,
    payload: ByteArray?,
): ByteArray = org.tenshu.sync.common.offerResponseMessage(status, pairingSecretHash, publicKeyA, payload)
