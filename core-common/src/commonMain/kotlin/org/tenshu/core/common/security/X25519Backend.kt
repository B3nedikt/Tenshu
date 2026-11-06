package org.tenshu.core.common.security

// Platform-abstracted X25519 (RFC 7748). Threat model TA3 specifies
// X25519 for the pairing-handshake ECDH; this interface lets
// `DefaultSyncCryptoManager` produce a real ephemeral keypair and
// compute a real shared secret (replacing slice A's dummy random + XOR
// stub).
//
// Backend per platform:
//   - JVM (desktop + Android): BouncyCastle (`X25519PrivateKeyParameters`,
//     `X25519PublicKeyParameters`, `X25519Agreement`).
//   - JS: `@noble/curves/ed25519`'s `x25519` export — same package as
//     Ed25519, no extra bundle cost.
//   - iOS: libsodium via cinterop (`CryptoBackendsModule.ios.kt`), for the
//     reason given on `Ed25519Backend` — CryptoKit is unreachable from K/N.
interface X25519Backend {

    /**
     * Generate a fresh X25519 ephemeral keypair. Used by the pairing
     * handshake — `SyncManagerImpl.generatePairingContext()` and the
     * pairing-implementation ops produce one of these per pairing
     * request, then discard after the shared secret is derived.
     */
    fun generateKeyPair(): SyncKeyPair

    /**
     * **Deterministically** derive an X25519 keypair from a 32-byte
     * [seed] (the raw private scalar). Unlike [generateKeyPair] this is
     * reproducible — same seed, same keypair — which is what the realm
     * identity's **encryption sub-key (asset A13)** needs: re-derivable
     * from the recovery code (`tenshu-rei.md` §2.3). Callers pass raw
     * 32-byte key material (e.g. an HKDF output); the library clamps the
     * scalar internally per RFC 7748, so no caller-side clamping is needed.
     *
     * Throws [IllegalArgumentException] when [seed] is not exactly
     * [X25519_KEY_SIZE] bytes.
     */
    fun deriveKeyPair(seed: ByteArray): SyncKeyPair

    /**
     * Compute the X25519 shared secret between [privateKey] and
     * [otherPublicKey]. Returns a 32-byte secret; *do not use it
     * directly as a symmetric key*: feed it through HKDF-SHA-256 (or
     * the protocol's key-derivation step) before encrypting.
     *
     * Throws [IllegalArgumentException] when either input is not
     * exactly [X25519_KEY_SIZE] bytes.
     */
    fun calculateSharedSecret(privateKey: ByteArray, otherPublicKey: ByteArray): ByteArray

    companion object {
        /** RFC 7748 §5: X25519 private/public key length in bytes. */
        const val X25519_KEY_SIZE = 32

        /** RFC 7748 §5: X25519 shared-secret length in bytes. */
        const val X25519_SHARED_SECRET_SIZE = 32
    }
}

/**
 * Plain holder for a (private, public) key pair. Distinct from
 * `SyncCryptoManager.KeyPair` only because that one lives in `:core`
 * and we don't want `:core-common`'s backend interfaces to leak the
 * higher-level domain wrapper. The two have the same shape — the
 * `SyncCryptoManager` impl converts at the boundary.
 */
class SyncKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
)
