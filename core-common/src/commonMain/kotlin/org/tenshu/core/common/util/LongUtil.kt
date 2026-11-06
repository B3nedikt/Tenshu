package org.tenshu.core.common.util

fun Long.toByteArray(): ByteArray {
    val result = ByteArray(Long.SIZE_BYTES)
    for (i in result.indices) {
        result[i] = ((this shr (8 * (Long.SIZE_BYTES - 1 - i))) and 0xFF).toByte()
    }
    return result
}

fun ByteArray.toLong(): Long {
    require(size == Long.SIZE_BYTES) {
        "ByteArray must be exactly ${Long.SIZE_BYTES} bytes long to convert to a Long. Was $size bytes."
    }

    var result = 0L
    for (i in indices) {
        result = result or ((this[i].toLong() and 0xFF) shl (8 * (Long.SIZE_BYTES - 1 - i)))
    }
    return result
}
