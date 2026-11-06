package org.tenshu.core.common.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Cross-backend correctness for the iOS libsodium crypto backends
// (Phase 6a of docs/proposals/ios-proxy-architecture.md). Runs on the
// iosSimulatorArm64 test gate.
//
// The load-bearing property is **byte-for-byte agreement with the JVM
// (BouncyCastle) backend** — an iOS Drive client and a desktop/Android
// proxy must interoperate over the EPC channel and the synced wire formats.
// The fixed vectors below were produced by the BouncyCastle backends
// (deterministic for Ed25519/X25519/Argon2id; the AEAD blob is one
// JVM-encrypted message the iOS backend must be able to decrypt). If a
// future libsodium bump changes any output, these assertions fail loudly.
class LibsodiumCryptoBackendsTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    // ---- Ed25519 (vectors from BouncyCastleEd25519Backend) ----

    private val edSeed = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
    private val edMsg = "tenshu-epc-attestation".encodeToByteArray()
    private val edPub = hex("79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664")
    private val edSig = hex(
        "382092b945aa80beef7a323e548b24c909a963b9a40d5494cc80e4cb3175a1b1" +
            "d4c6c468c3d5d69cea183854bee51d9666315c26040019c6e4502e049590f404",
    )

    @Test
    fun ed25519_derivePublicKey_matchesJvmVector() {
        assertTrue(LibsodiumEd25519Backend().derivePublicKey(edSeed).contentEquals(edPub))
    }

    @Test
    fun ed25519_sign_isDeterministicAndMatchesJvmVector() {
        // RFC 8032 signatures are deterministic, so the iOS signature must be
        // byte-identical to the BouncyCastle one for the same seed + message.
        assertTrue(LibsodiumEd25519Backend().sign(edSeed, edMsg).contentEquals(edSig))
    }

    @Test
    fun ed25519_verify_acceptsValidRejectsTampered() {
        val backend = LibsodiumEd25519Backend()
        assertTrue(backend.verify(edPub, edMsg, edSig))
        // Flip a signature byte → reject.
        val badSig = edSig.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(backend.verify(edPub, edMsg, badSig))
        // Wrong message → reject.
        assertFalse(backend.verify(edPub, "other".encodeToByteArray(), edSig))
        // Length mismatch → false, not throw.
        assertFalse(backend.verify(ByteArray(31), edMsg, edSig))
    }

    @Test
    fun ed25519_signsEmptyMessage() {
        val backend = LibsodiumEd25519Backend()
        val sig = backend.sign(edSeed, ByteArray(0))
        assertTrue(backend.verify(edPub, ByteArray(0), sig))
    }

    // ---- X25519 (vectors from BouncyCastleX25519Backend) ----

    private val xPrivA = hex("0a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20212223242526272829")
    private val xPubA = hex("eb4622c9cb46365d41bd7016f52a93bcdc674e2f4ab0512444817caf0ab7722e")
    private val xPrivB = hex("c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7")
    private val xPubB = hex("4d5bab89b0733d9d8dcecf04f321c90b761b7765a6bdb2bddbfad3e7abdf1f66")
    private val xShared = hex("63ba4ae8fa40eb08c79718ba365404e7621eca69abb3cb0f9e680342c3d92116")

    @Test
    fun x25519_deriveKeyPair_matchesJvmVectors() {
        val backend = LibsodiumX25519Backend()
        assertTrue(backend.deriveKeyPair(xPrivA).publicKey.contentEquals(xPubA))
        assertTrue(backend.deriveKeyPair(xPrivB).publicKey.contentEquals(xPubB))
        // The stored private key is the raw scalar, unchanged.
        assertTrue(backend.deriveKeyPair(xPrivA).privateKey.contentEquals(xPrivA))
    }

    @Test
    fun x25519_sharedSecret_matchesJvmVectorAndIsSymmetric() {
        val backend = LibsodiumX25519Backend()
        assertTrue(backend.calculateSharedSecret(xPrivA, xPubB).contentEquals(xShared))
        // ECDH symmetry: B's private + A's public derives the same secret.
        assertTrue(backend.calculateSharedSecret(xPrivB, xPubA).contentEquals(xShared))
    }

    @Test
    fun x25519_generatedKeyPairs_agreeOnSharedSecret() {
        val backend = LibsodiumX25519Backend()
        val a = backend.generateKeyPair()
        val b = backend.generateKeyPair()
        assertEquals(X25519Backend.X25519_KEY_SIZE, a.publicKey.size)
        val s1 = backend.calculateSharedSecret(a.privateKey, b.publicKey)
        val s2 = backend.calculateSharedSecret(b.privateKey, a.publicKey)
        assertTrue(s1.contentEquals(s2))
    }

    @Test
    fun x25519_rejectsLowOrderPublicKey() {
        // All-zero public key is a low-order point — crypto_scalarmult returns
        // -1 and we surface it rather than handing back an all-zero secret.
        assertFailsWith<IllegalArgumentException> {
            LibsodiumX25519Backend().calculateSharedSecret(xPrivA, ByteArray(32))
        }
    }

    // ---- XChaCha20-Poly1305 AEAD (blob from BouncyCastleAeadBackend) ----

    private val aeadKey = hex("05060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f2021222324")
    private val aeadPlain = "shared-realm-frame".encodeToByteArray()

    // A complete nonce||ciphertext||tag blob produced by the JVM backend; the
    // iOS backend must decrypt it to the same plaintext (wire interop).
    private val aeadJvmBlob = hex(
        "92b41c66f1ffabf75afdb93655e98b584525b0ce22292a0fa016e64fef0ba5cf" +
            "5fad2e80ce2be32647117ecbd97ffd4431e539504bec72017d5a",
    )

    @Test
    fun aead_decryptsJvmProducedBlob() {
        val plaintext = LibsodiumAeadBackend().decrypt(aeadJvmBlob, aeadKey)
        assertTrue(plaintext.contentEquals(aeadPlain))
    }

    @Test
    fun aead_roundTrips() {
        val backend = LibsodiumAeadBackend()
        val blob = backend.encrypt(aeadPlain, aeadKey)
        // Self-contained format: nonce(24) || ciphertext || tag(16).
        assertEquals(AeadBackend.AEAD_NONCE_SIZE + aeadPlain.size + AeadBackend.AEAD_TAG_SIZE, blob.size)
        assertTrue(backend.decrypt(blob, aeadKey).contentEquals(aeadPlain))
    }

    @Test
    fun aead_roundTripsEmptyPlaintext() {
        val backend = LibsodiumAeadBackend()
        val blob = backend.encrypt(ByteArray(0), aeadKey)
        assertEquals(AeadBackend.AEAD_MIN_CIPHERTEXT_SIZE, blob.size)
        assertEquals(0, backend.decrypt(blob, aeadKey).size)
    }

    @Test
    fun aead_rejectsTamperedCiphertextAndWrongKey() {
        val backend = LibsodiumAeadBackend()
        val tampered = aeadJvmBlob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<AeadAuthenticationException> { backend.decrypt(tampered, aeadKey) }

        val wrongKey = aeadKey.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFailsWith<AeadAuthenticationException> { backend.decrypt(aeadJvmBlob, wrongKey) }
    }

    // ---- Argon2id (vector from BouncyCastleKdfBackend) ----

    @Test
    fun argon2id_matchesJvmVector_withProductionSalt() {
        // 22-byte salt — the production "tenshu/recovery-kdf/v1" — which
        // libsodium's salt-locked crypto_pwhash can't do, but argon2id_hash_raw
        // can. Output must equal BouncyCastle's for the same inputs.
        val out = LibsodiumKdfBackend().argon2id(
            password = "password".encodeToByteArray(),
            salt = "tenshu/recovery-kdf/v1".encodeToByteArray(),
            parallelism = 1,
            memoryKiB = 8,
            iterations = 1,
            outputLength = 32,
        )
        assertTrue(out.contentEquals(hex("e5ac1b570330cb467b1ab9df99955e0e90135f6b5d616cd730b9257f7cc4de55")))
    }

    @Test
    fun argon2id_isDeterministic() {
        val backend = LibsodiumKdfBackend()
        val args = { backend.argon2id("pw".encodeToByteArray(), "saltsalt".encodeToByteArray(), 1, 8, 1, 32) }
        assertTrue(args().contentEquals(args()))
    }
}
