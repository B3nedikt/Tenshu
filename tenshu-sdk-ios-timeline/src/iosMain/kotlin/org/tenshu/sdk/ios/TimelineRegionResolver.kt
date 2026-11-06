package org.tenshu.sdk.ios

import app.tenshu.mapregion.app.LatLng
import app.tenshu.mapregion.app.RegionTerminus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.tenshu.core.common.TenshuClient
import app.tenshu.timeline.data.RegionBundleResolver as TimelineRegionBundleResolver
import app.tenshu.timeline.data.RouteRegionStream as TimelineRouteRegionStream
import app.tenshu.timeline.data.TripOfflineOffer
import app.tenshu.timeline.data.TripOfflineRegion
import app.tenshu.timeline.model.GeoPoint

/** Timeline's resolver (trip routes, movement traces, place heroes) over [client]. */
internal fun timelineRegionResolver(client: TenshuClient): TimelineRegionBundleResolver =
    IosTimelineRegionBundleResolver(IosRegionSupport.terminus(client))

/**
 * Timeline's `RegionBundleResolver` over the shared [RegionTerminus] — the iOS twin of
 * `app.tenshu.timeline.data.TerminusRegionBundleResolver` (`:timeline-shared-ui`), behaviour for
 * behaviour: centre the route, prefer the streamed handle, offer every region the route crosses.
 *
 * The (potentially large) whole-region drain hops to [Dispatchers.Default] exactly as the Compose
 * twin does, so the calling view model stays dispatcher-agnostic.
 */
internal class IosTimelineRegionBundleResolver(
    private val terminus: RegionTerminus,
) : TimelineRegionBundleResolver {

    override suspend fun bundlePath(route: List<GeoPoint>): String? {
        val centre = route.centroid() ?: return null
        return withContext(Dispatchers.Default) { terminus.renderableRegionPathFor(centre) }
    }

    override suspend fun streamHandle(route: List<GeoPoint>): TimelineRouteRegionStream? {
        val handle = terminus.renderableStream(route.centroid()) ?: return null
        return TimelineRouteRegionStream(
            regionId = handle.regionId,
            totalSize = handle.totalSize,
            readRange = handle.readRange,
        )
    }

    override suspend fun offlineOffer(route: List<GeoPoint>): TripOfflineOffer? {
        val offers = terminus.offersForRoute(
            route.map { LatLng(latitude = it.latitude, longitude = it.longitude) },
        )
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

    override fun downloadProgress(regionId: String): Flow<Long> = terminus.downloadProgress(regionId)
}

/** The mean of the route's points — a good-enough camera centre for picking the covering region. */
private fun List<GeoPoint>.centroid(): LatLng? =
    IosRegionSupport.meanOf(size, { this[it].latitude }, { this[it].longitude })
