package app.tenshu.timeline.data

import app.tenshu.timeline.model.GeoPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Resolves a trip's [route] to an absolute offline-basemap `.pmtiles` path staged in the app
 * sandbox (the render-hop terminus, `docs/proposals/maps-region-delivery.md` §7) — or `null` when
 * no region is installed / reachable, so `TripRouteMap` cleanly keeps the bundled demo basemap.
 *
 * Kept as a thin seam in `:timeline-shared-logic` (over the trip's own [GeoPoint], no `:map-region`
 * dependency) so the VM stays decoupled and fakeable; the real implementation lives in
 * `:timeline-shared-ui` over the shared `RegionTerminus` and is bound **best-effort** — absent in
 * the standalone in-process graph (region service unreachable), the VM's `getOrNull` leaves it
 * null and the basemap degrades to the demo. Zero regression to Timeline's standalone startup.
 */
fun interface RegionBundleResolver {
    suspend fun bundlePath(route: List<GeoPoint>): String?

    /**
     * The D10 streamed default (`maps-region-delivery.md`): a lazy [RouteRegionStream] for the
     * [route]'s region, the render fetching only its covering tiles rather than the whole bundle.
     * Preferred over [bundlePath]. Defaults to `null` (the standalone/fake graph has no stream) so
     * the VM falls back to [bundlePath] then the bundled demo; the real impl overrides it.
     */
    suspend fun streamHandle(route: List<GeoPoint>): RouteRegionStream? = null

    /**
     * The offline offer for this [route] (`maps-region-delivery.md` §7.1 track 3d): **every** region the
     * trip crosses, with sizes and residency — a set, because a journey can span more than one. Null/empty
     * when nothing covers the route (or the catalog is unreachable), so the row simply isn't shown.
     *
     * ⚠ The route already renders without this (it streams — D10). The row offers **availability without a
     * network**, not a working map.
     */
    suspend fun offlineOffer(route: List<GeoPoint>): TripOfflineOffer? = null

    /** Downloads one region's whole bundle into Control's store. Slow (hundreds of MB). */
    suspend fun download(regionId: String): Boolean = false

    /**
     * A **push stream** of [regionId]'s downloaded bytes so far, ticked per streamed chunk by the proxy
     * during a [download] (not a poll). Bytes, so the VM can total progress across a multi-region trip;
     * completes when the download finishes. Defaults to an empty flow (standalone/fake graph).
     */
    fun downloadProgress(regionId: String): Flow<Long> = emptyFlow()
}
