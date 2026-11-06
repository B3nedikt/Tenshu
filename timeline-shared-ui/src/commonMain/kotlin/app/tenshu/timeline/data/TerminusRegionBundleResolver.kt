package app.tenshu.timeline.data

import app.tenshu.mapregion.app.LatLng
import app.tenshu.mapregion.app.RegionTerminus
import app.tenshu.timeline.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real [RegionBundleResolver] over the shared [RegionTerminus] (`:map-region-app`): centres the
 * trip's [route] and asks the terminus for the tightest *installed* region covering that centre,
 * drained into the app sandbox (`maps-region-delivery.md` §7). Bound over Timeline's best-effort
 * Control region client — if Control is unreachable the terminus returns null and `TripRouteMap`
 * keeps the bundled demo basemap.
 *
 * The (potentially large) whole-region drain runs on [Dispatchers.Default] here, so the caller
 * (`TripDetailViewModel`) stays dispatcher-agnostic and test-synchronous.
 */
class TerminusRegionBundleResolver(
    private val terminus: RegionTerminus,
) : RegionBundleResolver {

    override suspend fun bundlePath(route: List<GeoPoint>): String? {
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
    override suspend fun streamHandle(route: List<GeoPoint>): RouteRegionStream? {
        val centre = route.centroid()
        val handle = terminus.renderableStream(centre) ?: return null
        return RouteRegionStream(
            regionId = handle.regionId,
            totalSize = handle.totalSize,
            readRange = handle.readRange,
        )
    }

    /**
     * Every region the trip's route crosses (track 3d) — the whole set, not just the one under the
     * centroid: a trip that crosses a border needs both halves to be usable offline, and the centroid
     * region alone would leave the far end of the route still needing the network.
     */
    override suspend fun offlineOffer(route: List<GeoPoint>): TripOfflineOffer? {
        val points = route.map { LatLng(latitude = it.latitude, longitude = it.longitude) }
        val offers = terminus.offersForRoute(points)
        if (offers.isEmpty()) return null
        return TripOfflineOffer(
            regions = offers.map {
                TripOfflineRegion(
                    regionId = it.regionId,
                    name = it.name,
                    byteSize = it.byteSize,
                    installed = it.installed,
                )
            },
        )
    }

    override suspend fun download(regionId: String): Boolean = terminus.download(regionId)

    override fun downloadProgress(regionId: String) = terminus.downloadProgress(regionId)
}

/** The mean of the route's points — a good-enough camera centre for picking the covering region. */
private fun List<GeoPoint>.centroid(): LatLng? {
    if (isEmpty()) return null
    var lat = 0.0
    var lng = 0.0
    for (p in this) {
        lat += p.latitude
        lng += p.longitude
    }
    return LatLng(latitude = lat / size, longitude = lng / size)
}
