package app.tenshu.fit.data

import app.tenshu.fit.model.GeoCoord
import app.tenshu.mapregion.app.LatLng
import app.tenshu.mapregion.app.RegionTerminus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real [RegionBundleResolver] over the shared [RegionTerminus] (`:map-region-app`): centres the
 * workout's GPS [route] and asks the terminus for the tightest *installed* region covering that
 * centre, drained into the app sandbox (`maps-region-delivery.md` §7). Bound over Fit's best-effort
 * Control region client — if Control is unreachable the terminus returns null and `WorkoutRouteMap`
 * keeps the bundled demo basemap. Mirrors Timeline's identical resolver.
 *
 * The (potentially large) whole-region drain runs on [Dispatchers.Default] here, so the caller
 * (`WorkoutDetailViewModel`) stays dispatcher-agnostic and test-synchronous.
 */
class TerminusRegionBundleResolver(
    private val terminus: RegionTerminus,
) : RegionBundleResolver {

    override suspend fun bundlePath(route: List<GeoCoord>): String? {
        val centre = route.centroid() ?: return null
        return withContext(Dispatchers.Default) {
            terminus.renderableRegionPathFor(centre)
        }
    }

    /**
     * The D10 streamed source for the [route]: asks the terminus for the covering region as a lazy
     * `streamRegionRange`-backed handle (no whole-region drain) and re-wraps it in the neutral
     * [RouteRegionStream] the VM holds. The catalog fetch is light, so no `Dispatchers.Default` hop
     * is needed here (the heavy per-tile fetches happen later, in `:ui-map`'s sparse-file builder).
     */
    override suspend fun streamHandle(route: List<GeoCoord>): RouteRegionStream? {
        val centre = route.centroid()
        val handle = terminus.renderableStream(centre) ?: return null
        return RouteRegionStream(
            regionId = handle.regionId,
            totalSize = handle.totalSize,
            readRange = handle.readRange,
        )
    }
}

/** The mean of the route's points — a good-enough camera centre for picking the covering region. */
private fun List<GeoCoord>.centroid(): LatLng? {
    if (isEmpty()) return null
    var lat = 0.0
    var lng = 0.0
    for (p in this) {
        lat += p.latitude
        lng += p.longitude
    }
    return LatLng(latitude = lat / size, longitude = lng / size)
}
