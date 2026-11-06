package org.tenshu.core.common.security

// Platform-abstracted Argon2id (RFC 9106). Threat model T-RC-02 specifies
// Argon2id as the slow KDF for shared-key derivation
// from BIP-39 entropy: parameters sized so that a single guess takes
// ~1 second on a high-end GPU. Memory-hard by design — GPUs and ASICs
// lose against high memory cost, which is the property we're paying for.
//
// Backend per platform:
//   - JVM (desktop + Android): BouncyCastle's `Argon2BytesGenerator` +
//     `Argon2Parameters` (no extra dependency — `bcprov` is already on
//     the JVM classpath for Ed25519/X25519/AEAD).
//   - JS: `@noble/hashes/argon2`'s sync `argon2id`. Same package family
//     as the other JS backends; no extra npm dependency (the `argon2`
//     export ships in `@noble/hashes` 1.4+).
//   - iOS: libsodium via cinterop (`CryptoBackendsModule.ios.kt`), which
//     produces bytes identical to the JVM and JS backends so an iOS client
//     and a desktop/Android proxy interoperate over one wire format.
//
// Argon2id version is **0x13** (RFC 9106 — the latest, and the only
// version every modern caller emits). Both backends default to 0x13;
// changing the version would change all derived keys, so it's not a
// per-call parameter.
//
// Parallelism is a per-call parameter rather than fixed at 1. p=1 is
// what `DefaultSyncCryptoManager.deriveKeys` uses today (portability:
// libraries differ subtly on multi-lane behaviour at p>1, and the
// recovery flow runs on a single thread anyway), but slice D's
// IndexedDB master-key wrapper may want different parameters and
// fixing parallelism in the interface would force a redesign.
interface KdfBackend {

    /**
     * Argon2id KDF over [password] (in our use: the 32-byte BIP-39 entropy
     * after `Bip39.decode`, *not* the raw mnemonic string — see
     * `core-common/.../util/Bip39.kt` and threat-model T-RC-02) with
     * [salt] and the supplied cost parameters. Returns a [outputLength]-
     * byte block.
     *
     * Throws [IllegalArgumentException] when any cost parameter is out of
     * RFC 9106's permitted range.
     *
     * Costs: this is intentionally slow (~hundreds of ms to multiple
     * seconds depending on parameters). Callers must run it off the UI
     * thread.
     */
    fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        parallelism: Int,
        memoryKiB: Int,
        iterations: Int,
        outputLength: Int,
    ): ByteArray

    companion object {
        /**
         * Production Argon2id parameters for the recovery-code KDF
         * (`DefaultSyncCryptoManager.deriveKeys`) per threat-model
         * T-RC-02. Memory-hard parameters sized so brute-forcing the
         * 256-bit BIP-39 entropy is far outside any feasible adversarial
         * envelope; the cost on a user's device at recovery time is
         * ~1–2 seconds on a modern laptop and acceptable on phones.
         *
         * Re-evaluate annually per threat-model §12. Bumping these
         * parameters changes all derived keys, so a bump is a protocol-
         * version change.
         */
        const val RECOVERY_KDF_PARALLELISM = 1
        const val RECOVERY_KDF_MEMORY_KIB = 64 * 1024 // 64 MiB
        const val RECOVERY_KDF_ITERATIONS = 3
        const val RECOVERY_KDF_OUTPUT_BYTES = 32
    }
}
