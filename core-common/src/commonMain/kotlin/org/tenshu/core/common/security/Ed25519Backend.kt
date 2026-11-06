package org.tenshu.core.common.security

// Platform-abstracted Ed25519 (RFC 8032). Threat model TA3 specifies
// Ed25519 for asset A1 (master identity keypair) and A4 (per-device
// signing keys); this interface lets `DefaultSyncCryptoManager` satisfy
// that contract with real cryptography on every platform without
// proliferating `expect`/`actual` declarations.
//
// Backend selection (see `docs/tech-debt.md` "Crypto Backend Selection"):
//   - JVM (desktop + Android): BouncyCastle. Android's standard provider
//     only has Ed25519 from API 33+; minSdk is 24, so BC is the portable
//     choice. Same library on desktop for behavioural uniformity.
//   - JS: `@noble/curves/ed25519`. ~30 KB minified, audited, sync API.
//   - iOS: libsodium via cinterop (`CryptoBackendsModule.ios.kt`).
//     Deliberately NOT CryptoKit: it is Swift-only and unreachable from
//     Kotlin/Native's Objective-C interop.
//
// Inputs and outputs are the canonical RFC 8032 sizes — anything else
// throws on the impl side rather than silently truncating. Verify is
// non-throwing (length mismatches return false) so callers don't need
// to length-check before verification — verify is the source of truth.
interface Ed25519Backend {

    /**
     * Sign [message] with the 32-byte Ed25519 [privateKeySeed]. Returns a
     * 64-byte signature. Throws [IllegalArgumentException] when
     * [privateKeySeed] is not exactly [ED25519_SEED_SIZE] bytes.
     */
    fun sign(privateKeySeed: ByteArray, message: ByteArray): ByteArray

    /**
     * Verify [signature] over [message] under [publicKey]. Returns false
     * on any length mismatch (no exception).
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    /**
     * Derive the 32-byte Ed25519 public key for the given 32-byte
     * [privateKeySeed]. Used to materialise the public half of a derived
     * keypair without going through a full sign-then-extract round.
     * Throws [IllegalArgumentException] when [privateKeySeed] is not
     * exactly [ED25519_SEED_SIZE] bytes.
     */
    fun derivePublicKey(privateKeySeed: ByteArray): ByteArray

    companion object {
        /** RFC 8032 §5.1.5: secret seed length in bytes. */
        const val ED25519_SEED_SIZE = 32

        /** RFC 8032 §5.1.5: public-key length in bytes. */
        const val ED25519_PUBLIC_KEY_SIZE = 32

        /** RFC 8032 §5.1.6: signature length in bytes. */
        const val ED25519_SIGNATURE_SIZE = 64
    }
}
