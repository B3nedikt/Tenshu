package org.tenshu.core.common.security

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.util.Arrays
import java.security.SecureRandom

// JVM AEAD backend. XChaCha20-Poly1305 per draft-irtf-cfrg-xchacha and
// RFC 8439. BouncyCastle ships ChaCha20-Poly1305 (12-byte nonce) but
// not the X variant directly; we build XChaCha20 by HChaCha20-deriving
// a sub-key from the 24-byte nonce's first 16 bytes and using the last
// 8 bytes (zero-prefixed to 12) as the inner ChaCha20-Poly1305 nonce.
// This is the standard XChaCha20 construction (see
// draft-irtf-cfrg-xchacha §2.2).
class BouncyCastleAeadBackend : AeadBackend {

    private val secureRandom = SecureRandom()

    override fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == AeadBackend.AEAD_KEY_SIZE) {
            "AEAD key must be ${AeadBackend.AEAD_KEY_SIZE} bytes; got ${key.size}"
        }
        val nonce = ByteArray(AeadBackend.AEAD_NONCE_SIZE).also { secureRandom.nextBytes(it) }
        val ctAndTag = chacha20Poly1305(
            forEncryption = true,
            key = subKey(key, nonce),
            nonce = innerNonce(nonce),
            input = plaintext,
        )
        // Output layout: nonce (24) || ciphertext (|plaintext|) || tag (16).
        val out = ByteArray(nonce.size + ctAndTag.size)
        System.arraycopy(nonce, 0, out, 0, nonce.size)
        System.arraycopy(ctAndTag, 0, out, nonce.size, ctAndTag.size)
        return out
    }

    override fun decrypt(ciphertext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == AeadBackend.AEAD_KEY_SIZE) {
            "AEAD key must be ${AeadBackend.AEAD_KEY_SIZE} bytes; got ${key.size}"
        }
        require(ciphertext.size >= AeadBackend.AEAD_MIN_CIPHERTEXT_SIZE) {
            "AEAD ciphertext too short: ${ciphertext.size} < ${AeadBackend.AEAD_MIN_CIPHERTEXT_SIZE}"
        }
        val nonce = Arrays.copyOfRange(ciphertext, 0, AeadBackend.AEAD_NONCE_SIZE)
        val body = Arrays.copyOfRange(ciphertext, AeadBackend.AEAD_NONCE_SIZE, ciphertext.size)
        return try {
            chacha20Poly1305(
                forEncryption = false,
                key = subKey(key, nonce),
                nonce = innerNonce(nonce),
                input = body,
            )
        } catch (e: Throwable) {
            throw AeadAuthenticationException("XChaCha20-Poly1305 decrypt failed: ${e.message}")
        }
    }

    private fun chacha20Poly1305(
        forEncryption: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(forEncryption, ParametersWithIV(KeyParameter(key), nonce))
        val out = ByteArray(cipher.getOutputSize(input.size))
        val produced = cipher.processBytes(input, 0, input.size, out, 0)
        val finalProduced = cipher.doFinal(out, produced)
        val totalLen = produced + finalProduced
        return if (totalLen == out.size) out else Arrays.copyOf(out, totalLen)
    }

    // HChaCha20 — derive a 256-bit sub-key from (key, first-16-bytes-of-nonce).
    // Per draft-irtf-cfrg-xchacha §2.2: HChaCha20(key, nonce[0..15]) -> 32-byte
    // sub-key, then ChaCha20-Poly1305 runs with that sub-key + a 12-byte
    // inner nonce (4 zero bytes || nonce[16..23]).
    private fun subKey(key: ByteArray, nonce: ByteArray): ByteArray {
        return HChaCha20.derive(key, Arrays.copyOfRange(nonce, 0, 16))
    }

    private fun innerNonce(nonce: ByteArray): ByteArray {
        // 12-byte inner nonce = 4 zero bytes || nonce[16..23].
        val inner = ByteArray(12)
        System.arraycopy(nonce, 16, inner, 4, 8)
        return inner
    }
}

// Pure-Kotlin HChaCha20 implementation per RFC 7539's ChaCha20 block
// function, modified to skip the addition-of-state-back step and
// extract words 0..3 + 12..15 (the "outer" state). Matches the
// reference in draft-irtf-cfrg-xchacha §2.2.
//
// BouncyCastle's `ChaChaEngine` is the full ChaCha20 block; HChaCha20 is
// a compact stand-alone derivation we'd otherwise have to extract. ~20
// lines of code; cheap enough not to take on another dependency.
private object HChaCha20 {
    private const val ROUNDS = 20

    fun derive(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == 32) { "HChaCha20 key must be 32 bytes" }
        require(nonce16.size == 16) { "HChaCha20 nonce must be 16 bytes" }

        // Initial state: 4 constant words + 8 key words + 4 nonce words.
        val state = IntArray(16)
        state[0] = 0x61707865.toInt(); state[1] = 0x3320646e.toInt()
        state[2] = 0x79622d32.toInt(); state[3] = 0x6b206574.toInt()
        for (i in 0 until 8) state[4 + i] = leI32(key, i * 4)
        for (i in 0 until 4) state[12 + i] = leI32(nonce16, i * 4)

        // 20 ChaCha20 rounds (10 double rounds), no add-back.
        repeat(ROUNDS / 2) {
            quarterRound(state, 0, 4, 8, 12)
            quarterRound(state, 1, 5, 9, 13)
            quarterRound(state, 2, 6, 10, 14)
            quarterRound(state, 3, 7, 11, 15)
            quarterRound(state, 0, 5, 10, 15)
            quarterRound(state, 1, 6, 11, 12)
            quarterRound(state, 2, 7, 8, 13)
            quarterRound(state, 3, 4, 9, 14)
        }

        // Extract: words 0..3 || 12..15.
        val out = ByteArray(32)
        for (i in 0 until 4) putLeI32(out, i * 4, state[i])
        for (i in 0 until 4) putLeI32(out, 16 + i * 4, state[12 + i])
        return out
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun leI32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
                ((b[off + 1].toInt() and 0xff) shl 8) or
                ((b[off + 2].toInt() and 0xff) shl 16) or
                ((b[off + 3].toInt() and 0xff) shl 24)

    private fun putLeI32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v ushr 8) and 0xff).toByte()
        b[off + 2] = ((v ushr 16) and 0xff).toByte()
        b[off + 3] = ((v ushr 24) and 0xff).toByte()
    }
}
