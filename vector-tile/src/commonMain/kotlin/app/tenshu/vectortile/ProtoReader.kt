package app.tenshu.vectortile

/**
 * A minimal, **bounds-checked** protobuf wire reader over a slice of a [ByteArray].
 *
 * Every method returns `null` (or `false`) on a truncated or malformed read rather than throwing —
 * tile bytes arrive from a CDN and are untrusted, so the decoder degrades to a partial result
 * instead of crashing a render or a classification.
 *
 * The repo already hand-rolls varints twice (`MovementTracePolyline.VarintReader`,
 * `PmtilesDirectory.Varint`); both are `private`, unbounded, and expose no position, so neither is
 * reusable for wire-type parsing — which needs to skip length-delimited fields it does not know.
 * This is the same algorithm with a cursor and a limit.
 */
internal class ProtoReader(
    private val bytes: ByteArray,
    private var pos: Int,
    private val limit: Int,
) {
    fun hasMore(): Boolean = pos < limit

    /** Reads a field key. Returns `(fieldNumber, wireType)`, or `null` if truncated. */
    fun readTag(): Pair<Int, Int>? {
        val key = readVarint() ?: return null
        return (key ushr 3).toInt() to (key and 0x7).toInt()
    }

    fun readVarint(): Long? {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= limit) return null
            // A varint longer than 10 bytes is malformed; refuse rather than shifting off the end.
            if (shift > 63) return null
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    /** Reads a length-delimited field, returning its `[start, end)` slice without copying. */
    fun readLengthDelimited(): Pair<Int, Int>? {
        val len = readVarint() ?: return null
        if (len < 0 || len > (limit - pos)) return null
        val start = pos
        pos += len.toInt()
        return start to pos
    }

    fun readFixed32(): Int? {
        if (pos + 4 > limit) return null
        var v = 0
        for (i in 0 until 4) v = v or ((bytes[pos + i].toInt() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    fun readFixed64(): Long? {
        if (pos + 8 > limit) return null
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    /** Skips a field of the given wire type. `false` when the field is malformed or truncated. */
    fun skip(wireType: Int): Boolean = when (wireType) {
        MvtDecoder.WIRE_VARINT -> readVarint() != null
        MvtDecoder.WIRE_I64 -> readFixed64() != null
        MvtDecoder.WIRE_LEN -> readLengthDelimited() != null
        MvtDecoder.WIRE_I32 -> readFixed32() != null
        else -> false // groups (3/4) are not used by MVT and are not worth supporting
    }
}
