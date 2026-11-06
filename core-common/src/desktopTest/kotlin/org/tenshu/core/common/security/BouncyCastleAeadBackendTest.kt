package org.tenshu.core.common.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// AEAD-shape correctness: encrypt/decrypt round-trips, length checks,
// authentication failure on tampered ciphertext. Doesn't pin known
// XChaCha20-Poly1305 vectors against the JVM impl alone — the cross-
// backend interop test (`AeadCrossBackendTest` in tenshu-sdk-js) is the
// real "does this match the spec" anchor.
class BouncyCastleAeadBackendTest {

    private val backend = BouncyCastleAeadBackend()

    @Test
    fun encryptDecrypt_roundTrips() {
        val key = ByteArray(AeadBackend.AEAD_KEY_SIZE) { it.toByte() }
        val plaintext = "tenshu phase 4 slice b".encodeToByteArray()
        val ciphertext = backend.encrypt(plaintext, key)
        // Layout: nonce (24) || ciphertext (len) || tag (16).
        assertTrue(
            ciphertext.size == plaintext.size +
                    AeadBackend.AEAD_NONCE_SIZE +
                    AeadBackend.AEAD_TAG_SIZE,
            "ciphertext blob should be plaintext + 40 bytes overhead",
        )
        assertContentEquals(plaintext, backend.decrypt(ciphertext, key))
    }

    @Test
    fun encrypt_emitsDifferentCiphertextEachCall_dueToRandomNonce() {
        val key = ByteArray(AeadBackend.AEAD_KEY_SIZE) { 0x42 }
        val plaintext = "deterministic input".encodeToByteArray()
        val a = backend.encrypt(plaintext, key)
        val b = backend.encrypt(plaintext, key)
        assertNotEquals(a.toList(), b.toList(), "fresh nonce must produce fresh ciphertext")
        assertContentEquals(plaintext, backend.decrypt(a, key))
        assertContentEquals(plaintext, backend.decrypt(b, key))
    }

    @Test
    fun decrypt_rejectsTamperedCiphertext() {
        val key = ByteArray(AeadBackend.AEAD_KEY_SIZE) { 0x55 }
        val ciphertext = backend.encrypt("payload".encodeToByteArray(), key)
        // Flip a bit in the body (not the nonce).
        val tampered = ciphertext.copyOf()
        tampered[AeadBackend.AEAD_NONCE_SIZE] = (tampered[AeadBackend.AEAD_NONCE_SIZE].toInt() xor 1).toByte()
        assertFailsWith<AeadAuthenticationException> { backend.decrypt(tampered, key) }
    }

    @Test
    fun decrypt_rejectsWrongKey() {
        val keyA = ByteArray(AeadBackend.AEAD_KEY_SIZE) { 0x11 }
        val keyB = ByteArray(AeadBackend.AEAD_KEY_SIZE) { 0x22 }
        val ciphertext = backend.encrypt("payload".encodeToByteArray(), keyA)
        assertFailsWith<AeadAuthenticationException> { backend.decrypt(ciphertext, keyB) }
    }

    @Test
    fun encrypt_rejectsWrongKeyLength() {
        assertFailsWith<IllegalArgumentException> {
            backend.encrypt("x".encodeToByteArray(), ByteArray(31))
        }
    }

    @Test
    fun decrypt_rejectsTooShortCiphertext() {
        val key = ByteArray(AeadBackend.AEAD_KEY_SIZE)
        // Anything shorter than nonce + tag = 40 bytes can't be a valid
        // ciphertext — fail fast with a typed exception.
        assertFailsWith<IllegalArgumentException> { backend.decrypt(ByteArray(39), key) }
    }

    @Test
    fun encrypt_supportsEmptyPlaintext() {
        val key = ByteArray(AeadBackend.AEAD_KEY_SIZE) { 0x77 }
        val ciphertext = backend.encrypt(ByteArray(0), key)
        assertContentEquals(ByteArray(0), backend.decrypt(ciphertext, key))
    }
}
