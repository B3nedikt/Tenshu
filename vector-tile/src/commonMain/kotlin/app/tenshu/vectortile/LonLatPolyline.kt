package app.tenshu.vectortile

import kotlin.math.roundToLong

/**
 * A compact wire codec for a lon/lat polyline — how rail geometry crosses the proxy↔app boundary
 * (`docs/proposals/timeline-movement-capture.md` §13.3, M9-4).
 *
 * Same shape as `MovementTracePolyline`, deliberately: E7 integers, first point absolute, the rest
 * zig-zag varint deltas. Rail geometry is dense and locally smooth, so deltas are small and a line
 * packs to a few bytes per point instead of the ~32 a pair of doubles would cost. That matters
 * because a bbox around a real trace can pull in dozens of lines, and they all ride one IPC message.
 *
 * Separate from `MovementTracePolyline` rather than shared: that one carries `(latE7, lngE7,
 * timestampMs, accuracyM)` because a *trace* has time and quality; a reference line has neither, and
 * threading two zero streams through it purely to reuse the function would cost more bytes than the
 * duplication costs lines.
 *
 * Untrusted input on decode (it crossed a process boundary): malformed bytes yield an empty list.
 */
object LonLatPolyline {

    fun encode(points: List<LonLat>): ByteArray {
        val out = ArrayList<Byte>()
        writeVarint(out, points.size.toLong())
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = (p.lat * 1e7).roundToLong()
            val lon = (p.lon * 1e7).roundToLong()
            writeZigZag(out, lat - prevLat)
            writeZigZag(out, lon - prevLon)
            prevLat = lat
            prevLon = lon
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): List<LonLat> {
        if (bytes.isEmpty()) return emptyList()
        return runCatching {
            val r = ProtoReader(bytes, 0, bytes.size)
            val count = (r.readVarint() ?: return emptyList()).toInt()
            if (count < 0) return emptyList()
            val out = ArrayList<LonLat>(count.coerceAtMost(4096))
            var lat = 0L
            var lon = 0L
            repeat(count) {
                lat += zigZag(r.readVarint() ?: return out)
                lon += zigZag(r.readVarint() ?: return out)
                out += LonLat(lon = lon / 1e7, lat = lat / 1e7)
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun zigZag(v: Long): Long = (v ushr 1) xor -(v and 1)

    private fun writeVarint(out: MutableList<Byte>, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) out += (b or 0x80).toByte() else { out += b.toByte(); break }
        }
    }

    private fun writeZigZag(out: MutableList<Byte>, value: Long) =
        writeVarint(out, (value shl 1) xor (value shr 63))
}
