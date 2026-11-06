package org.tenshu.core.security

import org.tenshu.core.core.TComponent

/**
 * Manager for handling end-to-end encryption (E2EE) keys and operations.
 *
 * This component is responsible for:
 * 1. Deriving shared keys from the user's recovery phrase (BIP-39).
 * 2. Managing the lifecycle of the Shared Identity Key and Shared Volume Key.
 * 3. Encrypting and decrypting data envelopes and binary blobs.
 */
interface SyncCryptoManager : TComponent {

    /**
     * Derives a set of keys from a recovery phrase.
     */
    fun deriveKeys(recoveryPhrase: String): DerivedKeys

    /**
     * Encrypts a binary payload (e.g. serialized metadata) using the given key.
     */
    fun encrypt(payload: ByteArray, key: ByteArray): ByteArray

    /**
     * Decrypts an encrypted payload using the given key.
     */
    fun decrypt(encryptedPayload: ByteArray, key: ByteArray): ByteArray

    /**
     * Encrypt [plaintext] under [key] with [associatedData] bound into
     * the authentication tag. Used by the file-content plane
     * (`docs/adr/0017-file-content-two-plane-channel.md` P1) to encrypt each
     * file chunk independently with `(fileId, chunkIndex, chunkCount)`
     * as AAD, so a chunk ciphertext cannot be silently reordered,
     * truncated, replayed across files, or spliced — any tamper of the
     * bound context fails decryption.
     *
     * The default implementation **does not touch the AEAD primitive or
     * any platform crypto backend** (threat-model TA3 / T-RC-02 frozen):
     * it length-prefix-frames `associatedData` ahead of [plaintext] and
     * runs the existing [encrypt], so the AAD travels *inside* the same
     * Poly1305-authenticated ciphertext. Authenticity of the AAD is
     * therefore exactly the authenticity the underlying AEAD already
     * provides. [decryptWithAad] re-verifies the bound AAD constant-ish
     * and strips it. Documented in `docs/sync/threat-model.md` (P1
     * amendment) as the chosen construction.
     *
     * Marked `open` via default so existing test fakes overriding only
     * [encrypt]/[decrypt] keep working unchanged.
     */
    fun encryptWithAad(
        plaintext: ByteArray,
        key: ByteArray,
        associatedData: ByteArray
    ): ByteArray = encrypt(frameWithAad(associatedData, plaintext), key)

    /**
     * Inverse of [encryptWithAad]. Decrypts, then checks the embedded
     * AAD frame matches [associatedData] exactly. Throws
     * [AadMismatchException] if the bound context differs (wrong file,
     * reordered chunk index, mismatched chunk count) — distinct from the
     * underlying AEAD's own authentication failure so callers can tell
     * "ciphertext intact but bound to a different context" from "bytes
     * corrupted". The frame is inside the authenticated ciphertext, so a
     * relay that flips AAD bytes triggers the AEAD tag failure first;
     * this check catches a relay that serves a *validly-encrypted but
     * wrong* chunk (e.g. chunk 7 served for the chunk-3 ref).
     */
    fun decryptWithAad(
        ciphertext: ByteArray,
        key: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        val framed = decrypt(ciphertext, key)
        val (embeddedAad, plaintext) = unframeWithAad(framed)
        if (!embeddedAad.contentEquals(associatedData)) {
            throw AadMismatchException()
        }
        return plaintext
    }

    /**
     * Generates a fresh **X25519** ephemeral keypair for the pairing
     * handshake. The keypair lives only as long as the handshake; both
     * sides discard their halves after the master key has been
     * exchanged (asset A2 in `docs/sync/threat-model.md`).
     *
     * Slice B+ produces a real X25519 keypair via the
     * `X25519Backend`; pre-Phase-4 stubs returned 32 random bytes as
     * both halves.
     */
    fun generateKeyPair(): KeyPair

    /**
     * Generates a fresh **Ed25519** signing keypair for asset A4 (per-
     * device signing key). The default delegates to [generateKeyPair]
     * so existing test fakes that override [generateKeyPair] keep
     * round-tripping; the production [DefaultSyncCryptoManager] in
     * `:core-common` overrides this with a real Ed25519 derivation
     * via the `Ed25519Backend`.
     *
     * Distinct from [generateKeyPair] because Ed25519 (signing) and
     * X25519 (ECDH) use different curves; stub crypto could share one
     * 32-byte blob, real crypto can't.
     */
    fun generateDeviceSigningKeyPair(): KeyPair = generateKeyPair()

    /**
     * Generates a fresh **Ed25519** device-admission keypair — asset A33
     * (`docs/proposals/delegated-device-admission-authority.md` §4.1), the
     * personal-realm analogue of the shared-realm member authority (A26).
     * Its private key signs new-device delegations (and, transitively, child
     * capability certs); its public key is what an
     * `AdmissionCapabilityCert` binds into the chain rooted at A1.
     *
     * Same delegation-to-[generateKeyPair] shape as
     * [generateDeviceSigningKeyPair], for the same stub-vs-real-crypto reason.
     */
    fun generateAdmissionKeyPair(): KeyPair = generateKeyPair()

    /**
     * Calculates a shared secret using X25519 ECDH (RFC 7748). The
     * 32-byte output is the raw shared secret — feed it through HKDF
     * (or the protocol's key-derivation step) before encrypting under
     * it.
     */
    fun calculateSharedSecret(privateKey: ByteArray, otherPublicKey: ByteArray): ByteArray

    /**
     * Derive the realm's master identity keypair (asset A1 in `docs/sync/threat-model.md`)
     * from the user's recovery phrase. Used to:
     *   - sign the realm-bootstrap self-signature for `POST /sync/realm`
     *     (`sync-relay-api.md` §3.2),
     *   - sign master delegations admitting new device keys during pairing
     *     (`sync-relay-api.md` §3.3),
     *   - sign challenge responses during recovery (`sync-relay-api.md` §3.4).
     *
     * ⚠ Implemented for real by `DefaultSyncCryptoManager` (`:core-common`): the BIP-39
     * mnemonic is decoded and run through HKDF-SHA-256 with a domain-separated `info`.
     * `BootstrapSyncCryptoManager` returns a deterministic stub keypair so tests round-trip;
     * that stub is the exception, not the platform posture.
     */
    fun deriveMasterKeyPair(recoveryPhrase: String): KeyPair

    /**
     * Derive the realm identity's X25519 **encryption sub-key** (asset A13,
     * `docs/sync/threat-model.md` + `tenshu-rei.md` §2.3) from the recovery
     * phrase. Deterministic + re-derivable from the recovery code, on a
     * distinct curve (X25519 vs A1's Ed25519) and a distinct HKDF domain, so
     * A13 is cryptographically independent of the Ed25519 master identity (A1,
     * [deriveMasterKeyPair]) despite sharing the recovery-code entropy. The
     * public side is what a shared realm's master key (A12) is wrapped *to*;
     * the private side unwraps it — see [wrapKeyForIdentity].
     *
     * Default throws: only the real [org.tenshu.core.common.security.DefaultSyncCryptoManager]
     * (and the bootstrap stub) implement it. Test fakes that don't exercise
     * shared-realm crypto inherit this throwing default — mirroring how
     * [encryptWithAad] is `open` so fakes overriding only [encrypt]/[decrypt]
     * keep compiling.
     */
    fun deriveRealmEncryptionKeyPair(recoveryPhrase: String): KeyPair =
        error("deriveRealmEncryptionKeyPair not implemented by ${this::class.simpleName}")

    /**
     * Wrap [key] (e.g. a shared-realm master key, asset A12) so that only the
     * holder of the X25519 private key matching [recipientEncPublicKey] (the
     * recipient's A13 public key from [deriveRealmEncryptionKeyPair]) can
     * unwrap it. Sealed-box: a fresh ephemeral X25519 keypair → ECDH with the
     * recipient → HKDF wrap key → AEAD-seal. Output is
     * `ephemeralPublicKey(32) || aeadBlob`; the ephemeral private key is
     * discarded (forward-secret w.r.t. the wrapper). Wrapping is once at
     * master-key level — **never per file** (`tenshu-rei.md` §2.3). This is a
     * confidentiality primitive only; member attribution is a separate
     * signature under the member's own A1 (§2.3), not done here.
     *
     * Default throws — see [deriveRealmEncryptionKeyPair].
     */
    fun wrapKeyForIdentity(key: ByteArray, recipientEncPublicKey: ByteArray): ByteArray =
        error("wrapKeyForIdentity not implemented by ${this::class.simpleName}")

    /**
     * Inverse of [wrapKeyForIdentity]. Splits the ephemeral public key off the
     * front of [wrapped], re-derives the shared secret via ECDH with
     * [myEncPrivateKey] (the recipient's A13 private key), and AEAD-opens the
     * blob. Recipient-binding is enforced by the ECDH math: a wrong private
     * key yields a different shared secret → different wrap key → the AEAD tag
     * fails (the underlying backend throws). The decrypt also re-checks the
     * AAD-bound ephemeral public key.
     *
     * Default throws — see [deriveRealmEncryptionKeyPair].
     */
    fun unwrapKeyForIdentity(wrapped: ByteArray, myEncPrivateKey: ByteArray): ByteArray =
        error("unwrapKeyForIdentity not implemented by ${this::class.simpleName}")

    /**
     * Generalised sealed box over an A13 identity key
     * (`docs/proposals/contact-invitation-handshake.md` §5.1): seal arbitrary
     * [plaintext] so only the holder of the X25519 private key matching
     * [recipientEncPublicKey] can open it. Structurally [wrapKeyForIdentity]
     * with a caller-supplied HKDF/AAD domain: [info] separates protocol
     * surfaces (e.g. `tenshu/invite/response/v1` vs `tenshu/invite/ack/v1` vs
     * the A12 key wrap), and [aad] binds extra context (e.g. the `inviteId`)
     * into the AEAD tag so a blob cannot be cross-spliced between contexts —
     * unsealing succeeds only with the identical `(info, aad)` pair.
     *
     * Blob layout matches [wrapKeyForIdentity] exactly:
     * `ephemeralPublicKey(32) || aeadBlob`, with the AAD framed as
     * `info || ephemeralPublicKey || aad`. `wrapKeyForIdentity` is the
     * `info = "tenshu/key-wrap/v1", aad = empty` instance of this primitive;
     * its existing blobs remain readable unchanged.
     *
     * Default throws — see [deriveRealmEncryptionKeyPair].
     */
    fun sealToIdentity(
        plaintext: ByteArray,
        recipientEncPublicKey: ByteArray,
        info: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray =
        error("sealToIdentity not implemented by ${this::class.simpleName}")

    /**
     * Inverse of [sealToIdentity]. Throws (AEAD tag failure from the backend,
     * or [AadMismatchException]) when the private key, [info], or [aad] do not
     * match what the blob was sealed under.
     *
     * Default throws — see [deriveRealmEncryptionKeyPair].
     */
    fun unsealFromIdentity(
        sealed: ByteArray,
        myEncPrivateKey: ByteArray,
        info: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray =
        error("unsealFromIdentity not implemented by ${this::class.simpleName}")

    /**
     * Generate a fresh 32-byte **shared-realm master key** (asset A12,
     * `tenshu-rei.md` §2.3) — the symmetric root a shared folder/realm is keyed
     * by. Unlike the personal realm's keys (derived from the recovery phrase via
     * Argon2id), a shared realm has **no recovery phrase**: its master key is
     * random and distributed to members **wrapped** ([wrapKeyForIdentity]) under
     * each member's A13. Re-derivable only by holding a member's wrapped copy,
     * never from a phrase. Symmetric data keys come from [deriveSharedRealmKeys].
     *
     * Default throws — see [deriveRealmEncryptionKeyPair].
     */
    fun generateSharedRealmMasterKey(): ByteArray =
        error("generateSharedRealmMasterKey not implemented by ${this::class.simpleName}")

    /**
     * Derive a shared realm's symmetric [DerivedKeys] (sharedIdentityKey +
     * sharedVolumeKey) from its 32-byte master key (A12, [generateSharedRealmMasterKey]).
     * Pure HKDF domain-separation — **no Argon2id**: the master key is already a
     * full-entropy random 32-byte secret (the personal path runs Argon2id only to
     * stretch a low-entropy *phrase*). The HKDF `info` tags are **distinct** from
     * the personal `deriveKeys` tags so a shared realm's keys are independent of
     * the personal realm's even if (hypothetically) the inputs collided. Mirrors
     * the personal split shape so [org.tenshu.sync.common.RealmContext] is keyed
     * identically regardless of realm origin.
     *
     * Default throws — see [deriveRealmEncryptionKeyPair].
     */
    fun deriveSharedRealmKeys(masterKey: ByteArray): DerivedKeys =
        error("deriveSharedRealmKeys not implemented by ${this::class.simpleName}")

    /**
     * Sign [message] with an Ed25519 [privateKey] (asset A4 in `docs/sync/threat-model.md`).
     *
     * ⚠ **Real RFC 8032 Ed25519 in production**: `DefaultSyncCryptoManager` (`:core-common`)
     * delegates to `Ed25519Backend` — BouncyCastle on JVM, `@noble/curves` on JS, libsodium on
     * iOS. Only `BootstrapSyncCryptoManager` is a structural stub (see [generateKeyPair]), so
     * do not read this interface as saying relay JWTs or admission certificates are unsigned.
     */
    fun signEd25519(privateKey: ByteArray, message: ByteArray): ByteArray

    /**
     * Verify a signature produced by [signEd25519] against the matching [publicKey].
     *
     * ⚠ See [signEd25519]: the production backend verifies real signatures. Only
     * `BootstrapSyncCryptoManager` relies on the `publicKey == privateKey` invariant of its own
     * [generateKeyPair], which round-trips in tests without authenticating anything.
     */
    fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    /**
     * Represents an asymmetric key pair.
     */
    class KeyPair(
        val publicKey: ByteArray,
        val privateKey: ByteArray
    )

    /**
     * Set of derived shared keys shared among the user's realm devices.
     */
    class DerivedKeys(
        val sharedIdentityKey: ByteArray,
        val sharedVolumeKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DerivedKeys) return false
            if (!sharedIdentityKey.contentEquals(other.sharedIdentityKey)) return false
            if (!sharedVolumeKey.contentEquals(other.sharedVolumeKey)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = sharedIdentityKey.contentHashCode()
            result = 31 * result + sharedVolumeKey.contentHashCode()
            return result
        }
    }

    companion object {
        const val ID = "tenshu/core/security/syncCryptoManager"
    }
}

fun org.tenshu.core.core.TContext.syncCryptoManager(): SyncCryptoManager =
    components[SyncCryptoManager.ID] as SyncCryptoManager

/**
 * Thrown by [SyncCryptoManager.decryptWithAad] when the ciphertext
 * decrypts cleanly (AEAD tag valid) but the AAD bound at encryption time
 * does not match what the caller expected — i.e. a validly-encrypted
 * chunk served under the wrong context (reorder / cross-file splice).
 */
class AadMismatchException(
    message: String = "AEAD associated-data mismatch"
) : RuntimeException(message)

/**
 * `len(aad) as 4 BE bytes || aad || plaintext`. Both halves end up
 * inside the single Poly1305-authenticated ciphertext, so the AAD is
 * tamper-evident with exactly the AEAD's own integrity strength — no
 * change to the AEAD primitive or any platform backend (TA3/T-RC-02
 * frozen). 4-byte length prefix caps AAD at 2^31-1 bytes, far beyond
 * the tens-of-bytes `(fileId, chunkIndex, chunkCount)` tuples this is
 * used for.
 */
internal fun frameWithAad(aad: ByteArray, plaintext: ByteArray): ByteArray {
    val out = ByteArray(4 + aad.size + plaintext.size)
    val n = aad.size
    out[0] = (n ushr 24 and 0xFF).toByte()
    out[1] = (n ushr 16 and 0xFF).toByte()
    out[2] = (n ushr 8 and 0xFF).toByte()
    out[3] = (n and 0xFF).toByte()
    aad.copyInto(out, destinationOffset = 4)
    plaintext.copyInto(out, destinationOffset = 4 + aad.size)
    return out
}

/** Inverse of [frameWithAad]. Returns `(aad, plaintext)`. */
internal fun unframeWithAad(framed: ByteArray): Pair<ByteArray, ByteArray> {
    require(framed.size >= 4) { "framed blob too short for AAD length prefix" }
    val n = ((framed[0].toInt() and 0xFF) shl 24) or
        ((framed[1].toInt() and 0xFF) shl 16) or
        ((framed[2].toInt() and 0xFF) shl 8) or
        (framed[3].toInt() and 0xFF)
    require(n >= 0 && 4 + n <= framed.size) { "framed blob AAD length out of range" }
    val aad = framed.copyOfRange(4, 4 + n)
    val plaintext = framed.copyOfRange(4 + n, framed.size)
    return aad to plaintext
}
