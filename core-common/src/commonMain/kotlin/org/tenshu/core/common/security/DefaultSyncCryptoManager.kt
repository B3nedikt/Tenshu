package org.tenshu.core.common.security

import org.koin.dsl.koinApplication
import org.tenshu.core.common.util.Bip39
import org.tenshu.core.common.util.hkdfSha256
import org.tenshu.core.common.util.nextSecureRandomBytes
import org.tenshu.core.security.SyncCryptoManager

/**
 * A production [DefaultSyncCryptoManager] over this platform's [cryptoBackendsModule], for graphs
 * that are not assembled through Koin's `coreCommonModule()` (e.g. an `InProcessProxy`). Uses a
 * fresh isolated Koin application so it never touches — or depends on — a global Koin.
 */
fun createDefaultSyncCryptoManager(): SyncCryptoManager {
    val koin = koinApplication { modules(cryptoBackendsModule()) }.koin
    return DefaultSyncCryptoManager(
        ed25519 = koin.get<Ed25519Backend>(),
        x25519 = koin.get<X25519Backend>(),
        aead = koin.get<AeadBackend>(),
        kdf = koin.get<KdfBackend>(),
    )
}

/**
 * Production [SyncCryptoManager]. Phase 4 of `docs/web/roadmap.md`.
 *
 *   - **Slice A** (landed): real Ed25519 (sign/verify/derive) via
 *     [Ed25519Backend], real BIP-39 → Ed25519 master-key derivation.
 *   - **Slice B** (landed): real X25519 (keypair + ECDH) via
 *     [X25519Backend], real XChaCha20-Poly1305 AEAD via [AeadBackend].
 *     `generateKeyPair()` returns an X25519 keypair for the pairing
 *     handshake; A4 device keys come through [generateDeviceSigningKeyPair]
 *     (Ed25519, distinct from pairing keys).
 *   - **Slice C** (this slice): real Argon2id KDF via [KdfBackend].
 *     [deriveKeys] now Argon2id-stretches the BIP-39 entropy with the
 *     recovery-flow parameters from threat-model T-RC-02, then
 *     HKDF-domain-separates the result into the shared-identity and
 *     shared-volume halves.
 *
 * All four backends are injected through Koin so each platform can
 * supply its own impls — see [cryptoBackendsModule].
 *
 * Master-keypair derivation: per `docs/sync/threat-model.md` T-K-04
 * the master identity key is "derived deterministically from the
 * recovery code mnemonic." We decode the mnemonic to its 256-bit
 * entropy via [Bip39.decode] (skipping BIP-39's PBKDF2-2048 seed step
 * on purpose — see `Bip39.kt` and T-RC-02), domain-separate via
 * HKDF-SHA-256, and use the 32-byte output as the Ed25519 seed.
 */
class DefaultSyncCryptoManager(
    private val ed25519: Ed25519Backend,
    private val x25519: X25519Backend,
    private val aead: AeadBackend,
    private val kdf: KdfBackend,
) : SyncCryptoManager {

    override fun deriveKeys(recoveryPhrase: String): SyncCryptoManager.DerivedKeys {
        // T-RC-02: feed the *decoded* BIP-39 entropy (not the mnemonic
        // string) into Argon2id with the production recovery-flow
        // parameters, then HKDF-domain-separate the 32-byte output into
        // sharedIdentityKey + sharedVolumeKey. Two layers of derivation:
        //   - Argon2id supplies the brute-force resistance (memory-hard,
        //     ~1–2 s/guess on a modern laptop, far worse for GPUs).
        //   - HKDF supplies the domain separation so the two output keys
        //     remain unrelated and can be rotated/replaced independently.
        // Bip39.decode throws Bip39InvalidMnemonicException on malformed
        // input; production callers either pre-validate (recovery flow)
        // or pass a freshly-generated mnemonic (CreateRealm), so the
        // throw is the programmer-error path.
        val entropy = Bip39.decode(recoveryPhrase)
        val kdfOutput = kdf.argon2id(
            password = entropy,
            salt = RECOVERY_KDF_SALT,
            parallelism = KdfBackend.RECOVERY_KDF_PARALLELISM,
            memoryKiB = KdfBackend.RECOVERY_KDF_MEMORY_KIB,
            iterations = KdfBackend.RECOVERY_KDF_ITERATIONS,
            outputLength = KdfBackend.RECOVERY_KDF_OUTPUT_BYTES,
        )
        return SyncCryptoManager.DerivedKeys(
            sharedIdentityKey = hkdfSha256(
                ikm = kdfOutput,
                salt = ByteArray(0),
                info = SHARED_IDENTITY_HKDF_INFO,
                length = AeadBackend.AEAD_KEY_SIZE,
            ),
            sharedVolumeKey = hkdfSha256(
                ikm = kdfOutput,
                salt = ByteArray(0),
                info = SHARED_VOLUME_HKDF_INFO,
                length = AeadBackend.AEAD_KEY_SIZE,
            ),
        )
    }

    override fun encrypt(payload: ByteArray, key: ByteArray): ByteArray {
        // Real XChaCha20-Poly1305 via AeadBackend. The output blob is
        // self-contained: nonce(24) || ciphertext || tag(16). Callers
        // (SyncObserver, ApprovePairingRequest, SyncManagerImpl recovery-
        // wrapping) treat the result as opaque bytes — they store it,
        // transmit it, and feed it back into `decrypt` later. The 40-byte
        // overhead per encryption is fine for the existing payload sizes
        // (master keys, recovery phrases, sync block envelopes).
        return aead.encrypt(payload, requireAeadKey(key))
    }

    override fun decrypt(encryptedPayload: ByteArray, key: ByteArray): ByteArray {
        return aead.decrypt(encryptedPayload, requireAeadKey(key))
    }

    private fun requireAeadKey(key: ByteArray): ByteArray {
        // Slice A's stub `encrypt`/`decrypt` accepted any key length and
        // XOR-padded with the modulus; real AEAD requires exactly 32 bytes.
        // Stretch shorter keys + truncate longer ones via HKDF so existing
        // callers that pass non-32-byte material keep working without each
        // callsite needing to know about the length constraint. Domain-
        // tagged so a key derived here can't collide with a key derived
        // for a different purpose from the same input.
        if (key.size == AeadBackend.AEAD_KEY_SIZE) return key
        return hkdfSha256(
            ikm = key,
            salt = ByteArray(0),
            info = AEAD_KEY_HKDF_INFO,
            length = AeadBackend.AEAD_KEY_SIZE,
        )
    }

    override fun generateKeyPair(): SyncCryptoManager.KeyPair {
        // X25519 ephemeral keypair for the pairing handshake. Replaces
        // slice A's "32 random bytes as both halves" stub.
        val pair = x25519.generateKeyPair()
        return SyncCryptoManager.KeyPair(publicKey = pair.publicKey, privateKey = pair.privateKey)
    }

    override fun generateDeviceSigningKeyPair(): SyncCryptoManager.KeyPair {
        // Ed25519 device signing keypair (asset A4). The seed is just 32
        // CSPRNG bytes — Ed25519 takes any 32-byte string as a private
        // seed, no curve-specific clamping needed.
        val seed = nextSecureRandomBytes(Ed25519Backend.ED25519_SEED_SIZE)
        val publicKey = ed25519.derivePublicKey(seed)
        return SyncCryptoManager.KeyPair(publicKey = publicKey, privateKey = seed)
    }

    override fun generateAdmissionKeyPair(): SyncCryptoManager.KeyPair =
        // Ed25519 admission keypair (asset A33) — signs new-device delegations and
        // child capability certs, so it MUST be Ed25519 like A4, not the X25519
        // shape the interface default's generateKeyPair() would produce. Without
        // this override every real-crypto A33 signature failed relay verification
        // (caught by DelegatedAdmissionSystemTest — stub crypto's pub==priv
        // invariant masked it in every unit test).
        generateDeviceSigningKeyPair()

    override fun calculateSharedSecret(
        privateKey: ByteArray,
        otherPublicKey: ByteArray
    ): ByteArray = x25519.calculateSharedSecret(privateKey, otherPublicKey)

    override fun deriveMasterKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair {
        val entropy = Bip39.decode(recoveryPhrase)
        val seed = hkdfSha256(
            ikm = entropy,
            salt = ByteArray(0),
            info = MASTER_KEYPAIR_HKDF_INFO,
            length = Ed25519Backend.ED25519_SEED_SIZE,
        )
        val publicKey = ed25519.derivePublicKey(seed)
        return SyncCryptoManager.KeyPair(publicKey = publicKey, privateKey = seed)
    }

    override fun deriveRealmEncryptionKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair {
        // Asset A13 (tenshu-rei.md §2.3): the realm identity's X25519 encryption
        // sub-key. Same recovery-code entropy as A1, but a DISTINCT HKDF info
        // tag (and a distinct curve) — HKDF guarantees the two 32-byte seeds are
        // independent, so A1 (Ed25519) and A13 (X25519) are cryptographically
        // unrelated despite the shared recovery phrase. Deterministic so A13 is
        // re-derivable from the recovery code (A9) — covered by pairing +
        // revocation together with A1.
        val entropy = Bip39.decode(recoveryPhrase)
        val seed = hkdfSha256(
            ikm = entropy,
            salt = ByteArray(0),
            info = REALM_ENC_KEYPAIR_HKDF_INFO,
            length = X25519Backend.X25519_KEY_SIZE,
        )
        val pair = x25519.deriveKeyPair(seed)
        return SyncCryptoManager.KeyPair(publicKey = pair.publicKey, privateKey = pair.privateKey)
    }

    override fun wrapKeyForIdentity(key: ByteArray, recipientEncPublicKey: ByteArray): ByteArray =
        // The A12 key wrap is the `info = "tenshu/key-wrap/v1", aad = empty`
        // instance of the generalised sealed box: KEY_WRAP_AAD_PREFIX and
        // KEY_WRAP_HKDF_INFO are the same bytes, so `info || ephemeralPub ||
        // (empty)` reproduces the historical `prefix || ephemeralPub` AAD
        // exactly — pre-existing wrapped blobs stay readable.
        sealToIdentity(key, recipientEncPublicKey, info = KEY_WRAP_HKDF_INFO)

    override fun unwrapKeyForIdentity(wrapped: ByteArray, myEncPrivateKey: ByteArray): ByteArray =
        unsealFromIdentity(wrapped, myEncPrivateKey, info = KEY_WRAP_HKDF_INFO)

    override fun sealToIdentity(
        plaintext: ByteArray,
        recipientEncPublicKey: ByteArray,
        info: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        // Ephemeral-static X25519 sealed box (contact-invitation-handshake.md
        // §5.1). A fresh ephemeral keypair per call → AEAD nonce per call ⇒
        // two seals of the same (plaintext, recipient) produce different
        // blobs, each opening to the same plaintext. The ephemeral private
        // key is discarded (forward-secret w.r.t. the sealer).
        val ephemeral = x25519.generateKeyPair()
        val shared = x25519.calculateSharedSecret(ephemeral.privateKey, recipientEncPublicKey)
        val wrapKey = hkdfSha256(
            ikm = shared,
            salt = ByteArray(0),
            info = info,
            length = AeadBackend.AEAD_KEY_SIZE,
        )
        // encryptWithAad (interface default) frames the AAD inside the AEAD
        // ciphertext; binding the ephemeral pub pins the blob to this
        // exchange, the caller AAD pins it to its protocol context.
        val sealed = encryptWithAad(plaintext, wrapKey, sealAad(info, ephemeral.publicKey, aad))
        return ephemeral.publicKey + sealed
    }

    override fun unsealFromIdentity(
        sealed: ByteArray,
        myEncPrivateKey: ByteArray,
        info: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(sealed.size > X25519Backend.X25519_KEY_SIZE) {
            "sealed blob too short: ${sealed.size} bytes"
        }
        val ephemeralPub = sealed.copyOfRange(0, X25519Backend.X25519_KEY_SIZE)
        val body = sealed.copyOfRange(X25519Backend.X25519_KEY_SIZE, sealed.size)
        val shared = x25519.calculateSharedSecret(myEncPrivateKey, ephemeralPub)
        val wrapKey = hkdfSha256(
            ikm = shared,
            salt = ByteArray(0),
            info = info,
            length = AeadBackend.AEAD_KEY_SIZE,
        )
        return decryptWithAad(body, wrapKey, sealAad(info, ephemeralPub, aad))
    }

    private fun sealAad(info: ByteArray, ephemeralPub: ByteArray, aad: ByteArray): ByteArray =
        info + ephemeralPub + aad

    override fun generateSharedRealmMasterKey(): ByteArray =
        // Asset A12: a full-entropy random 32-byte symmetric key. No phrase, no
        // KDF — a shared realm's root is random and only ever distributed wrapped
        // ([wrapKeyForIdentity]).
        nextSecureRandomBytes(AeadBackend.AEAD_KEY_SIZE)

    override fun deriveSharedRealmKeys(masterKey: ByteArray): SyncCryptoManager.DerivedKeys =
        // Pure HKDF domain-separation off the random A12 — no Argon2id (the input
        // is already full-entropy; Argon2id only stretches the low-entropy phrase
        // on the personal path). Distinct info tags from the personal
        // SHARED_*_HKDF_INFO so a shared realm's keys are independent of the
        // personal realm's.
        SyncCryptoManager.DerivedKeys(
            sharedIdentityKey = hkdfSha256(
                ikm = masterKey,
                salt = ByteArray(0),
                info = SHARED_REALM_IDENTITY_HKDF_INFO,
                length = AeadBackend.AEAD_KEY_SIZE,
            ),
            sharedVolumeKey = hkdfSha256(
                ikm = masterKey,
                salt = ByteArray(0),
                info = SHARED_REALM_VOLUME_HKDF_INFO,
                length = AeadBackend.AEAD_KEY_SIZE,
            ),
        )

    override fun signEd25519(privateKey: ByteArray, message: ByteArray): ByteArray =
        ed25519.sign(privateKey, message)

    override fun verifyEd25519(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray
    ): Boolean = ed25519.verify(publicKey, message, signature)

    private companion object {
        private val MASTER_KEYPAIR_HKDF_INFO =
            "tenshu/master-keypair-seed/v1".encodeToByteArray()
        private val AEAD_KEY_HKDF_INFO =
            "tenshu/aead-key/v1".encodeToByteArray()

        // Asset A13 (realm identity encryption sub-key) derivation — distinct
        // HKDF domain from MASTER_KEYPAIR_HKDF_INFO so A13 ⊥ A1.
        private val REALM_ENC_KEYPAIR_HKDF_INFO =
            "tenshu/realm-enc-keypair-seed/v1".encodeToByteArray()

        // A12 sealed-box wrap: HKDF info for turning the ECDH secret into the
        // AEAD wrap key. Doubles as the AAD domain prefix bound alongside the
        // ephemeral public key — sealToIdentity uses one `info` for both, and
        // the historical KEY_WRAP_AAD_PREFIX was the same bytes.
        private val KEY_WRAP_HKDF_INFO =
            "tenshu/key-wrap/v1".encodeToByteArray()

        // Fixed protocol salt for the recovery-flow Argon2id KDF. The
        // salt does not have to be secret (the recovery phrase itself is
        // the secret with 256 bits of entropy), but Argon2id requires
        // one ≥8 bytes; we use a domain-tagged constant so this KDF's
        // output can't collide with a future Argon2id derivation that
        // uses the same parameters but a different purpose tag (e.g.
        // slice D's IDB master-key wrapper).
        private val RECOVERY_KDF_SALT =
            "tenshu/recovery-kdf/v1".encodeToByteArray()

        // HKDF info tags for the two shared-key halves that come out of
        // the Argon2id-stretched material. Distinct tags so the two
        // halves are independent — slice D may need to rotate one
        // without the other.
        private val SHARED_IDENTITY_HKDF_INFO =
            "tenshu/derive-keys/shared-identity/v1".encodeToByteArray()
        private val SHARED_VOLUME_HKDF_INFO =
            "tenshu/derive-keys/shared-volume/v1".encodeToByteArray()

        // HKDF info tags for splitting a SHARED realm's random A12 master key into
        // its two data keys. Distinct domain from the personal SHARED_*_HKDF_INFO
        // above so the two realms' key spaces never collide.
        private val SHARED_REALM_IDENTITY_HKDF_INFO =
            "tenshu/shared-realm/shared-identity/v1".encodeToByteArray()
        private val SHARED_REALM_VOLUME_HKDF_INFO =
            "tenshu/shared-realm/shared-volume/v1".encodeToByteArray()
    }
}
