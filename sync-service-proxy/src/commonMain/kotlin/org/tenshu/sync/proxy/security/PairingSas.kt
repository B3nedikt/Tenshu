package org.tenshu.sync.proxy.security

import org.tenshu.core.common.util.hkdfSha256

/**
 * Derives the 6-digit pairing Short Authentication String (SAS) per
 * `docs/proposals/sync-pairing-flow-improvements.md` §3.2.
 *
 * ```
 * sasBytes = HKDF-SHA-256(
 *     ikm    = sharedSecret,
 *     salt   = PublicKeyA || PublicKeyB,
 *     info   = "tenshu/pairing/sas/v1",
 *     length = 4)
 * sas = (be32(sasBytes) >> 12) % 1_000_000
 * ```
 *
 * Both sides of the handshake derive this independently from the same
 * inputs. A MITM substituting their own ephemeral key produces a
 * different shared secret on one side, so the SAS shown on host vs
 * requester diverges and the user catches the substitution by
 * comparing the two screens.
 *
 * The output is a 6-character zero-padded decimal string, e.g.
 * `"482913"`, ready for display.
 */
fun derivePairingSas(
    sharedSecret: ByteArray,
    publicKeyA: ByteArray,
    publicKeyB: ByteArray,
): String = sasDigits(sharedSecret, publicKeyA, publicKeyB, info = SAS_INFO)

/**
 * The **join flow's** SAS (v2): [derivePairingSas]'s construction, with the joiner's identity in
 * the transcript.
 *
 * ```
 * info = "tenshu/pairing/sas/v2"
 *        || lp(joinerSigningPublicKey)      // Ed25519, asset A4
 *        || lp(utf8(joinerDeviceId))
 *        || lp(utf8(joinerDeviceName))
 * sasBytes = HKDF-SHA-256(ikm = sharedSecret, salt = hostPublicKey || joinerPublicKey, info, 4)
 * lp(x) = be32(x.size) || x
 * ```
 *
 * ⚠⚠ **Why** (threat model T-P-14, gap 1): v1 covers the two X25519 keys only. The joiner's
 * signing key, `deviceId` and name reach the host through the relay with nothing authenticating
 * them, so a relay that served the genuine ECDH key with **its own signing key from the first
 * poll** got a matching code — and the host delegated admission to that key. With them in the
 * transcript, any change the relay makes to any of the four fields changes the host's code and
 * not the joiner's.
 *
 * Both sides compute it from the values they already hold: the joiner from its own key, id and
 * the name it posts (`InitiatePairingImplementation`), the host from the request it pins
 * (`GetPendingPairingRequestsImplementation`).
 *
 * ⚠⚠ **Lockstep, no fallback** (maintainer decision, 2026-10-03). There is no version anywhere —
 * not in the QR, not in the request — and the host never computes the v1 code for a join request:
 * a version the relay carries is a downgrade lever, and one in the QR informs one direction only.
 * A v1 device paired against a v2 device therefore shows a different code, and the screens tell
 * the user to update both.
 *
 * The **offer flow keeps v1** ([derivePairingSas]) on purpose: there the offerer's signing key and
 * `deviceId` ride the scanned QR, never the relay, so they are authenticated by the scan itself
 * and the v1 transcript already covers everything the relay can touch.
 */
fun deriveJoinPairingSas(
    sharedSecret: ByteArray,
    hostPublicKey: ByteArray,
    joinerPublicKey: ByteArray,
    joinerSigningPublicKey: ByteArray,
    joinerDeviceId: String,
    joinerDeviceName: String,
): String = sasDigits(
    sharedSecret = sharedSecret,
    publicKeyA = hostPublicKey,
    publicKeyB = joinerPublicKey,
    info = JOIN_SAS_INFO +
        lengthPrefixed(joinerSigningPublicKey) +
        lengthPrefixed(joinerDeviceId.encodeToByteArray()) +
        lengthPrefixed(joinerDeviceName.encodeToByteArray()),
)

private fun lengthPrefixed(bytes: ByteArray): ByteArray {
    val n = bytes.size
    return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + bytes
}

private fun sasDigits(
    sharedSecret: ByteArray,
    publicKeyA: ByteArray,
    publicKeyB: ByteArray,
    info: ByteArray,
): String {
    val salt = ByteArray(publicKeyA.size + publicKeyB.size).also {
        publicKeyA.copyInto(it, 0)
        publicKeyB.copyInto(it, publicKeyA.size)
    }
    val sasBytes = hkdfSha256(
        ikm = sharedSecret,
        salt = salt,
        info = info,
        length = 4,
    )
    // Big-endian 32-bit read, top 20 bits → 6-digit decimal
    val be32 = ((sasBytes[0].toInt() and 0xFF) shl 24) or
            ((sasBytes[1].toInt() and 0xFF) shl 16) or
            ((sasBytes[2].toInt() and 0xFF) shl 8) or
            (sasBytes[3].toInt() and 0xFF)
    val truncated = (be32 ushr 12) % 1_000_000
    return truncated.toString().padStart(6, '0')
}

private val SAS_INFO: ByteArray = "tenshu/pairing/sas/v1".encodeToByteArray()
private val JOIN_SAS_INFO: ByteArray = "tenshu/pairing/sas/v2".encodeToByteArray()
