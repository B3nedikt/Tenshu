package org.tenshu.core.common.util

/**
 * Returns cryptographically secure random bytes.
 */
expect fun nextSecureRandomBytes(size: Int): ByteArray
