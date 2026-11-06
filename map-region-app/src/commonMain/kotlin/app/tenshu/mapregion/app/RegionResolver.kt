package app.tenshu.mapregion.app

/**
 * A WGS84 lat/lng point — the neutral coordinate the region resolver works in, so every `:ui-map`
 * consumer (Maps place snippets, Timeline trip routes, Fit workout routes) can feed a point without
 * a shared app-level model. Each app converts its own coordinate type (`maps` `GeoPoint`, `timeline`
 * `GeoPoint`, …) at the call site.
 */
data class LatLng(val latitude: Double, val longitude: Double)

/**
 * A coarse geographic bounding box (WGS84 degrees) — the render-time shape of a catalog region's
 * coverage (`docs/proposals/maps-region-delivery.md` D3: "coarse coverage polygon, for framing,
 * not rendering"). We only need containment + relative size to pick a covering region, so a box is
 * enough; the catalog's real coverage may be a polygon, but its bbox is the conservative superset
 * and the honest "which region am I looking at" granularity.
 */
data class GeoBounds(
    val minLat: Double,
    val minLng: Double,
    val maxLat: Double,
    val maxLng: Double,
) {
    fun contains(point: LatLng): Boolean =
        point.latitude in minLat..maxLat && point.longitude in minLng..maxLng

    /** Relative extent — used only to prefer a tighter (sub-country) box over a looser one. */
    val area: Double get() = (maxLat - minLat) * (maxLng - minLng)

    companion object {
        /**
         * Parses a GeoJSON-style bbox string `"minLng,minLat,maxLng,maxLat"` — the coarse form
         * a catalog region's coverage carries. Returns null on any malformed/degenerate input so a
         * bad catalog row simply drops out of resolution (never blanking the surface). Whitespace is
         * tolerated; a full polygon string (leading `{`/`[`) is out of scope here and returns null.
         */
        fun parseBbox(coverage: String): GeoBounds? {
            val parts = coverage.trim().split(",")
            if (parts.size != 4) return null
            val nums = parts.map { it.trim().toDoubleOrNull() ?: return null }
            val (minLng, minLat, maxLng, maxLat) = nums
            if (minLat > maxLat || minLng > maxLng) return null
            return GeoBounds(minLat = minLat, minLng = minLng, maxLat = maxLat, maxLng = maxLng)
        }
    }
}

/**
 * A candidate region for resolution: its [regionId], coverage [bounds], and the on-disk
 * [bundlePath] of its installed bytes — **null when the region is in the catalog but not yet
 * downloaded**. This is the join a terminus produces from the proxy's `catalog()` × `installed()`
 * (a catalog `RegionEntry` contributes `bounds`; a matching `InstalledRegion` contributes
 * `bundlePath`).
 */
data class ResolvableRegion(
    val regionId: String,
    val bounds: GeoBounds,
    val bundlePath: String? = null,
)

/**
 * The viewport→region resolver (`docs/proposals/maps-region-delivery.md` §7 phase-2: "a shared
 * viewport→region resolver belongs in the terminus from the start"). Pure logic — given a point
 * (a place's coordinate, or a Timeline trip's camera centre) and the known regions, it answers two
 * questions the render seam needs:
 *
 *  - **[resolveBundlePath]** — which installed region's `.pmtiles` should back this point? Feeds
 *    `MapSnippet(regionBundlePath = …)` / `OfflineBasemap` / `RouteBasemap`. Null ⇒ fall back to the
 *    bundled demo then the schematic (the surface never blanks).
 *  - **[coveringRegion]** — which region *covers* this point at all (installed or not)? Feeds a
 *    "download {region} for a real map" affordance when [ResolvableRegion.bundlePath] is null.
 *
 * When several boxes contain the point, the **smallest-area** one wins — a Bundesland/city split
 * is a better answer than the country that also contains it (D7 granularity).
 */
object RegionResolver {

    /** The installed region backing [point], as an absolute bundle path, or null if none. */
    fun resolveBundlePath(point: LatLng?, regions: List<ResolvableRegion>): String? =
        covering(point, regions).firstOrNull { it.bundlePath != null }?.bundlePath

    /** The tightest region covering [point] (installed or not), or null if outside every box. */
    fun coveringRegion(point: LatLng?, regions: List<ResolvableRegion>): ResolvableRegion? =
        covering(point, regions).firstOrNull()

    /** Regions containing [point], tightest box first; empty when [point] is null. */
    private fun covering(point: LatLng?, regions: List<ResolvableRegion>): List<ResolvableRegion> {
        if (point == null) return emptyList()
        return regions.filter { it.bounds.contains(point) }.sortedBy { it.bounds.area }
    }
}
