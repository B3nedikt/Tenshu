package org.tenshu.core.common.util

/**
 * HMAC-SHA-256 per RFC 2104 + HKDF-SHA-256 per RFC 5869, in pure Kotlin
 * over the platform [sha256] expect. Used by the pairing SAS derivation
 * (`docs/proposals/sync-pairing-flow-improvements.md` §3.2) and any
 * future HKDF-shaped key derivation we need to keep cross-platform
 * without taking on a wider crypto dependency.
 */

private const val SHA256_BLOCK_SIZE = 64
private const val SHA256_OUTPUT_SIZE = 32

private const val HMAC_IPAD: Byte = 0x36
private const val HMAC_OPAD: Byte = 0x5C

/**
 * HMAC-SHA-256(key, message) per RFC 2104.
 */
fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val k = if (key.size > SHA256_BLOCK_SIZE) sha256(key) else key
    val padded = ByteArray(SHA256_BLOCK_SIZE)
    k.copyInto(padded, 0, 0, k.size)

    val inner = ByteArray(SHA256_BLOCK_SIZE + message.size)
    val outer = ByteArray(SHA256_BLOCK_SIZE + SHA256_OUTPUT_SIZE)
    for (i in 0 until SHA256_BLOCK_SIZE) {
        inner[i] = (padded[i].toInt() xor HMAC_IPAD.toInt()).toByte()
        outer[i] = (padded[i].toInt() xor HMAC_OPAD.toInt()).toByte()
    }
    message.copyInto(inner, SHA256_BLOCK_SIZE)
    val innerHash = sha256(inner)
    innerHash.copyInto(outer, SHA256_BLOCK_SIZE)
    return sha256(outer)
}

/**
 * HKDF-SHA-256 per RFC 5869. [length] is the desired output in bytes;
 * must be at most 255 * 32 = 8160. An empty [salt] is replaced with 32
 * zero bytes per the spec.
 */
fun hkdfSha256(
    ikm: ByteArray,
    salt: ByteArray,
    info: ByteArray,
    length: Int,
): ByteArray {
    require(length in 1..(255 * SHA256_OUTPUT_SIZE)) {
        "HKDF length out of range: $length"
    }
    val effectiveSalt = if (salt.isEmpty()) ByteArray(SHA256_OUTPUT_SIZE) else salt
    val prk = hmacSha256(effectiveSalt, ikm)

    val out = ByteArray(length)
    var t = ByteArray(0)
    var written = 0
    var counter = 1
    while (written < length) {
        val blockInput = ByteArray(t.size + info.size + 1)
        t.copyInto(blockInput, 0)
        info.copyInto(blockInput, t.size)
        blockInput[blockInput.size - 1] = counter.toByte()
        t = hmacSha256(prk, blockInput)
        val take = minOf(t.size, length - written)
        t.copyInto(out, written, 0, take)
        written += take
        counter++
    }
    return out
}
