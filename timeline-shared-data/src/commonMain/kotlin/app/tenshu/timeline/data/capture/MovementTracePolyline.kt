package app.tenshu.timeline.data.capture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The **movement-trace polyline codec** (`docs/proposals/timeline-movement-capture.md` §4): turns an
 * ordered path of [CapturedFix]es into the bounded `MovementTrace.polyline` blob and back. Two stages,
 * both pure `commonMain`:
 *
 *  - [simplify] — Douglas–Peucker (drop points within `simplifyEpsilonM` of the local line) then an
 *    **arc-length** cap to `maxPolylinePoints`, so a straight walk collapses to its shape and no trace
 *    exceeds the resident-store size tier (§4 keeps `MovementTrace` a small, safe eager-synced row).
 *    Deterministic → re-encoding an unchanged path is stable.
 *  - [encode]/[decode] — delta + zig-zag-varint of (latE7, lngE7, timestampMs, accuracyM). The first
 *    point is absolute; each subsequent one is a delta from its predecessor, so a slow walk (small
 *    deltas) packs tightly. Round-trips the given points bit-for-bit, **accuracy included**.
 *
 * ⚠ **Accuracy is route data, not just a capture-time signal** (§12.1). The original codec dropped it,
 * which meant that after a round-trip a 5 m GPS fix and a 2 km cell fix were indistinguishable — so no
 * post-hoc re-filtering and no confidence re-derivation were possible, and §13's kinematic classifier
 * (which must discount low-accuracy legs) could never be applied to already-stored traces. Accuracy
 * varies slowly, so as a fourth delta stream it costs ≈1 byte/point.
 *
 * [encode] does **not** simplify — the caller simplifies once (so distance/mode stay computed over the
 * raw path, §3) and encodes the reduced set.
 */
object MovementTracePolyline {

    /** Simplify a path for storage: Douglas–Peucker at the config tolerance, then cap the point count. */
    fun simplify(fixes: List<CapturedFix>, config: MovementDetectionConfig): List<CapturedFix> {
        if (fixes.size <= 2) return fixes
        val dp = douglasPeucker(fixes, config.simplifyEpsilonM)
        return capPoints(dp, config.maxPolylinePoints)
    }

    /** Serialise an already-simplified path to the trace blob (accuracy included). */
    fun encode(fixes: List<CapturedFix>): ByteArray {
        val out = ArrayList<Byte>()
        writeVarint(out, fixes.size.toLong())
        var prevLat = 0L; var prevLng = 0L; var prevTs = 0L; var prevAcc = 0L
        for (f in fixes) {
            writeZigZag(out, f.latE7 - prevLat)
            writeZigZag(out, f.lngE7 - prevLng)
            writeZigZag(out, f.timestampMs - prevTs)
            writeZigZag(out, f.accuracyM - prevAcc)
            prevLat = f.latE7; prevLng = f.lngE7; prevTs = f.timestampMs; prevAcc = f.accuracyM.toLong()
        }
        return out.toByteArray()
    }

    /** Deserialise a trace blob back to its path, accuracy included — the exact points [encode] took. */
    fun decode(bytes: ByteArray): List<CapturedFix> {
        if (bytes.isEmpty()) return emptyList()
        val reader = VarintReader(bytes)
        val count = reader.readVarint().toInt()
        val fixes = ArrayList<CapturedFix>(count)
        var lat = 0L; var lng = 0L; var ts = 0L; var acc = 0L
        repeat(count) {
            lat += reader.readZigZag()
            lng += reader.readZigZag()
            ts += reader.readZigZag()
            acc += reader.readZigZag()
            fixes += CapturedFix(latE7 = lat, lngE7 = lng, timestampMs = ts, accuracyM = acc.toInt())
        }
        return fixes
    }

    // --- Douglas–Peucker ---------------------------------------------------------------------

    private fun douglasPeucker(points: List<CapturedFix>, epsilonM: Double): List<CapturedFix> {
        if (points.size < 3) return points
        var index = -1
        var maxDist = 0.0
        for (i in 1 until points.size - 1) {
            val d = perpDistanceMeters(points[i], points.first(), points.last())
            if (d > maxDist) { maxDist = d; index = i }
        }
        return if (maxDist > epsilonM && index > 0) {
            val left = douglasPeucker(points.subList(0, index + 1), epsilonM)
            val right = douglasPeucker(points.subList(index, points.size), epsilonM)
            left.dropLast(1) + right // index point is shared by both halves
        } else {
            listOf(points.first(), points.last())
        }
    }

    /** Perpendicular distance of [p] from segment [a]–[b], via a local equirectangular projection. */
    private fun perpDistanceMeters(p: CapturedFix, a: CapturedFix, b: CapturedFix): Double {
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(a.latE7 / 1e7 * PI / 180.0)
        fun x(f: CapturedFix) = (f.lngE7 - a.lngE7) / 1e7 * mPerDegLng
        fun y(f: CapturedFix) = (f.latE7 - a.latE7) / 1e7 * mPerDegLat
        val ax = 0.0; val ay = 0.0
        val bx = x(b); val by = y(b)
        val px = x(p); val py = y(p)
        val dx = bx - ax; val dy = by - ay
        val segLen = sqrt(dx * dx + dy * dy)
        if (segLen == 0.0) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        // |cross product| / |segment| = distance from the infinite line through a,b.
        return abs(dx * (ay - py) - (ax - px) * dy) / segLen
    }

    /**
     * Keep at most [max] points, endpoints included, evenly spaced **by distance travelled**.
     *
     * ⚠ The original cap strided by *index*, which threw away the shape guarantee Douglas–Peucker had
     * just computed (§12.1): a 3-hour drive capped to 500 points kept every 20th point regardless of
     * whether that stretch was a motorway straight — where DP had already collapsed the geometry to a
     * handful of points, each one load-bearing — or a dense city grid. Striding by arc length instead
     * spends the budget where the path actually goes, so dropped points are the ones a viewer misses
     * least.
     */
    private fun capPoints(points: List<CapturedFix>, max: Int): List<CapturedFix> {
        if (points.size <= max || max < 2) return points

        // Cumulative arc length at each point (index 0 = 0.0).
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + capturedDistanceMeters(points[i - 1], points[i])
        }
        val total = cumulative.last()
        // Degenerate (all points coincident) → nothing to space by; fall back to index stride.
        if (total <= 0.0) {
            val step = (points.size - 1).toDouble() / (max - 1)
            return (0 until max).map { (it * step).roundToInt() }.distinct().map { points[it] }
        }

        val kept = LinkedHashSet<Int>()
        kept += 0
        var cursor = 1
        for (n in 1 until max - 1) {
            val target = total * n / (max - 1)
            // Advance to the first point at or past this arc-length target.
            while (cursor < points.size - 1 && cumulative[cursor] < target) cursor++
            kept += cursor
        }
        kept += points.size - 1
        return kept.sorted().map { points[it] }
    }

    // --- varint / zig-zag --------------------------------------------------------------------

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

    private class VarintReader(private val bytes: ByteArray) {
        private var pos = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }
        fun readZigZag(): Long {
            val z = readVarint()
            return (z ushr 1) xor -(z and 1)
        }
    }
}
