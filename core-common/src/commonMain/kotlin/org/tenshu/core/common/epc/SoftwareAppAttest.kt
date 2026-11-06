package org.tenshu.core.common.epc

import org.tenshu.core.security.SyncCryptoManager

/**
 * Cross-platform **reference** attestor/verifier for EPC measure 2 (Phase 4a of
 * `docs/proposals/ios-proxy-architecture.md`). It proves the attestation *flow*
 * — challenge → signed attestation → verify → key continuity — the way
 * `BootstrapSyncCryptoManager` proves the EPC handshake flow. It is **not** Apple
 * App Attest: there is no hardware root of trust, just an Ed25519 signature over
 * the proxy's challenge, standing in for the genuineness guarantee the real
 * cert-chain verifier provides (which lands with the iOS app, Phase 6).
 *
 * Wire form: `[2-byte big-endian publicKey length][attestation public key][signature]`
 * (length-prefixed so it works under both the 5-byte stub keys and real 32-byte
 * Ed25519 keys / 64-byte signatures). Because the exchange rides the established
 * E2E channel, the challenge is confidential and the response is authenticated
 * end-to-end — an attestation cannot be read or replayed across channels, so the
 * channel itself binds the challenge (no extra binding needed).
 */
class SoftwareAppAttestor(
    private val crypto: SyncCryptoManager,
    private val attestationKey: SyncCryptoManager.KeyPair,
) : AppAttestor {
    override fun attest(challenge: ByteArray): ByteArray {
        val signature = crypto.signEd25519(attestationKey.privateKey, challenge)
        return encodeAttestation(attestationKey.publicKey, signature)
    }
}

/**
 * Verifier counterpart of [SoftwareAppAttestor]. Parses the attestation blob,
 * verifies the Ed25519 signature over the challenge, and (optionally) checks the
 * attestation key against [isTrusted] — modelling Apple's "is this a key my app
 * legitimately attested" gate. Returns [AppAttestResult.Attested] with a key id
 * derived from the public key (for cross-reconnection pinning).
 */
class SoftwareAppAttestVerifier(
    private val crypto: SyncCryptoManager,
    private val isTrusted: (attestationPublicKey: ByteArray) -> Boolean = { true },
) : AppAttestVerifier {
    override fun verify(challenge: ByteArray, attestation: ByteArray): AppAttestResult {
        val parsed = decodeAttestation(attestation)
            ?: return AppAttestResult.Rejected("malformed attestation")
        val (publicKey, signature) = parsed
        if (!crypto.verifyEd25519(publicKey, challenge, signature)) {
            return AppAttestResult.Rejected("attestation signature invalid")
        }
        if (!isTrusted(publicKey)) {
            return AppAttestResult.Rejected("attestation key not trusted")
        }
        return AppAttestResult.Attested(keyId = publicKey.toKeyId())
    }
}

private const val PUBLIC_KEY_LENGTH_PREFIX = 2

private fun encodeAttestation(publicKey: ByteArray, signature: ByteArray): ByteArray {
    val out = ByteArray(PUBLIC_KEY_LENGTH_PREFIX + publicKey.size + signature.size)
    out[0] = (publicKey.size ushr 8).toByte()
    out[1] = publicKey.size.toByte()
    publicKey.copyInto(out, PUBLIC_KEY_LENGTH_PREFIX)
    signature.copyInto(out, PUBLIC_KEY_LENGTH_PREFIX + publicKey.size)
    return out
}

private fun decodeAttestation(blob: ByteArray): Pair<ByteArray, ByteArray>? {
    if (blob.size < PUBLIC_KEY_LENGTH_PREFIX) return null
    val publicKeyLength = ((blob[0].toInt() and 0xFF) shl 8) or (blob[1].toInt() and 0xFF)
    val signatureStart = PUBLIC_KEY_LENGTH_PREFIX + publicKeyLength
    if (publicKeyLength <= 0 || signatureStart > blob.size || signatureStart == blob.size) return null
    val publicKey = blob.copyOfRange(PUBLIC_KEY_LENGTH_PREFIX, signatureStart)
    val signature = blob.copyOfRange(signatureStart, blob.size)
    return publicKey to signature
}

private fun ByteArray.toKeyId(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
