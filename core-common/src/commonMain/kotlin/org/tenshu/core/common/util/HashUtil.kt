package org.tenshu.core.common.util

/**
 * Returns the SHA-256 hash of the given bytes.
 */
expect fun sha256(data: ByteArray): ByteArray

/**
 * Derives a key using PBKDF2-HMAC-SHA512.
 */
expect fun pbkdf2Sha512(
    password: CharArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBits: Int
): ByteArray
