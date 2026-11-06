package org.tenshu.core.common.security

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.tenshu.core.common.util.nextSecureRandomBytes

// JS AEAD backend, backed by `@noble/ciphers/chacha`'s
// `xchacha20poly1305`. Sync API, same shape as the Ed25519/X25519
// backends from slices A/B-X25519.
//
// `xchacha20poly1305(key, nonce)` returns an object with `encrypt`/
// `decrypt` methods; both are sync and produce/consume `Uint8Array`s.
// The combined ciphertext+tag layout matches what BouncyCastle's
// `ChaCha20Poly1305` produces (ciphertext || tag(16)), so the
// nonce-prefixed self-contained format is byte-for-byte interoperable.
@JsModule("@noble/ciphers/chacha")
private external object NobleChacha {
    fun xchacha20poly1305(key: Uint8Array, nonce: Uint8Array): NobleAeadCipher
}

private external interface NobleAeadCipher {
    fun encrypt(plaintext: Uint8Array): Uint8Array
    fun decrypt(ciphertext: Uint8Array): Uint8Array
}

class NobleAeadBackend : AeadBackend {

    override fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == AeadBackend.AEAD_KEY_SIZE) {
            "AEAD key must be ${AeadBackend.AEAD_KEY_SIZE} bytes; got ${key.size}"
        }
        val nonce = nextSecureRandomBytes(AeadBackend.AEAD_NONCE_SIZE)
        val cipher = NobleChacha.xchacha20poly1305(key.toUint8Array(), nonce.toUint8Array())
        val ctAndTag = cipher.encrypt(plaintext.toUint8Array()).toByteArray()
        // Layout: nonce (24) || ciphertext (|plaintext|) || tag (16).
        val out = ByteArray(nonce.size + ctAndTag.size)
        nonce.copyInto(out, 0)
        ctAndTag.copyInto(out, nonce.size)
        return out
    }

    override fun decrypt(ciphertext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == AeadBackend.AEAD_KEY_SIZE) {
            "AEAD key must be ${AeadBackend.AEAD_KEY_SIZE} bytes; got ${key.size}"
        }
        require(ciphertext.size >= AeadBackend.AEAD_MIN_CIPHERTEXT_SIZE) {
            "AEAD ciphertext too short: ${ciphertext.size} < ${AeadBackend.AEAD_MIN_CIPHERTEXT_SIZE}"
        }
        val nonce = ciphertext.copyOfRange(0, AeadBackend.AEAD_NONCE_SIZE)
        val body = ciphertext.copyOfRange(AeadBackend.AEAD_NONCE_SIZE, ciphertext.size)
        val cipher = NobleChacha.xchacha20poly1305(key.toUint8Array(), nonce.toUint8Array())
        return try {
            cipher.decrypt(body.toUint8Array()).toByteArray()
        } catch (t: Throwable) {
            // @noble/ciphers throws on tag-mismatch with a generic
            // `Error("invalid tag")`; map to the typed boundary so
            // `Session.unlock` and the sync layer can route the error
            // the same way they do on JVM.
            throw AeadAuthenticationException("XChaCha20-Poly1305 decrypt failed: ${t.message}")
        }
    }
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val arr = Uint8Array(size)
    for (i in indices) arr.asDynamic()[i] = this[i].toInt() and 0xFF
    return arr
}

private fun Uint8Array.toByteArray(): ByteArray {
    val result = ByteArray(length)
    for (i in 0 until length) result[i] = this[i]
    return result
}
