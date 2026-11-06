package org.tenshu.core.common.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Test vectors from RFC 4231 (HMAC-SHA-256) and RFC 5869 (HKDF-SHA-256).
 * The named cases below match the spec'd test-case numbering so it's
 * easy to look up.
 */
class HkdfTest {

    // RFC 4231 §4.2 — HMAC-SHA-256 Test Case 1
    @Test
    fun hmacSha256_rfc4231_case1() {
        val key = "0b".repeat(20).hexDecode()
        val data = "Hi There".encodeToByteArray()
        val expected = "b0344c61d8db38535ca8afceaf0bf12b" +
                "881dc200c9833da726e9376c2e32cff7"
        assertEquals(expected, hmacSha256(key, data).toHexString())
    }

    // RFC 4231 §4.3 — HMAC-SHA-256 Test Case 2 (key shorter than block size)
    @Test
    fun hmacSha256_rfc4231_case2() {
        val key = "Jefe".encodeToByteArray()
        val data = "what do ya want for nothing?".encodeToByteArray()
        val expected = "5bdcc146bf60754e6a042426089575c7" +
                "5a003f089d2739839dec58b964ec3843"
        assertEquals(expected, hmacSha256(key, data).toHexString())
    }

    // RFC 4231 §4.6 — HMAC-SHA-256 Test Case 5 (truncated MAC; we keep full output)
    @Test
    fun hmacSha256_rfc4231_case5_full() {
        val key = "0c".repeat(20).hexDecode()
        val data = "Test With Truncation".encodeToByteArray()
        val expected = "a3b6167473100ee06e0c796c2955552b" +
                "fa6f7c0a6a8aef8b93f860aab0cd20c5"
        assertEquals(expected, hmacSha256(key, data).toHexString())
    }

    // RFC 5869 §A.1 — HKDF-SHA-256 Test Case 1 (basic)
    @Test
    fun hkdfSha256_rfc5869_case1() {
        val ikm = "0b".repeat(22).hexDecode()
        val salt = "000102030405060708090a0b0c".hexDecode()
        val info = "f0f1f2f3f4f5f6f7f8f9".hexDecode()
        val expected = "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"
        assertEquals(expected, hkdfSha256(ikm, salt, info, length = 42).toHexString())
    }

    // RFC 5869 §A.2 — HKDF-SHA-256 Test Case 2 (longer inputs/outputs)
    @Test
    fun hkdfSha256_rfc5869_case2() {
        val ikm = (0..0x4f).joinToString("") { it.toString(16).padStart(2, '0') }.hexDecode()
        val salt = (0x60..0xaf).joinToString("") { it.toString(16).padStart(2, '0') }.hexDecode()
        val info = (0xb0..0xff).joinToString("") { it.toString(16).padStart(2, '0') }.hexDecode()
        val expected = "b11e398dc80327a1c8e7f78c596a4934" +
                "4f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09" +
                "da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f" +
                "1d87"
        assertEquals(expected, hkdfSha256(ikm, salt, info, length = 82).toHexString())
    }

    // RFC 5869 §A.3 — HKDF-SHA-256 Test Case 3 (zero-length salt + info)
    @Test
    fun hkdfSha256_rfc5869_case3() {
        val ikm = "0b".repeat(22).hexDecode()
        val expected = "8da4e775a563c18f715f802a063c5a31" +
                "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8"
        assertEquals(
            expected,
            hkdfSha256(ikm, ByteArray(0), ByteArray(0), length = 42).toHexString(),
        )
    }
}

private fun String.hexDecode(): ByteArray {
    require(length % 2 == 0) { "Hex must be even length" }
    val out = ByteArray(length / 2)
    for (i in 0 until length step 2) {
        out[i / 2] = ((digit(this[i]) shl 4) or digit(this[i + 1])).toByte()
    }
    return out
}

private fun digit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> error("Bad hex char $c")
}
