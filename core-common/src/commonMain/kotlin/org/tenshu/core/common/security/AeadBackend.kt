package org.tenshu.core.common.security

// Platform-abstracted AEAD. Threat model TA3 specifies
// XChaCha20-Poly1305 (RFC 8439 + draft-irtf-cfrg-xchacha for the 24-byte
// nonce extension); this interface lets `DefaultSyncCryptoManager`
// satisfy that contract on every platform.
//
// Backend per platform:
//   - JVM (desktop + Android): BouncyCastle's
//     `org.bouncycastle.crypto.modes.XChaCha20Poly1305`.
//   - JS: `@noble/ciphers/chacha`'s `xchacha20poly1305`. Same author as
//     `@noble/curves` / `@noble/hashes` already in core-common.
//   - iOS: libsodium via cinterop (`CryptoBackendsModule.ios.kt`). CryptoKit
//     was never an option here twice over: Swift-only, and its `ChaChaPoly`
//     is AES-GCM-shaped rather than XChaCha20.
//
// Output format is **self-contained**: `nonce (24) || ciphertext ||
// tag (16)`. This keeps the existing `encrypt(payload, key)` /
// `decrypt(payload, key)` two-arg API on `SyncCryptoManager` workable —
// callers don't manage nonces. Trade-off: every encryption costs an
// extra 40 bytes overhead, which is fine for the small payloads
// (recovery phrase, master key, sync block envelope) the existing
// callers use.
interface AeadBackend {

    /**
     * Encrypt [plaintext] under [key]. Generates a fresh random nonce,
     * prepends it to the ciphertext+tag, and returns the combined blob:
     * `nonce (24) || ciphertext (|plaintext|) || tag (16)`.
     *
     * Throws [IllegalArgumentException] when [key] is not exactly
     * [AEAD_KEY_SIZE] bytes.
     */
    fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray

    /**
     * Decrypt [ciphertext] under [key]. Expects the
     * `nonce || ciphertext || tag` format produced by [encrypt].
     *
     * Throws [IllegalArgumentException] when [key] is not exactly
     * [AEAD_KEY_SIZE] bytes or [ciphertext] is too short to contain
     * a nonce + tag. Throws [AeadAuthenticationException] when the
     * Poly1305 tag doesn't match (corrupted ciphertext, wrong key,
     * or tampered nonce).
     */
    fun decrypt(ciphertext: ByteArray, key: ByteArray): ByteArray

    companion object {
        /** XChaCha20-Poly1305 key length. */
        const val AEAD_KEY_SIZE = 32

        /** XChaCha20-Poly1305 nonce length (24 bytes — the "X" extension). */
        const val AEAD_NONCE_SIZE = 24

        /** Poly1305 tag length. */
        const val AEAD_TAG_SIZE = 16

        /** Minimum ciphertext-blob length: nonce + tag, with empty plaintext. */
        const val AEAD_MIN_CIPHERTEXT_SIZE = AEAD_NONCE_SIZE + AEAD_TAG_SIZE
    }
}

/**
 * Thrown by [AeadBackend.decrypt] when the Poly1305 tag fails
 * verification. Either the ciphertext was tampered with, the wrong key
 * was supplied, or the encoded nonce was modified. Catch-all for the
 * "decrypt is the source of truth on authenticity" contract; callers
 * generally treat any of these as the same fatal condition.
 */
class AeadAuthenticationException(message: String = "AEAD authentication failed") :
    RuntimeException(message)
