package org.tenshu.core.common.util

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get

private external interface Crypto {
    fun getRandomValues(array: Uint8Array): Uint8Array
}

private external val crypto: Crypto

actual fun nextSecureRandomBytes(size: Int): ByteArray {
    val arr = Uint8Array(size)
    crypto.getRandomValues(arr)
    val result = ByteArray(size)
    for (i in 0 until size) {
        result[i] = arr[i]
    }
    return result
}
