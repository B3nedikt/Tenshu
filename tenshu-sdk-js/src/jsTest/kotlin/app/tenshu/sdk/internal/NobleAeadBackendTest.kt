package app.tenshu.sdk.internal

import org.tenshu.core.common.security.AeadAuthenticationException
import org.tenshu.core.common.security.AeadBackend
import org.tenshu.core.common.security.NobleAeadBackend
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

// AEAD-shape correctness for the @noble/ciphers backend. Same suite as
// `BouncyCastleAeadBackendTest` on the JVM side so a regression in
// either backend surfaces against the same fixtures.
class NobleAeadBackendTest {

    private val backend: AeadBackend = NobleAeadBackend()

    @Test
    fun encryptDecrypt_roundTrips() {
        val key = ByteArray(AeadBackend.AEAD_KEY_SIZE) { it.toByte() }
        val plaintext = "tenshu phase 4 slice b".encodeToByteArray()
        val ciphertext = backend.encrypt(plaintext, key)
        assertContentEquals(plaintext, backend.decrypt(ciphertext, key))
    }

    @Test
    fun encrypt_emitsDifferentCiphertextEachCall_dueToRandomNonce() {
        val key = ByteArray(AeadBackend.AEAD_KEY_SIZE) { 0x42 }
        val plaintext = "deterministic input".encodeToByteArray()
        val a = backend.encrypt(plaintext, key)
        val b = backend.encrypt(plaintext, key)
        assertNotEquals(a.toList(), b.toList())
        assertContentEquals(plaintext, backend.decrypt(a, key))
        assertContentEquals(plaintext, backend.decrypt(b, key))
    }

    @Test
    fun decrypt_rejectsTamperedCiphertext() {
        val key = ByteArray(AeadBackend.AEAD_KEY_SIZE) { 0x55 }
        val ciphertext = backend.encrypt("payload".encodeToByteArray(), key)
        val tampered = ciphertext.copyOf()
        tampered[AeadBackend.AEAD_NONCE_SIZE] =
            (tampered[AeadBackend.AEAD_NONCE_SIZE].toInt() xor 1).toByte()
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
        assertFailsWith<IllegalArgumentException> { backend.decrypt(ByteArray(39), key) }
    }
}
