package org.tenshu.inference.proxy.onnx

/**
 * A vocabulary in the packed format a device reads (`UnigramVocabulary`), built in the test.
 *
 * The header fields are parameters so a test can write a file that is wrong in exactly one way.
 */
internal fun packedVocabulary(
    pieces: List<Pair<String, Float>>,
    magic: String = "TSHU",
    version: Int = 1,
    count: Int = pieces.size,
    byteFallback: Boolean = true,
    unkId: Int = 2,
): ByteArray {
    fun intLe(value: Int): ByteArray = ByteArray(4) { (value ushr (8 * it)).toByte() }
    var out: ByteArray = magic.encodeToByteArray() + intLe(version) + intLe(count) +
        byteArrayOf(if (byteFallback) 1 else 0) + intLe(unkId)
    pieces.forEach { (piece, score) ->
        val utf8: ByteArray = piece.encodeToByteArray()
        out = out + intLe(score.toRawBits()) + byteArrayOf(utf8.size.toByte()) + utf8
    }
    return out
}
