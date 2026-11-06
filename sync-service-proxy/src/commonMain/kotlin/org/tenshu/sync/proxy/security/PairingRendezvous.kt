package org.tenshu.sync.proxy.security

import org.tenshu.core.common.PairingWireLimits
import org.tenshu.core.common.util.hkdfSha256
import org.tenshu.core.common.util.sha256
import org.tenshu.core.security.SyncCryptoManager

/**
 * What the **relay** sees of a pairing secret, and how a device name crosses it sealed
 * (`relay-metadata-minimisation.md` F7, threat model A6 / T-P-16 / T-P-17).
 *
 * ## Join flow (`tenshu://pair?…`)
 *
 * ```
 * pairingSecretHash = hex(SHA-256("tenshu/pairing/join/rendezvous/v1" || 0x00 || utf8(secret)))
 * nameKey           = HKDF-SHA-256(ikm = utf8(secret), salt = ∅,
 *                                  info = "tenshu/pairing/join/device-name/v1", L = 32)
 * sealedDeviceName  = AEAD(nameKey, pad(utf8(name)),
 *                         aad = lp(utf8(pairingSecretHash)) || lp(utf8(deviceId))
 *                               || lp(publicKey) || lp(signingPublicKey))
 * ```
 *
 * ⚠⚠ **The AAD binds every key the host will trust**, not just the device id. With `deviceId`
 * alone, a relay could repost a genuine sealed name under its *own* X25519 / Ed25519 keys: the
 * host would open it, and only the SAS would catch the swap — while the host's first-seen pin per
 * device id (T-P-14) let the forged copy mask the real request until the user rejected it.
 * Binding `publicKey ‖ signingPublicKey` makes the seal itself prove that the secret-holder
 * chose those keys, so a swapped-key copy fails to open and is dropped before it is pinned.
 *
 * The hash is the offer/hub flows' construction — SHA-256 of the secret, lower-case hex, 64
 * characters — with a domain prefix, so a join hash can never equal an offer or hub hash for
 * the same bytes, nor any other SHA-256 the platform publishes. It is the rendezvous key of
 * every join route: the request inbox, the response mailbox, its peek and its ack. The relay
 * never receives the secret itself, so it cannot derive [nameKey] and the name stays opaque to it.
 *
 * ## Hub flow (`tenshu://hub?…`)
 *
 * ```
 * hubOfferHash = hex(SHA-256("tenshu/pairing/hub/rendezvous/v1" || 0x00 || printedSecret))
 * ```
 *
 * The hub's printed secret reaches the relay only as [hubOfferHash] — the key of every
 * `/sync/pairing/hub-offer` post and fetch. It was a bare `SHA-256(secret)` until the hardening
 * follow-up to F7; the label carries the **secret**, not the hash, so a printed label keeps working
 * across the change and only the derivation moved (hub and phone must run the same build). ⚠ The
 * hash is still **stable** over the hub's lifetime — domain separation stops it equalling any other
 * hash of those bytes, it does not stop the relay linking one hub's arms (T-P-17, accepted). Its name is sealed the same way under
 * `info = "tenshu/pairing/hub/device-name/v1"`, with the offer's ephemeral key and the hub's
 * device id as AAD, so a sealed name cannot be lifted onto another arm or another hub. That AAD
 * already covers every key the phone trusts from the offer: the ephemeral key is the only key
 * the relay carries — the hub's verify key comes from the printed label, never from the relay,
 * and the signature it checks covers the ephemeral key too.
 *
 * ⚠ **AEAD** is [SyncCryptoManager.encryptWithAad] — XChaCha20-Poly1305 with a random nonce in
 * production. Nothing new is invented here: HKDF, SHA-256 and the AEAD are the primitives the
 * platform already uses for the EPC address, the SAS and the sync envelopes.
 *
 * ⚠ **Padding.** The plaintext is `be16(len) || name || 0…` to a multiple of [NAME_PAD_BLOCK]
 * bytes, so the ciphertext length says which 64-byte bucket the name falls in, not its length.
 * ⚠ **Length.** The relay refuses a sealed name over [PairingWireLimits.MAX_SEALED_DEVICE_NAME_BYTES],
 * and the AAD rides inside that ciphertext, so the name's budget depends on the inputs (≈766 UTF-8
 * bytes for a UUID device id on the join flow). Callers fit the name first
 * ([fitJoinDeviceName] / [fitHubDeviceName]); the seal functions refuse an unfitted one.
 *
 * ⚠ The seal is **confidentiality** against the relay. Integrity of the name the approver shows
 * is still the SAS's job (join: `deriveJoinPairingSas` covers the name) and the printed-key
 * signature's (hub: `hubOfferMessage` covers it); the AEAD tag only makes tampering *fail*
 * rather than show garbage.
 */
object PairingRendezvous {

    /**
     * Rendezvous key of the hub offer (`/sync/pairing/hub-offer`), derived from the label's printed
     * secret; see the class KDoc. Never `SHA-256(secret)` bare (T-P-17).
     */
    fun hubOfferHash(printedSecret: ByteArray): String =
        sha256(HUB_HASH_DOMAIN + byteArrayOf(0) + printedSecret).toHexString()

    /** Rendezvous key of every join-flow relay route. Never the raw secret (F7). */
    fun joinSecretHash(pairingSecret: String): String =
        sha256(JOIN_HASH_DOMAIN + byteArrayOf(0) + pairingSecret.encodeToByteArray()).toHexString()

    /**
     * [deviceName] cut to what [sealJoinDeviceName] can seal under the relay's
     * [PairingWireLimits.MAX_SEALED_DEVICE_NAME_BYTES] for these exact AAD inputs.
     *
     * ⚠⚠ Call it **before** anything else consumes the name — the SAS transcript, the stored
     * `Device` name — so every copy is the same truncated string. The name is a display label
     * (the platform's device name), so a shortened label is a better outcome than an opaque relay
     * `400` that strands the pairing.
     */
    fun fitJoinDeviceName(
        pairingSecret: String,
        deviceId: String,
        publicKey: ByteArray,
        signingPublicKey: ByteArray,
        deviceName: String,
    ): String = truncateUtf8(
        deviceName,
        PairingWireLimits.maxSealedDeviceNameBytes(joinAad(pairingSecret, deviceId, publicKey, signingPublicKey).size),
    )

    /** [fitJoinDeviceName]'s hub twin: fit before signing, sealing and the offer session. */
    fun fitHubDeviceName(ephemeralPublicKey: ByteArray, deviceId: String, deviceName: String): String =
        truncateUtf8(deviceName, PairingWireLimits.maxSealedDeviceNameBytes(hubAad(ephemeralPublicKey, deviceId).size))

    /**
     * The longest prefix of [value] whose UTF-8 encoding is at most [maxBytes], cut on a
     * code-point boundary (never mid-sequence, so the result is valid UTF-8 and round-trips).
     * ⚠ A multi-code-point grapheme (ZWJ emoji, a base + combining mark) may still be split; for
     * a display label that is accepted.
     */
    internal fun truncateUtf8(value: String, maxBytes: Int): String {
        val bytes = value.encodeToByteArray()
        if (bytes.size <= maxBytes) return value
        var end = maxBytes
        // Back off continuation bytes (10xxxxxx) so the cut lands on a sequence start.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOfRange(0, end).decodeToString()
    }

    /**
     * Seal the joiner's [deviceName] so only a holder of [pairingSecret] — the host — reads it.
     * ⚠ [deviceName] must already be [fitJoinDeviceName]'d; a longer one is refused here rather
     * than sent for the relay to `400`.
     */
    fun sealJoinDeviceName(
        crypto: SyncCryptoManager,
        pairingSecret: String,
        deviceId: String,
        publicKey: ByteArray,
        signingPublicKey: ByteArray,
        deviceName: String,
    ): ByteArray {
        val aad = joinAad(pairingSecret, deviceId, publicKey, signingPublicKey)
        return crypto.encryptWithAad(
            plaintext = pad(deviceName, PairingWireLimits.maxSealedDeviceNameBytes(aad.size)),
            key = joinNameKey(pairingSecret),
            associatedData = aad,
        )
    }

    /**
     * Inverse of [sealJoinDeviceName]. Throws when the blob was sealed under another secret, for
     * another `deviceId`, for other keys than the ones it arrived with, or tampered with —
     * callers treat that as an unverifiable request.
     */
    fun openJoinDeviceName(
        crypto: SyncCryptoManager,
        pairingSecret: String,
        deviceId: String,
        publicKey: ByteArray,
        signingPublicKey: ByteArray,
        sealedDeviceName: ByteArray,
    ): String = unpad(
        crypto.decryptWithAad(
            ciphertext = sealedDeviceName,
            key = joinNameKey(pairingSecret),
            associatedData = joinAad(pairingSecret, deviceId, publicKey, signingPublicKey),
        ),
    )

    /** Canonical, length-prefixed join AAD; see the class KDoc. */
    private fun joinAad(
        pairingSecret: String,
        deviceId: String,
        publicKey: ByteArray,
        signingPublicKey: ByteArray,
    ): ByteArray =
        lengthPrefixed(joinSecretHash(pairingSecret).encodeToByteArray()) +
            lengthPrefixed(deviceId.encodeToByteArray()) +
            lengthPrefixed(publicKey) +
            lengthPrefixed(signingPublicKey)

    /**
     * Seal a hub's [deviceName] for the phone that scanned its printed label. ⚠ [deviceName] must
     * already be [fitHubDeviceName]'d.
     */
    fun sealHubDeviceName(
        crypto: SyncCryptoManager,
        printedSecret: ByteArray,
        ephemeralPublicKey: ByteArray,
        deviceId: String,
        deviceName: String,
    ): ByteArray {
        val aad = hubAad(ephemeralPublicKey, deviceId)
        return crypto.encryptWithAad(
            plaintext = pad(deviceName, PairingWireLimits.maxSealedDeviceNameBytes(aad.size)),
            key = hubNameKey(printedSecret),
            associatedData = aad,
        )
    }

    /** Inverse of [sealHubDeviceName]. Throws on any mismatch or tamper. */
    fun openHubDeviceName(
        crypto: SyncCryptoManager,
        printedSecret: ByteArray,
        ephemeralPublicKey: ByteArray,
        deviceId: String,
        sealedDeviceName: ByteArray,
    ): String = unpad(
        crypto.decryptWithAad(
            ciphertext = sealedDeviceName,
            key = hubNameKey(printedSecret),
            associatedData = hubAad(ephemeralPublicKey, deviceId),
        ),
    )

    private fun hubAad(ephemeralPublicKey: ByteArray, deviceId: String): ByteArray =
        lengthPrefixed(ephemeralPublicKey) + lengthPrefixed(deviceId.encodeToByteArray())

    /** Plaintext bucket size; see the class KDoc. One copy with the relay's cap. */
    const val NAME_PAD_BLOCK = PairingWireLimits.SEALED_DEVICE_NAME_PAD_BLOCK

    private fun joinNameKey(pairingSecret: String): ByteArray =
        hkdfSha256(ikm = pairingSecret.encodeToByteArray(), salt = ByteArray(0), info = JOIN_NAME_INFO, length = 32)

    private fun hubNameKey(printedSecret: ByteArray): ByteArray =
        hkdfSha256(ikm = printedSecret, salt = ByteArray(0), info = HUB_NAME_INFO, length = 32)

    private fun pad(name: String, maxNameBytes: Int): ByteArray {
        val bytes = name.encodeToByteArray()
        require(bytes.size <= maxNameBytes) {
            "device name is ${bytes.size} UTF-8 bytes; at most $maxNameBytes fit the relay's sealed-name cap " +
                "(fit it with PairingRendezvous.fitJoinDeviceName / fitHubDeviceName first)"
        }
        val framed = PairingWireLimits.SEALED_DEVICE_NAME_LENGTH_PREFIX + bytes.size
        val padded = ((framed + NAME_PAD_BLOCK - 1) / NAME_PAD_BLOCK) * NAME_PAD_BLOCK
        val out = ByteArray(padded)
        out[0] = (bytes.size ushr 8).toByte()
        out[1] = bytes.size.toByte()
        bytes.copyInto(out, destinationOffset = 2)
        return out
    }

    private fun unpad(padded: ByteArray): String {
        require(padded.size >= 2) { "sealed device name too short" }
        val n = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        require(2 + n <= padded.size) { "sealed device name length out of range" }
        return padded.copyOfRange(2, 2 + n).decodeToString()
    }

    private fun lengthPrefixed(bytes: ByteArray): ByteArray {
        val n = bytes.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + bytes
    }

    private val JOIN_HASH_DOMAIN = "tenshu/pairing/join/rendezvous/v1".encodeToByteArray()
    private val HUB_HASH_DOMAIN = "tenshu/pairing/hub/rendezvous/v1".encodeToByteArray()
    private val JOIN_NAME_INFO = "tenshu/pairing/join/device-name/v1".encodeToByteArray()
    private val HUB_NAME_INFO = "tenshu/pairing/hub/device-name/v1".encodeToByteArray()
}
