package org.tenshu.core.common.security

import org.tenshu.core.security.SyncCryptoManager

// Wraps the master identity private key (asset A1, threat-model §A1) under a
// wrap key for at-rest storage. The output is a self-authenticated AEAD blob
// — a wrong wrap key or a corrupted blob fails the auth tag and surfaces as
// `MasterKeyUnwrapException`.
//
// The blob shape (nonce(24) || ciphertext || tag(16)) is owned by the
// underlying [SyncCryptoManager.encrypt] / [SyncCryptoManager.decrypt] —
// slice D piggybacks on the slice-B XChaCha20-Poly1305 implementation. The
// wrap-key length constraint is also delegated: non-32-byte keys are
// HKDF-stretched inside `requireAeadKey` (slice 4.4f), so callers can pass
// raw shared-secret bytes, Argon2id-derived keys, or future WebAuthn-PRF
// outputs without each call site knowing about the length.
//
// Today's expected wrap-key sources (the call site picks one):
//   - 32 random bytes from the platform CSPRNG. Gives at-rest IDB storage
//     with zero security improvement over keeping the key in memory; the
//     placeholder until a real authenticator-bound key lands.
//   - Argon2id-from-passphrase via [SyncCryptoManager.deriveKeys] for the
//     desktop/Android unlock-passphrase path.
//
// Phase 5's WebAuthn PRF / largeBlob extension (`docs/web/architecture.md`
// §4.4) will produce the wrap key from the user's authenticator. The
// wrapper interface stays the same; only the wrap-key source changes.
//
// Versioning: the envelope is opaque bytes today. If we ever need to bump
// the wrap algorithm without breaking existing blobs, prepend a 1-byte
// version tag in `wrap()` and dispatch on it in `unwrap()`. Not needed
// until the wrap path actually changes.
class MasterKeyWrapper(private val crypto: SyncCryptoManager) {

    fun wrap(masterPrivateKey: ByteArray, wrapKey: ByteArray): WrappedMasterKey {
        require(masterPrivateKey.isNotEmpty()) { "masterPrivateKey is empty" }
        require(wrapKey.isNotEmpty()) { "wrapKey is empty" }
        return WrappedMasterKey(crypto.encrypt(masterPrivateKey, wrapKey))
    }

    fun unwrap(blob: WrappedMasterKey, wrapKey: ByteArray): ByteArray {
        require(wrapKey.isNotEmpty()) { "wrapKey is empty" }
        return try {
            crypto.decrypt(blob.bytes, wrapKey)
        } catch (t: Throwable) {
            throw MasterKeyUnwrapException(t)
        }
    }

    // Wrap an Ed25519 master keypair (asset A1) so the unwrap path can
    // restore both halves without re-deriving the public key from the
    // private seed. The plaintext layout is:
    //
    //   version(1) || privateKey(32) || publicKey(32)   (= 65 bytes)
    //
    // Version byte gates future changes (curve swap, packed encoding,
    // etc.) without invalidating existing blobs — the AEAD envelope from
    // [wrap] above is still the outer wrap. v1 is Ed25519-only per
    // threat-model TA3; non-32-byte halves throw.
    fun wrap(keyPair: SyncCryptoManager.KeyPair, wrapKey: ByteArray): WrappedMasterKey {
        require(keyPair.privateKey.size == ED25519_KEY_SIZE) {
            "keyPair.privateKey must be $ED25519_KEY_SIZE bytes (got ${keyPair.privateKey.size})"
        }
        require(keyPair.publicKey.size == ED25519_KEY_SIZE) {
            "keyPair.publicKey must be $ED25519_KEY_SIZE bytes (got ${keyPair.publicKey.size})"
        }
        val payload = ByteArray(KEYPAIR_PAYLOAD_SIZE)
        payload[0] = KEYPAIR_PAYLOAD_VERSION
        keyPair.privateKey.copyInto(payload, destinationOffset = 1)
        keyPair.publicKey.copyInto(payload, destinationOffset = 1 + ED25519_KEY_SIZE)
        return wrap(payload, wrapKey)
    }

    // Wrap an Ed25519 master keypair (asset A1) *plus* the 24-word recovery
    // phrase's BIP-39 entropy, so the web reveal-phrase path
    // (`Recovery.revealPhrase`, low-friction onboarding §7.4) can reconstruct
    // the mnemonic after a PRF unlock without the user re-entering it. The
    // plaintext layout is the v2 variant:
    //
    //   version(0x02) || privateKey(32) || publicKey(32) || entropy(32)  (= 97 bytes)
    //
    // The entropy is the same seed that derives A1 and the shared keys — so a
    // wrap-key holder who can unwrap this blob already has A1; carrying the
    // entropy adds no trust boundary (threat-model A1, web at-rest paragraph).
    // Old v1 blobs (keypair only) remain valid and unwrap to a null entropy.
    fun wrap(
        keyPair: SyncCryptoManager.KeyPair,
        entropy: ByteArray,
        wrapKey: ByteArray,
    ): WrappedMasterKey {
        require(keyPair.privateKey.size == ED25519_KEY_SIZE) {
            "keyPair.privateKey must be $ED25519_KEY_SIZE bytes (got ${keyPair.privateKey.size})"
        }
        require(keyPair.publicKey.size == ED25519_KEY_SIZE) {
            "keyPair.publicKey must be $ED25519_KEY_SIZE bytes (got ${keyPair.publicKey.size})"
        }
        require(entropy.size == ENTROPY_SIZE) {
            "entropy must be $ENTROPY_SIZE bytes (got ${entropy.size})"
        }
        val payload = ByteArray(KEYPAIR_ENTROPY_PAYLOAD_SIZE)
        payload[0] = KEYPAIR_ENTROPY_PAYLOAD_VERSION
        keyPair.privateKey.copyInto(payload, destinationOffset = 1)
        keyPair.publicKey.copyInto(payload, destinationOffset = 1 + ED25519_KEY_SIZE)
        entropy.copyInto(payload, destinationOffset = 1 + 2 * ED25519_KEY_SIZE)
        return wrap(payload, wrapKey)
    }

    fun unwrapKeyPair(blob: WrappedMasterKey, wrapKey: ByteArray): SyncCryptoManager.KeyPair =
        unwrapKeyPairAndEntropy(blob, wrapKey).first

    // Unwrap a keypair blob, returning the entropy too when the blob is the
    // v2 (entropy-carrying) variant. v1 blobs unwrap to a `null` entropy —
    // the reveal-phrase path treats that as "unavailable" (re-wrap on the
    // next passkey registration upgrades the blob to v2).
    fun unwrapKeyPairAndEntropy(
        blob: WrappedMasterKey,
        wrapKey: ByteArray,
    ): Pair<SyncCryptoManager.KeyPair, ByteArray?> {
        val payload = unwrap(blob, wrapKey)
        if (payload.isEmpty()) {
            throw MasterKeyUnwrapException(
                IllegalArgumentException("wrapped keypair payload is empty")
            )
        }
        return when (payload[0]) {
            KEYPAIR_PAYLOAD_VERSION -> readKeyPair(payload, KEYPAIR_PAYLOAD_SIZE) to null
            KEYPAIR_ENTROPY_PAYLOAD_VERSION -> {
                val keyPair = readKeyPair(payload, KEYPAIR_ENTROPY_PAYLOAD_SIZE)
                val entropy = payload.copyOfRange(
                    1 + 2 * ED25519_KEY_SIZE,
                    KEYPAIR_ENTROPY_PAYLOAD_SIZE,
                )
                keyPair to entropy
            }
            else -> throw MasterKeyUnwrapException(
                IllegalArgumentException("wrapped keypair payload has unknown version: ${payload[0]}")
            )
        }
    }

    private fun readKeyPair(payload: ByteArray, expectedSize: Int): SyncCryptoManager.KeyPair {
        if (payload.size != expectedSize) {
            throw MasterKeyUnwrapException(
                IllegalArgumentException(
                    "wrapped keypair payload has wrong size: ${payload.size} != $expectedSize"
                )
            )
        }
        val privateKey = payload.copyOfRange(1, 1 + ED25519_KEY_SIZE)
        val publicKey = payload.copyOfRange(1 + ED25519_KEY_SIZE, 1 + 2 * ED25519_KEY_SIZE)
        return SyncCryptoManager.KeyPair(publicKey = publicKey, privateKey = privateKey)
    }

    private companion object {
        const val ED25519_KEY_SIZE = 32
        const val ENTROPY_SIZE = 32
        const val KEYPAIR_PAYLOAD_VERSION: Byte = 0x01
        const val KEYPAIR_PAYLOAD_SIZE = 1 + 2 * ED25519_KEY_SIZE
        const val KEYPAIR_ENTROPY_PAYLOAD_VERSION: Byte = 0x02
        const val KEYPAIR_ENTROPY_PAYLOAD_SIZE = 1 + 2 * ED25519_KEY_SIZE + ENTROPY_SIZE
    }
}

class WrappedMasterKey(val bytes: ByteArray) {
    init {
        require(bytes.size >= MIN_BLOB_SIZE) {
            "Wrapped master-key blob is too small: ${bytes.size} < $MIN_BLOB_SIZE"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is WrappedMasterKey && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        // XChaCha20-Poly1305 envelope: 24-byte nonce + ≥1 ciphertext byte + 16-byte tag.
        const val MIN_BLOB_SIZE: Int = 24 + 1 + 16
    }
}

class MasterKeyUnwrapException(cause: Throwable) :
    RuntimeException(
        "Failed to unwrap master key (wrong wrap key or corrupted blob)",
        cause,
    )
