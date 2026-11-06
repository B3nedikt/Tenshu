package org.tenshu.core.common.security

import org.tenshu.core.common.util.Bip39
import org.tenshu.core.common.util.Bip39InvalidMnemonicException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Integration tests for DefaultSyncCryptoManager wired against the real
// BouncyCastle backends (slices A + B + C). Pins the contracts the
// Phase 4 slices deliver:
//
//   - signEd25519/verifyEd25519 round-trip through the Ed25519 backend.
//   - deriveMasterKeyPair is deterministic for the same mnemonic.
//   - deriveMasterKeyPair rejects malformed mnemonics with the typed
//     Bip39InvalidMnemonicException (production callers either pre-
//     validate or pass a freshly-generated mnemonic, so this is the
//     programmer-error path).
//   - Different mnemonics produce different master keys.
//   - deriveKeys (slice C — Argon2id over BIP-39 entropy + HKDF) is
//     deterministic, produces independent halves, and is distinct from
//     the master-key derivation path.
//
// Some tests exercise the production Argon2id parameters
// (`KdfBackend.RECOVERY_KDF_*`, m=64 MiB / t=3) — these are intentionally
// slow (~1 s/call) so the full file takes a few seconds; not a tight loop.
class DefaultSyncCryptoManagerTest {

    private val crypto = DefaultSyncCryptoManager(
        ed25519 = BouncyCastleEd25519Backend(),
        x25519 = BouncyCastleX25519Backend(),
        aead = BouncyCastleAeadBackend(),
        kdf = BouncyCastleKdfBackend(),
    )

    @Test
    fun signAndVerify_roundTrip() {
        val seed = ByteArray(32) { it.toByte() }
        val message = "tenshu".encodeToByteArray()
        val signature = crypto.signEd25519(seed, message)
        // Derive the matching public key the same way the backend does.
        val pub = BouncyCastleEd25519Backend().derivePublicKey(seed)
        assertTrue(crypto.verifyEd25519(pub, message, signature))
        assertFalse(crypto.verifyEd25519(pub, "tampered".encodeToByteArray(), signature))
    }

    @Test
    fun deriveMasterKeyPair_isDeterministic() {
        val phrase = Bip39.generateMnemonic()
        val a = crypto.deriveMasterKeyPair(phrase)
        val b = crypto.deriveMasterKeyPair(phrase)
        assertContentEquals(a.privateKey, b.privateKey)
        assertContentEquals(a.publicKey, b.publicKey)
    }

    @Test
    fun deriveMasterKeyPair_signaturesVerifyAgainstDerivedPublicKey() {
        // Slice A's load-bearing claim: a key derived from a mnemonic on
        // one device produces signatures verifiable against the same
        // derived public key on another. Without that, `CreateRealm` and
        // `RecoveryClaim` can't agree on `(ownerId, masterPublicKey)`.
        val phrase = Bip39.generateMnemonic()
        val keypair = crypto.deriveMasterKeyPair(phrase)
        val message = "realm-bootstrap".encodeToByteArray()
        val signature = crypto.signEd25519(keypair.privateKey, message)
        assertTrue(crypto.verifyEd25519(keypair.publicKey, message, signature))
        assertEquals(Ed25519Backend.ED25519_PUBLIC_KEY_SIZE, keypair.publicKey.size)
        assertEquals(Ed25519Backend.ED25519_SEED_SIZE, keypair.privateKey.size)
    }

    @Test
    fun deriveMasterKeyPair_differentMnemonicsProduceDifferentKeys() {
        val a = crypto.deriveMasterKeyPair(Bip39.generateMnemonic())
        val b = crypto.deriveMasterKeyPair(Bip39.generateMnemonic())
        assertNotEquals(a.privateKey.toList(), b.privateKey.toList())
        assertNotEquals(a.publicKey.toList(), b.publicKey.toList())
    }

    @Test
    fun deriveMasterKeyPair_rejectsMalformedMnemonic() {
        assertFailsWith<Bip39InvalidMnemonicException.InvalidLength> {
            crypto.deriveMasterKeyPair("not a mnemonic")
        }
    }

    @Test
    fun deriveMasterKeyPair_isDistinctFromBip39EntropyAndSharedKeys() {
        // The HKDF domain-separation tags in DefaultSyncCryptoManager
        // guarantee the master-key seed is independent of any other key
        // derived from the same entropy. Without this guarantee, slice C
        // (Argon2id-stretched shared keys) and slice D (recovery-blob
        // wrapping) wouldn't be able to safely live alongside the master
        // identity derivation on the same mnemonic.
        val phrase = Bip39.generateMnemonic()
        val masterSeed = crypto.deriveMasterKeyPair(phrase).privateKey
        val entropy = Bip39.decode(phrase)
        assertNotEquals(entropy.toList(), masterSeed.toList())
        // `deriveKeys` Argon2id-stretches the same entropy and HKDF-
        // splits the result with distinct info tags from the master-key
        // derivation; the master seed must not collide with either half.
        val derived = crypto.deriveKeys(phrase)
        assertNotEquals(derived.sharedIdentityKey.toList(), masterSeed.toList())
        assertNotEquals(derived.sharedVolumeKey.toList(), masterSeed.toList())
    }

    @Test
    fun deriveKeys_isDeterministicAndProducesIndependentHalves() {
        // Slice C contract: Argon2id over the BIP-39 entropy with the
        // production `RECOVERY_KDF_*` parameters, then HKDF-domain-separated
        // into the shared-identity + shared-volume halves. Determinism is
        // load-bearing for recovery — a re-derivation on a fresh device
        // must produce the same shared keys. Independence between halves
        // is what the HKDF info tags buy us.
        val phrase = Bip39.generateMnemonic()
        val a = crypto.deriveKeys(phrase)
        val b = crypto.deriveKeys(phrase)
        assertContentEquals(a.sharedIdentityKey, b.sharedIdentityKey)
        assertContentEquals(a.sharedVolumeKey, b.sharedVolumeKey)
        assertEquals(AeadBackend.AEAD_KEY_SIZE, a.sharedIdentityKey.size)
        assertEquals(AeadBackend.AEAD_KEY_SIZE, a.sharedVolumeKey.size)
        assertNotEquals(
            a.sharedIdentityKey.toList(),
            a.sharedVolumeKey.toList(),
            "HKDF info tags must produce independent halves",
        )
    }

    @Test
    fun deriveKeys_rejectsMalformedMnemonic() {
        // `deriveKeys` decodes the mnemonic to entropy before Argon2id;
        // a malformed mnemonic must fail at decode time, not silently
        // produce zero-entropy output.
        assertFailsWith<Bip39InvalidMnemonicException.InvalidLength> {
            crypto.deriveKeys("not a mnemonic")
        }
    }

    // --- Shared-realm crypto (A13 sub-key + A12 wrap), tenshu-rei.md §2.3 ---

    @Test
    fun deriveRealmEncryptionKeyPair_isDeterministic() {
        // A13 must be re-derivable from the recovery code (recovery on a fresh
        // device + the "covered by pairing/revocation with A1" lifecycle).
        val phrase = Bip39.generateMnemonic()
        val a = crypto.deriveRealmEncryptionKeyPair(phrase)
        val b = crypto.deriveRealmEncryptionKeyPair(phrase)
        assertContentEquals(a.privateKey, b.privateKey)
        assertContentEquals(a.publicKey, b.publicKey)
        assertEquals(X25519Backend.X25519_KEY_SIZE, a.publicKey.size)
        assertEquals(X25519Backend.X25519_KEY_SIZE, a.privateKey.size)
    }

    @Test
    fun deriveRealmEncryptionKeyPair_differentMnemonicsProduceDifferentKeys() {
        val a = crypto.deriveRealmEncryptionKeyPair(Bip39.generateMnemonic())
        val b = crypto.deriveRealmEncryptionKeyPair(Bip39.generateMnemonic())
        assertNotEquals(a.privateKey.toList(), b.privateKey.toList())
        assertNotEquals(a.publicKey.toList(), b.publicKey.toList())
    }

    @Test
    fun realmEncryptionKey_isIndependentOfMasterKeyAndDerivedKeys() {
        // The distinct HKDF info tag (and distinct curve) make A13 independent
        // of A1 and of the shared keys, despite the shared recovery entropy —
        // the precondition for wrapping A12 to A13 without leaking A1.
        val phrase = Bip39.generateMnemonic()
        val a13 = crypto.deriveRealmEncryptionKeyPair(phrase)
        val a1 = crypto.deriveMasterKeyPair(phrase)
        assertNotEquals(a13.privateKey.toList(), a1.privateKey.toList())
        assertNotEquals(a13.publicKey.toList(), a1.publicKey.toList())
        val entropy = Bip39.decode(phrase)
        assertNotEquals(a13.privateKey.toList(), entropy.toList())
        val derived = crypto.deriveKeys(phrase)
        assertNotEquals(a13.privateKey.toList(), derived.sharedIdentityKey.toList())
        assertNotEquals(a13.privateKey.toList(), derived.sharedVolumeKey.toList())
    }

    @Test
    fun wrapKeyForIdentity_unwrapRoundTrips() {
        val masterKey = ByteArray(32) { (it * 7 + 1).toByte() } // a stand-in A12
        val member = crypto.deriveRealmEncryptionKeyPair(Bip39.generateMnemonic())
        val wrapped = crypto.wrapKeyForIdentity(masterKey, member.publicKey)
        // ephemeral pub prefix + sealed blob (nonce + ct + tag).
        assertTrue(wrapped.size > X25519Backend.X25519_KEY_SIZE)
        val unwrapped = crypto.unwrapKeyForIdentity(wrapped, member.privateKey)
        assertContentEquals(masterKey, unwrapped)
    }

    @Test
    fun unwrapKeyForIdentity_withWrongRecipientKey_fails() {
        // Recipient-binding via ECDH: wrap to member A, attempt unwrap with
        // member B's private key → wrong shared secret → AEAD tag fails.
        val masterKey = ByteArray(32) { it.toByte() }
        val memberA = crypto.deriveRealmEncryptionKeyPair(Bip39.generateMnemonic())
        val memberB = crypto.deriveRealmEncryptionKeyPair(Bip39.generateMnemonic())
        val wrapped = crypto.wrapKeyForIdentity(masterKey, memberA.publicKey)
        assertFailsWith<AeadAuthenticationException> {
            crypto.unwrapKeyForIdentity(wrapped, memberB.privateKey)
        }
    }

    @Test
    fun unwrapKeyForIdentity_tamperedBlobOrEphemeralPub_fails() {
        val masterKey = ByteArray(32) { (it + 3).toByte() }
        val member = crypto.deriveRealmEncryptionKeyPair(Bip39.generateMnemonic())
        val wrapped = crypto.wrapKeyForIdentity(masterKey, member.publicKey)

        // Flip a byte in the AEAD-blob region (tail) → Poly1305 fails.
        val tamperedBlob = wrapped.copyOf()
        tamperedBlob[tamperedBlob.size - 1] = (tamperedBlob[tamperedBlob.size - 1] + 1).toByte()
        assertFailsWith<AeadAuthenticationException> {
            crypto.unwrapKeyForIdentity(tamperedBlob, member.privateKey)
        }

        // Flip a byte in the ephemeral-pub region (head) → different shared
        // secret → different wrap key → AEAD tag fails.
        val tamperedPub = wrapped.copyOf()
        tamperedPub[0] = (tamperedPub[0] + 1).toByte()
        assertFailsWith<AeadAuthenticationException> {
            crypto.unwrapKeyForIdentity(tamperedPub, member.privateKey)
        }
    }

    @Test
    fun generateSharedRealmMasterKey_isFreshAndFullLength() {
        // Asset A12 (SR-3b): a random 32-byte symmetric key, fresh per call.
        val k1 = crypto.generateSharedRealmMasterKey()
        val k2 = crypto.generateSharedRealmMasterKey()
        assertEquals(AeadBackend.AEAD_KEY_SIZE, k1.size)
        assertNotEquals(k1.toList(), k2.toList(), "two A12s are independent random keys")
    }

    @Test
    fun deriveSharedRealmKeys_isDeterministicAndDomainSeparated() {
        // SR-3b: HKDF-split a random A12 into the two data keys — deterministic in
        // the master key, the two halves distinct, and independent of the personal
        // deriveKeys path (distinct HKDF info tags).
        val masterKey = crypto.generateSharedRealmMasterKey()
        val a = crypto.deriveSharedRealmKeys(masterKey)
        val b = crypto.deriveSharedRealmKeys(masterKey)
        assertEquals(a, b, "deterministic in the master key")
        assertEquals(AeadBackend.AEAD_KEY_SIZE, a.sharedIdentityKey.size)
        assertEquals(AeadBackend.AEAD_KEY_SIZE, a.sharedVolumeKey.size)
        assertNotEquals(
            a.sharedIdentityKey.toList(),
            a.sharedVolumeKey.toList(),
            "the two halves are domain-separated",
        )
        // A different master key yields different keys.
        assertNotEquals(
            a.sharedIdentityKey.toList(),
            crypto.deriveSharedRealmKeys(crypto.generateSharedRealmMasterKey()).sharedIdentityKey.toList(),
        )
    }

    @Test
    fun wrapKeyForIdentity_producesFreshBlobsThatBothUnwrap() {
        // Fresh ephemeral keypair + AEAD nonce per call → distinct ciphertexts,
        // each unwrapping to the same key. No deterministic-wrap leakage.
        val masterKey = ByteArray(32) { (it * 3).toByte() }
        val member = crypto.deriveRealmEncryptionKeyPair(Bip39.generateMnemonic())
        val w1 = crypto.wrapKeyForIdentity(masterKey, member.publicKey)
        val w2 = crypto.wrapKeyForIdentity(masterKey, member.publicKey)
        assertNotEquals(w1.toList(), w2.toList())
        assertContentEquals(masterKey, crypto.unwrapKeyForIdentity(w1, member.privateKey))
        assertContentEquals(masterKey, crypto.unwrapKeyForIdentity(w2, member.privateKey))
    }

    @Test
    fun x25519DeriveKeyPair_isDeterministicAndEcdhAgrees() {
        // Pins the deterministic-seed path the A13 derivation relies on.
        val backend = BouncyCastleX25519Backend()
        val seed = ByteArray(X25519Backend.X25519_KEY_SIZE) { (it + 1).toByte() }
        val a = backend.deriveKeyPair(seed)
        val b = backend.deriveKeyPair(seed)
        assertContentEquals(a.publicKey, b.publicKey)
        assertContentEquals(a.privateKey, b.privateKey)
        // A seed-derived keypair produces a curve-valid scalar: ECDH with a
        // randomly-generated peer agrees in both directions.
        val peer = backend.generateKeyPair()
        val s1 = backend.calculateSharedSecret(a.privateKey, peer.publicKey)
        val s2 = backend.calculateSharedSecret(peer.privateKey, a.publicKey)
        assertContentEquals(s1, s2)
    }
}
