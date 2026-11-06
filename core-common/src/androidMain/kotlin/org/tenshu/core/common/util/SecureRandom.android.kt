package org.tenshu.core.common.util

import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun nextSecureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    secureRandom.nextBytes(bytes)
    return bytes
}
