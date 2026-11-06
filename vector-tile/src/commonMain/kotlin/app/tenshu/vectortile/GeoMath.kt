package app.tenshu.vectortile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Metric geometry over lon/lat, sized for **map-matching** rather than navigation.
 *
 * Everything here uses a local equirectangular projection: at the scale a match cares about (tens of
 * metres, over paths of a few kilometres) the error against a proper geodesic is far below GPS noise,
 * and it turns every distance into cheap arithmetic — which matters, because a match compares a
 * few hundred trace points against a few thousand reference segments.
 */

internal const val METRES_PER_DEGREE_LAT = 111_320.0

/** Metres per degree of longitude at [latDeg] — shrinks toward the poles. */
internal fun metresPerDegreeLon(latDeg: Double): Double =
    METRES_PER_DEGREE_LAT * cos(latDeg * PI / 180.0)

/** An axis-aligned lon/lat box. Used to reject far-away reference lines before any real work. */
data class GeoBounds(
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
) {
    /** Grow the box by [metres] on every side, so a near-miss still survives the prefilter. */
    fun expandedBy(metres: Double): GeoBounds {
        val dLat = metres / METRES_PER_DEGREE_LAT
        val midLat = (minLat + maxLat) / 2
        val perLon = metresPerDegreeLon(midLat)
        // Near the poles a degree of longitude collapses; fall back to the whole span rather than
        // dividing by ~0 and producing an infinite box.
        val dLon = if (perLon < 1.0) 180.0 else metres / perLon
        return GeoBounds(minLon - dLon, minLat - dLat, maxLon + dLon, maxLat + dLat)
    }

    fun intersects(other: GeoBounds): Boolean =
        minLon <= other.maxLon && maxLon >= other.minLon &&
            minLat <= other.maxLat && maxLat >= other.minLat

    companion object {
        /** Bounding box of a set of points, or `null` when empty. */
        fun of(points: List<LonLat>): GeoBounds? {
            if (points.isEmpty()) return null
            var minLon = points[0].lon; var maxLon = minLon
            var minLat = points[0].lat; var maxLat = minLat
            for (p in points) {
                minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
                minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            }
            return GeoBounds(minLon, minLat, maxLon, maxLat)
        }
    }
}

/** Great-circle-ish distance between two coordinates, in metres (equirectangular approximation). */
internal fun distanceMeters(a: LonLat, b: LonLat): Double {
    val perLon = metresPerDegreeLon((a.lat + b.lat) / 2)
    val dx = (b.lon - a.lon) * perLon
    val dy = (b.lat - a.lat) * METRES_PER_DEGREE_LAT
    return sqrt(dx * dx + dy * dy)
}

/**
 * Shortest distance from [p] to the **segment** `a`–`b` (not the infinite line), in metres.
 *
 * Clamping to the segment matters: a trace running past the *end* of a short rail stub must not be
 * scored as if the stub extended forever.
 */
internal fun pointToSegmentMeters(p: LonLat, a: LonLat, b: LonLat): Double {
    val perLon = metresPerDegreeLon(p.lat)
    val ax = 0.0
    val ay = 0.0
    val bx = (b.lon - a.lon) * perLon
    val by = (b.lat - a.lat) * METRES_PER_DEGREE_LAT
    val px = (p.lon - a.lon) * perLon
    val py = (p.lat - a.lat) * METRES_PER_DEGREE_LAT

    val dx = bx - ax
    val dy = by - ay
    val lenSq = dx * dx + dy * dy
    if (lenSq == 0.0) return sqrt(px * px + py * py) // degenerate segment = a point

    // Projection parameter, clamped to the segment.
    val t = ((px - ax) * dx + (py - ay) * dy) / lenSq
    val clamped = t.coerceIn(0.0, 1.0)
    val cx = ax + clamped * dx
    val cy = ay + clamped * dy
    return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
}

/**
 * Shortest distance from [p] to any segment of [polyline], in metres. `null` for an empty polyline;
 * a one-point polyline degenerates to point-to-point.
 */
internal fun pointToPolylineMeters(p: LonLat, polyline: List<LonLat>): Double? {
    if (polyline.isEmpty()) return null
    if (polyline.size == 1) return distanceMeters(p, polyline[0])
    var best = Double.MAX_VALUE
    for (i in 1 until polyline.size) {
        val d = pointToSegmentMeters(p, polyline[i - 1], polyline[i])
        if (d < best) best = d
        if (best == 0.0) return 0.0
    }
    return best
}

/** Absolute difference, exposed for readability at call sites doing tolerance comparisons. */
internal fun approximatelyEqual(a: Double, b: Double, epsilon: Double): Boolean = abs(a - b) <= epsilon

/**
 * Where a point landed when projected onto a polyline: how far off it was ([distanceM]), how far
 * along the line the landing sits ([alongM], from the line's first vertex), and the landing [point].
 *
 * ⚠ [alongM] is the piece [pointToPolylineMeters] cannot give and snapping cannot do without — it is
 * what makes "walk the track between two matched fixes" expressible at all.
 */
internal data class PolylineProjection(
    val distanceM: Double,
    val alongM: Double,
    val point: LonLat,
)

/** Cumulative along-line distance at each vertex, in metres (index 0 = 0.0). */
internal fun cumulativeMeters(polyline: List<LonLat>): DoubleArray {
    val out = DoubleArray(polyline.size)
    for (i in 1 until polyline.size) out[i] = out[i - 1] + distanceMeters(polyline[i - 1], polyline[i])
    return out
}

/**
 * Project [p] onto [polyline] — the nearest position **on** the line, not merely the distance to it.
 *
 * [cumulative] is [cumulativeMeters] of the same polyline, hoisted by the caller because a snapping
 * pass projects hundreds of points onto the same handful of lines.
 */
internal fun projectOntoPolyline(
    p: LonLat,
    polyline: List<LonLat>,
    cumulative: DoubleArray,
): PolylineProjection? {
    if (polyline.size < 2) return null
    var best: PolylineProjection? = null
    for (i in 1 until polyline.size) {
        val a = polyline[i - 1]
        val b = polyline[i]
        val t = segmentParameter(p, a, b)
        val point = LonLat(lon = a.lon + t * (b.lon - a.lon), lat = a.lat + t * (b.lat - a.lat))
        val d = distanceMeters(p, point)
        if (best == null || d < best.distanceM) {
            best = PolylineProjection(
                distanceM = d,
                alongM = cumulative[i - 1] + t * (cumulative[i] - cumulative[i - 1]),
                point = point,
            )
        }
    }
    return best
}

/**
 * Where along segment `a`–`b` the foot of the perpendicular from [p] falls, as a fraction clamped to
 * `[0, 1]` — the same clamping [pointToSegmentMeters] does, and for the same reason: a trace running
 * past the end of a short stub must land on the stub's end, not on its imaginary extension.
 */
private fun segmentParameter(p: LonLat, a: LonLat, b: LonLat): Double {
    val perLon = metresPerDegreeLon(p.lat)
    val bx = (b.lon - a.lon) * perLon
    val by = (b.lat - a.lat) * METRES_PER_DEGREE_LAT
    val px = (p.lon - a.lon) * perLon
    val py = (p.lat - a.lat) * METRES_PER_DEGREE_LAT
    val lenSq = bx * bx + by * by
    if (lenSq == 0.0) return 0.0 // degenerate segment = a point
    return ((px * bx + py * by) / lenSq).coerceIn(0.0, 1.0)
}
