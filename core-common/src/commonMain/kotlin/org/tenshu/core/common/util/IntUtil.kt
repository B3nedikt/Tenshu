package org.tenshu.core.common.util

fun Int.toByteArray(): ByteArray {
    val result = ByteArray(Int.SIZE_BYTES)
    for (i in result.indices) {
        result[i] = ((this shr (8 * (Int.SIZE_BYTES - 1 - i))) and 0xFF).toByte()
    }
    return result
}

fun ByteArray.toInt(): Int {
    require(size == Int.SIZE_BYTES) {
        "ByteArray must be exactly ${Int.SIZE_BYTES} bytes long to convert to an Int. Was $size bytes."
    }

    var result = 0
    for (i in indices) {
        result = result or ((this[i].toInt() and 0xFF) shl (8 * (Int.SIZE_BYTES - 1 - i)))
    }
    return result
}
