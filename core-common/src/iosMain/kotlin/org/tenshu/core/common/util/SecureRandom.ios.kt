package org.tenshu.core.common.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun nextSecureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    // `addressOf(0)` on an empty array throws on Kotlin/Native, and a
    // zero-length request has nothing to fill — return the empty array as-is.
    if (size == 0) return bytes
    bytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0))
    }
    return bytes
}
