package org.tenshu.core.common.util

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

actual fun sha256(data: ByteArray): ByteArray {
    return MessageDigest.getInstance("SHA-256").digest(data)
}

actual fun pbkdf2Sha512(
    password: CharArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBits: Int
): ByteArray {
    val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
    val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
    return skf.generateSecret(spec).encoded
}
