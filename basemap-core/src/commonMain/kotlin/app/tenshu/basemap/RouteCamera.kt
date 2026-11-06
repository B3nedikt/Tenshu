package app.tenshu.basemap

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.tan

/**
 * The camera target + zoom that frames a route's geographic bounding box: [centerLatitude] /
 * [centerLongitude] are the midpoint of the min/max lat/lng, [zoom] is chosen so that **both** the
 * horizontal and the vertical extent fit the viewport (with modest padding). Pure so the framing math
 * is unit-testable without a map surface (mirrors [normalizeRoute] / [resolveBasemapSource]).
 */
data class RouteCameraFrame(
    val centerLatitude: Double,
    val centerLongitude: Double,
    val zoom: Double,
)

/**
 * The latitude at which the Web Mercator projection is truncated to a square world — the standard
 * slippy-map cutoff, and what MapLibre itself clamps to. Beyond it [mercatorWorldFraction] diverges.
 */
private const val MAX_MERCATOR_LATITUDE = 85.051129

/**
 * [latitude] as a fraction of the whole Mercator world height, in `[-0.5, 0.5]` (north positive).
 *
 * This is the correction the naive "a degree is a degree" framing was missing. In Web Mercator a
 * degree of latitude covers progressively *more* screen the further from the equator you are — at
 * Munich (~48°N) about 1.5× what a degree of longitude covers. Framing a north-south route off the
 * raw degree span therefore under-estimates its on-screen height by half again and cuts the ends off.
 */
private fun mercatorWorldFraction(latitude: Double): Double {
    val radians = latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE) * PI / 180.0
    return ln(tan(PI / 4.0 + radians / 2.0)) / (2.0 * PI)
}

/**
 * MapLibre's world is [MAPLIBRE_TILE_SIZE_DP] × 2^zoom density-independent pixels across. This is
 * the constant that ties a zoom level to actual screen space, and getting it wrong is invisible in
 * a unit test — it only shows up as a route that overflows the surface on a real display.
 */
internal const val MAPLIBRE_TILE_SIZE_DP = 512.0

/**
 * The tightest a hero may frame — a neighbourhood, not a doorstep.
 *
 * ⚠⚠ Public because **both renderers need the same number**, and the MapKit path learned that the
 * expensive way twice: once on the 256-vs-512 tile constant (`ios-apple-maps-option.md` §8.2 F1),
 * and once here — a stationary workout (7 fixes within 4 cm) fitted to its own bounding box and
 * rendered a **10 m** window of Apple's basemap, which has no roads or labels at that scale and so
 * drew a bare land fill. MapLibre was unaffected because [routeCameraFrame] clamps to this.
 */
const val BASEMAP_MAX_ZOOM = 15.0

/**
 * Pure: frame a lat/lng [route] to a [RouteCameraFrame].
 *
 * Centre = midpoint of the bounding box. [zoom] is the **tighter** of the two axis fits, so the whole
 * route is visible rather than merely its dominant axis. Each axis compares how much of the world the
 * route covers against how much of the world the viewport shows at a given zoom:
 *
 * - the route spans `lngSpan / 360` of the world horizontally, and a **Mercator** fraction of it
 *   vertically ([mercatorWorldFraction]) — *not* a raw degree fraction, because a degree of latitude
 *   covers ~1.5× more screen at Munich's latitude than a degree of longitude does;
 * - the viewport shows `viewportWidthDp / 512` of the world horizontally and
 *   `viewportHeightDp / 512` vertically, per [MAPLIBRE_TILE_SIZE_DP].
 *
 * That second half is why real dimensions are taken rather than a mere aspect ratio. An earlier
 * version compared the axes against each other correctly but kept the original absolute calibration,
 * which implicitly assumed a 512dp-wide viewport. A phone is nearer 411dp, so every route was framed
 * about a third of a zoom level too tight and ran to the edges — and on a longitude-dominant trip,
 * where the horizontal axis binds, the aspect-and-Mercator work changed nothing at all. Both halves
 * are needed; only the pair actually fits a route on a device.
 *
 * Clamped to [[minZoom], [maxZoom]]. A degenerate span (a single point, or all points sharing a
 * lat/lng) has nothing to fit, so it uses [maxZoom]. An empty route yields a null-island frame at
 * [maxZoom] (renderers never call this for `route.size < 2`).
 *
 * @param viewportWidthDp the surface's measured width in dp. Defaults to [MAPLIBRE_TILE_SIZE_DP],
 *   the neutral "one world tile" viewport, for callers with nothing measured yet.
 * @param viewportHeightDp the surface's measured height in dp.
 * @param paddingFraction fraction of the span added as breathing room on each side (0.15 = 15%), so
 *   the route never runs flush to the edge of the surface.
 */
fun routeCameraFrame(
    route: List<BasemapCoord>,
    viewportWidthDp: Double = MAPLIBRE_TILE_SIZE_DP,
    viewportHeightDp: Double = MAPLIBRE_TILE_SIZE_DP,
    paddingFraction: Double = 0.15,
    minZoom: Double = 3.0,
    maxZoom: Double = BASEMAP_MAX_ZOOM,
): RouteCameraFrame {
    if (route.isEmpty()) return RouteCameraFrame(0.0, 0.0, maxZoom)
    val minLat = route.minOf { it.latitude }
    val maxLat = route.maxOf { it.latitude }
    val minLng = route.minOf { it.longitude }
    val maxLng = route.maxOf { it.longitude }
    val centerLat = (minLat + maxLat) / 2.0
    val centerLng = (minLng + maxLng) / 2.0

    val padding = 1.0 + paddingFraction * 2.0
    // Both extents as a fraction of the whole world, so the two axes are directly comparable.
    val horizontalWorldFraction = ((maxLng - minLng) / 360.0) * padding
    val verticalWorldFraction =
        (mercatorWorldFraction(maxLat) - mercatorWorldFraction(minLat)) * padding

    // How much of the world each axis of the viewport shows at zoom 0.
    val width = if (viewportWidthDp > 0.0) viewportWidthDp else MAPLIBRE_TILE_SIZE_DP
    val height = if (viewportHeightDp > 0.0) viewportHeightDp else MAPLIBRE_TILE_SIZE_DP
    val horizontalViewportFraction = width / MAPLIBRE_TILE_SIZE_DP
    val verticalViewportFraction = height / MAPLIBRE_TILE_SIZE_DP

    // Fit each axis independently, then take whichever demands the most zooming out.
    val horizontalZoom =
        if (horizontalWorldFraction <= 0.0) maxZoom
        else log2(horizontalViewportFraction / horizontalWorldFraction)
    val verticalZoom =
        if (verticalWorldFraction <= 0.0) maxZoom
        else log2(verticalViewportFraction / verticalWorldFraction)

    val zoom = min(horizontalZoom, verticalZoom).coerceIn(minZoom, maxZoom)
    return RouteCameraFrame(centerLat, centerLng, zoom)
}

/**
 * Pure: fit a lat/lng route into the unit square `[0,1]×[0,1]`, with **x = longitude** (west → 0)
 * and **y = latitude inverted** (north → 0, so the drawn route reads north-up). A degenerate span
 * (all points share a lat or lng) collapses to the centre line rather than dividing by zero.
 *
 * This is the *schematic* fit, not a geographic projection: it is what `:ui-map`'s `RoutePolyline`
 * and `timeline-ios`'s `RouteSketch` draw when there are no tiles to anchor to. The drawing stays
 * with each renderer; the fitting is shared.
 */
fun normalizeRoute(route: List<BasemapCoord>): List<Pair<Double, Double>> {
    if (route.isEmpty()) return emptyList()
    val minLat = route.minOf { it.latitude }
    val maxLat = route.maxOf { it.latitude }
    val minLng = route.minOf { it.longitude }
    val maxLng = route.maxOf { it.longitude }
    val latSpan = (maxLat - minLat).takeIf { it > 0.0 }
    val lngSpan = (maxLng - minLng).takeIf { it > 0.0 }
    return route.map { c ->
        val x = if (lngSpan != null) (c.longitude - minLng) / lngSpan else 0.5
        val y = if (latSpan != null) (maxLat - c.latitude) / latSpan else 0.5
        x to y
    }
}
