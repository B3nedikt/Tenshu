package org.tenshu.sdk.ios

import app.tenshu.mapregion.app.LatLng
import app.tenshu.mapregion.app.RegionTerminus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tenshu.core.common.TenshuClient
import app.tenshu.fit.data.RegionBundleResolver as FitRegionBundleResolver
import app.tenshu.fit.data.RouteRegionStream as FitRouteRegionStream
import app.tenshu.fit.model.GeoCoord

/** Fit's resolver (workout GPS routes) over [client]. */
internal fun fitRegionResolver(client: TenshuClient): FitRegionBundleResolver =
    IosFitRegionBundleResolver(IosRegionSupport.terminus(client))

/**
 * Fit's `RegionBundleResolver` over the shared [RegionTerminus] — the iOS twin of
 * `app.tenshu.fit.data.TerminusRegionBundleResolver` (`:fit-shared-ui`). Fit's seam is the smaller
 * one: no offline offer, so only the two render entries are implemented.
 */
internal class IosFitRegionBundleResolver(
    private val terminus: RegionTerminus,
) : FitRegionBundleResolver {

    override suspend fun bundlePath(route: List<GeoCoord>): String? {
        val centre = route.centroid() ?: return null
        return withContext(Dispatchers.Default) { terminus.renderableRegionPathFor(centre) }
    }

    override suspend fun streamHandle(route: List<GeoCoord>): FitRouteRegionStream? {
        val handle = terminus.renderableStream(route.centroid()) ?: return null
        return FitRouteRegionStream(
            regionId = handle.regionId,
            totalSize = handle.totalSize,
            readRange = handle.readRange,
        )
    }
}

/** The mean of the route's points — a good-enough camera centre for picking the covering region. */
private fun List<GeoCoord>.centroid(): LatLng? =
    IosRegionSupport.meanOf(size, { this[it].latitude }, { this[it].longitude })
