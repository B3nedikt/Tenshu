package org.tenshu.core.common.util

import java.security.MessageDigest

/** JVM/Android incremental SHA-256 — the platform's own streaming digest. */
actual class Sha256Digest actual constructor() {

    private val digest = MessageDigest.getInstance("SHA-256")

    actual fun update(bytes: ByteArray, length: Int) {
        digest.update(bytes, 0, length)
    }

    actual fun digest(): ByteArray = digest.digest()
}
