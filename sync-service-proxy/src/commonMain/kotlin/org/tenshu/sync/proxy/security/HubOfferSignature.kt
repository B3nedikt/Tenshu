package org.tenshu.sync.proxy.security

/**
 * Canonical bytes the hub's **printed static Ed25519 key** signs over its relay-posted
 * ephemeral offer (hub proposal §11.3, H2). Built by the hub in `OfferHubPairing` and
 * verified by the phone in `RespondToHubPairing` — the relay never verifies (it doesn't
 * hold the printed key; the label in the user's hand is the trust anchor).
 *
 * Like [offerResponseMessage] (since B1), every variable-length field here is
 * 32-bit-BE length-prefixed: the same static key signs this for years across re-arms, so
 * the encoding must be unambiguous (no `deviceId`/`deviceName` boundary confusion), and
 * the domain prefix separates it from everything else an A4 device key signs (JWTs,
 * offer-responses, HLC tips, delegations).
 */
fun hubOfferMessage(
    ephemeralPublicKey: ByteArray,
    deviceId: String,
    deviceName: String,
): ByteArray {
    val idBytes = deviceId.encodeToByteArray()
    val nameBytes = deviceName.encodeToByteArray()
    return HUB_OFFER_DOMAIN.encodeToByteArray() +
        lengthPrefixed(ephemeralPublicKey) +
        lengthPrefixed(idBytes) +
        lengthPrefixed(nameBytes)
}

private const val HUB_OFFER_DOMAIN = "tenshu/pairing/hub-offer/v1"

private fun lengthPrefixed(bytes: ByteArray): ByteArray = byteArrayOf(
    (bytes.size ushr 24).toByte(),
    (bytes.size ushr 16).toByte(),
    (bytes.size ushr 8).toByte(),
    bytes.size.toByte(),
) + bytes
