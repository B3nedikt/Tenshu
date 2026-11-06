package app.tenshu.vectortile

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/** One layer of a decoded tile — `transit`, `roads`, `pois`, … */
data class MvtLayer(
    val name: String,
    val version: Int,
    /** Tile-local coordinate space, normally [MvtDecoder.DEFAULT_EXTENT]. */
    val extent: Int,
    val features: List<MvtFeature>,
)

/**
 * One feature. [geometry] is in **tile-local integer coordinates** (0..[MvtLayer.extent], y down);
 * use [MvtLayer.toLonLat] to project. Kept unprojected because a consumer matching against many
 * features wants to project once, or not at all (tile-space comparisons are cheaper and exact).
 */
data class MvtFeature(
    val id: Long,
    val type: MvtGeometryType,
    val tags: Map<String, MvtValue>,
    /** Rings/lines: one list per `MoveTo`-started part. A point feature has one 1-element ring. */
    val geometry: List<List<MvtPoint>>,
) {
    /** Convenience for the common case of reading a string tag (`railway`, `service`, `name`). */
    fun tag(key: String): String? = (tags[key] as? MvtValue.Str)?.value

    /** True when [key] equals [value] as a string tag. */
    fun tagIs(key: String, value: String): Boolean = tag(key) == value
}

/** Tile-local integer coordinate. */
data class MvtPoint(val x: Int, val y: Int)

enum class MvtGeometryType {
    UNKNOWN, POINT, LINESTRING, POLYGON;

    companion object {
        fun of(raw: Int): MvtGeometryType = when (raw) {
            1 -> POINT
            2 -> LINESTRING
            3 -> POLYGON
            else -> UNKNOWN
        }
    }
}

/** A tag value. MVT's value union, narrowed to what a consumer actually branches on. */
sealed interface MvtValue {
    data class Str(val value: String) : MvtValue
    data class Num(val value: Double) : MvtValue
    data class Int64(val value: Long) : MvtValue
    data class Bool(val value: Boolean) : MvtValue
    data object Null : MvtValue
}

/** A geographic coordinate in degrees. */
data class LonLat(val lon: Double, val lat: Double)

/**
 * Project a tile-local point to lon/lat, given the tile's own address.
 *
 * Web-Mercator inverse: the point's fractional position within the tile is added to the tile index,
 * normalised by `2^z`, then unprojected. ⚠ Tile y runs **downward** (north at y=0), which is why the
 * latitude term is inverted before the `sinh` — getting this backwards mirrors every result across
 * the equator, and a rail match would silently never hit.
 */
fun MvtLayer.toLonLat(point: MvtPoint, z: Int, tileX: Long, tileY: Long): LonLat {
    val n = 1L shl z
    val fx = (tileX + point.x.toDouble() / extent) / n
    val fy = (tileY + point.y.toDouble() / extent) / n
    val lon = fx * 360.0 - 180.0
    val lat = atan(sinh(PI * (1 - 2 * fy))) * 180.0 / PI
    return LonLat(lon = lon, lat = lat)
}
