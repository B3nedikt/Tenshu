package app.tenshu.fit.data

import app.tenshu.fit.model.GeoCoord

/**
 * Resolves a workout's GPS [route] to an absolute offline-basemap `.pmtiles` path staged in the app
 * sandbox (the render-hop terminus, `docs/proposals/maps-region-delivery.md` §7) — or `null` when
 * no region is installed / reachable, so `WorkoutRouteMap` cleanly keeps the bundled demo basemap.
 *
 * A thin seam in `:fit-shared-logic` (over the workout's own [GeoCoord], no `:map-region`
 * dependency) so the VM stays decoupled and fakeable; the real implementation lives in
 * `:fit-shared-ui` over the shared `RegionTerminus` and is bound **best-effort** — absent in the
 * standalone graph (region service unreachable), the VM's `getOrNull` leaves it null and the map
 * degrades to the demo. Mirrors Timeline's identical seam.
 */
fun interface RegionBundleResolver {
    suspend fun bundlePath(route: List<GeoCoord>): String?

    /**
     * The D10 streamed default (`maps-region-delivery.md`): a lazy [RouteRegionStream] for the
     * [route]'s region, the render fetching only its covering tiles rather than the whole bundle.
     * Preferred over [bundlePath]. Defaults to `null` (the standalone/fake graph has no stream) so
     * the VM falls back to [bundlePath] then the bundled demo; the real impl overrides it.
     */
    suspend fun streamHandle(route: List<GeoCoord>): RouteRegionStream? = null
}
