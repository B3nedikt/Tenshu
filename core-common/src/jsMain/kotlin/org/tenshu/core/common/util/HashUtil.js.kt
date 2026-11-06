package org.tenshu.core.common.util

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get

@JsModule("@noble/hashes/sha2")
private external object Sha2 {
    fun sha256(data: Uint8Array): Uint8Array
    fun sha512(data: Uint8Array): Uint8Array
}

@JsModule("@noble/hashes/pbkdf2")
private external object Pbkdf2 {
    fun pbkdf2(hash: dynamic, password: Uint8Array, salt: Uint8Array, options: dynamic): Uint8Array
}

@JsModule("@noble/hashes/utils")
private external object NobleUtils {
    fun utf8ToBytes(s: String): Uint8Array
}

actual fun sha256(data: ByteArray): ByteArray {
    val result = Sha2.sha256(data.toUint8Array())
    return result.toByteArray()
}

actual fun pbkdf2Sha512(
    password: CharArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBits: Int
): ByteArray {
    val passwordBytes = NobleUtils.utf8ToBytes(password.concatToString())
    val options = js("({})")
    options.c = iterations
    options.dkLen = keyLengthBits / 8
    val result = Pbkdf2.pbkdf2(Sha2.asDynamic().sha512, passwordBytes, salt.toUint8Array(), options)
    return result.toByteArray()
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val arr = Uint8Array(size)
    for (i in indices) {
        arr.asDynamic()[i] = this[i].toInt() and 0xFF
    }
    return arr
}

private fun Uint8Array.toByteArray(): ByteArray {
    val result = ByteArray(length)
    for (i in 0 until length) {
        result[i] = this[i]
    }
    return result
}
