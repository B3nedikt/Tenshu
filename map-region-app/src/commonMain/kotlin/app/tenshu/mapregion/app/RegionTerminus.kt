package app.tenshu.mapregion.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.getOrNull
import org.tenshu.core.mapregion.catalog
import org.tenshu.core.mapregion.downloadProgress
import org.tenshu.core.mapregion.downloadRegion
import org.tenshu.core.mapregion.installed
import org.tenshu.core.mapregion.readRegionRange
import org.tenshu.core.mapregion.streamRegionRange
import org.tenshu.mapregion.client.mapRegionService

/**
 * The app-side render terminus for offline basemap regions, shared by every `:ui-map` consumer —
 * Maps' place snippets, Timeline's trip routes, (later) Fit's workout routes
 * (`docs/proposals/maps-region-delivery.md` D5/§7). Given a [TenshuClient] reaching the proxy's
 * `MapRegionService` and a platform [RegionBundleStager], it drains an installed region's bytes
 * over `readRegionRange` into the app sandbox and returns the staged `.pmtiles` path to hand
 * `OfflineBasemap`/`RouteBasemap` as `regionBundlePath`.
 *
 * Compose-free and maplibre-free by construction, so an app's *data* layer can depend on it without
 * pulling the basemap engine's native libraries. Behaviourally identical to the staging half of
 * Maps' original `ClientRegionRepository`, lifted here so Timeline/Fit reuse it verbatim.
 *
 * [stager] is the platform file store the drain fills (Android `filesDir` / desktop cache); when
 * absent (headless dev/test graph, or a platform with no staging seam) [renderableRegionPath]
 * returns `null` and the renderer keeps the bundled demo.
 */
class RegionTerminus(
    private val client: TenshuClient,
    private val stager: RegionBundleStager?,
) {

    /**
     * Drains the first installed region's bytes into the [stager]'s file store and returns the
     * staged `.pmtiles` path. No stager (headless dev) or nothing installed → `null` → the basemap
     * keeps the bundled demo. Point-agnostic; [renderableRegionPathFor] is the coverage-aware form.
     */
    suspend fun renderableRegionPath(): String? = renderableRegionPathFor(null)

    /**
     * Resolves the installed region that best covers [point] and drains its bytes into the
     * [stager]'s file store, returning the staged `.pmtiles` path. When [point] is null (or no
     * installed region covers it), falls back to the first installed region — so a single-region
     * user always gets a real basemap, while a multi-region user (a Timeline trip crossing borders)
     * gets the tightest covering one ([RegionResolver], D7 granularity). No stager or nothing
     * installed → `null` → the basemap keeps the bundled demo.
     */
    suspend fun renderableRegionPathFor(point: LatLng?): String? {
        val stager = stager ?: return null
        val installed = client.mapRegionService.installed().getOrNull()?.toList().orEmpty()
        if (installed.isEmpty()) return null

        val covering = point?.let { resolveCovering(it, installed.map { r -> r.regionId.get() }.toSet()) }
        val target = installed.firstOrNull { it.regionId.get() == covering } ?: installed.first()

        val regionId = target.regionId.get()
        return stageRegionBundle(stager, regionId, target.byteSize.get()) { offset, length ->
            client.mapRegionService.readRegionRange(regionId, offset, length).getOrNull()?.get()
        }
    }

    /**
     * The D10 streamed default: resolves the covering catalog region and returns a lazy
     * [RegionStreamHandle] whose [RegionStreamHandle.readRange] rides the proxy's `streamRegionRange`
     * op — the render fetches only the covering tiles on demand (no full download, no [stager]).
     * Unlike [renderableRegionPathFor], streaming does **not** require the region be installed: the
     * proxy range-fetches from the tile host on a cache miss. When [point] is given, the tightest
     * *catalog* region covering it is used; otherwise (or when none covers it) the first catalog
     * region. Returns `null` when the catalog is empty/unreachable, so the caller falls back to the
     * whole-region path then the bundled demo.
     */
    suspend fun renderableStream(point: LatLng? = null): RegionStreamHandle? {
        val catalog = client.mapRegionService.catalog().getOrNull() ?: return null
        val entries = catalog.regions.toList()
        if (entries.isEmpty()) return null

        val allIds = entries.map { it.regionId.get() }.toSet()
        val coveringId = point?.let { resolveCovering(it, allIds) }
        val entry = entries.firstOrNull { it.regionId.get() == coveringId } ?: entries.first()

        val regionId = entry.regionId.get()
        return RegionStreamHandle(
            regionId = regionId,
            totalSize = entry.byteSize.get(),
            readRange = { offset, length ->
                client.mapRegionService.streamRegionRange(regionId, offset, length).getOrNull()?.get()
            },
        )
    }

    /**
     * The **offline offer** for [point] (`maps-region-delivery.md` §7.1 track 3d): which catalog region
     * covers this place/route, how big it is, and whether it is already resident. Backs the in-app
     * contextual trigger ("keep this area offline") that D5-amended leaves to the apps, while the
     * canonical management surface stays in Control.
     *
     * ⚠ The map **already works without this** — an uninstalled region streams (D10). So the affordance
     * this feeds is not "make the map work", it is "stop needing the network for it": availability and
     * privacy (a resident region is served from disk, so no per-viewport request pattern reaches the CDN
     * at all — T-MR-03). Callers should say that honestly rather than implying the map is broken.
     *
     * Null when the catalog is unreachable/empty, or when no region covers [point] — there is nothing to
     * offer, and the caller shows nothing.
     */
    suspend fun offerFor(point: LatLng?): RegionOffer? {
        val catalog = client.mapRegionService.catalog().getOrNull() ?: return null
        val entries = catalog.regions.toList()
        if (entries.isEmpty()) return null

        val allIds = entries.map { it.regionId.get() }.toSet()
        // Only a region that genuinely covers the point may be offered: falling back to "the first
        // region" here would invite the user to download a bundle that does not contain their place.
        val coveringId = point?.let { resolveCovering(it, allIds) } ?: return null
        val entry = entries.firstOrNull { it.regionId.get() == coveringId } ?: return null

        val residentIds = client.mapRegionService.installed().getOrNull()
            ?.toList()
            ?.map { it.regionId.get() }
            ?.toSet()
            .orEmpty()

        return RegionOffer(
            regionId = coveringId,
            name = entry.name.get(),
            byteSize = entry.byteSize.get(),
            installed = coveringId in residentIds,
        )
    }

    /**
     * The offline offer for a **route** (`maps-region-delivery.md` §7.1 track 3d, Timeline): the set of
     * catalog regions covering *any* point of it, each with its size and residency.
     *
     * A route is not a point. A trip can cross a border — or simply sit near one — so "the region covering
     * this trip" is genuinely a **set**, and its cost is the **sum** of the ones not yet resident. Offering
     * a single region (say, the one under the centroid) would silently under-deliver on exactly the
     * journeys Timeline exists for: the far end of the route would still need the network.
     *
     * Each point resolves to its **tightest** covering region (D7), and the results are deduplicated — a
     * dense polyline inside one city yields one region, not one per vertex. Points that no region covers
     * contribute nothing (they simply cannot be kept offline). Empty when the catalog is unreachable, the
     * route is empty, or nothing covers any of it.
     */
    suspend fun offersForRoute(points: List<LatLng>): List<RegionOffer> {
        if (points.isEmpty()) return emptyList()
        val catalog = client.mapRegionService.catalog().getOrNull() ?: return emptyList()
        val entries = catalog.regions.toList()
        if (entries.isEmpty()) return emptyList()

        // Catalog + install state are fetched once, then every point is resolved locally — a route can
        // have hundreds of vertices and each must not cost a round trip.
        val residentIds = client.mapRegionService.installed().getOrNull()
            ?.toList()
            ?.map { it.regionId.get() }
            ?.toSet()
            .orEmpty()

        val resolvable = entries.mapNotNull { entry ->
            val bounds = GeoBounds.parseBbox(entry.coverage.get()) ?: return@mapNotNull null
            ResolvableRegion(regionId = entry.regionId.get(), bounds = bounds, bundlePath = entry.regionId.get())
        }
        val coveringIds = points
            .mapNotNull { RegionResolver.coveringRegion(it, resolvable)?.regionId }
            .toSet()

        return entries
            .filter { it.regionId.get() in coveringIds }
            .map { entry ->
                val regionId = entry.regionId.get()
                RegionOffer(
                    regionId = regionId,
                    name = entry.name.get(),
                    byteSize = entry.byteSize.get(),
                    installed = regionId in residentIds,
                )
            }
    }

    /**
     * Downloads a region's whole bundle into Control's canonical store ("keep this offline"). Returns
     * whether it landed; the proxy sha256-gates the bytes and swaps them in atomically. Slow — a region
     * is hundreds of MB — so callers must show progress and stay cancellable.
     */
    suspend fun download(regionId: String): Boolean =
        client.mapRegionService.downloadRegion(regionId).getOrNull() != null

    /**
     * A **push stream** of [regionId]'s downloaded bytes so far, while a [download] is in flight
     * (`maps-region-delivery.md` §7.1, download UX). The proxy pushes a tick per streamed chunk over the
     * `downloadProgress` stream op — a genuine flow, not the old 700 ms poll. Since the download is streamed
     * (track 3e) this is the actual bytes on disk, so a stalled fetch shows a stalled bar. Bytes (not a
     * fraction) so a caller downloading several regions can total them; the flow **completes** when the
     * download finishes, and the caller learns success/failure from [download]'s own return.
     */
    fun downloadProgress(regionId: String): Flow<Long> =
        client.mapRegionService.downloadProgress(regionId).map { it.writtenBytes.get() }

    /**
     * The id of the tightest installed region covering [point], via the catalog's coverage boxes,
     * or null if the catalog is unavailable / no installed region covers the point. Only installed
     * ids ([installedIds]) are considered, so the caller always has bytes to stage.
     */
    private suspend fun resolveCovering(point: LatLng, installedIds: Set<String>): String? {
        val catalog = client.mapRegionService.catalog().getOrNull() ?: return null
        val resolvable = catalog.regions.toList().mapNotNull { entry ->
            val id = entry.regionId.get()
            if (id !in installedIds) return@mapNotNull null
            val bounds = GeoBounds.parseBbox(entry.coverage.get()) ?: return@mapNotNull null
            ResolvableRegion(regionId = id, bounds = bounds, bundlePath = id)
        }
        return RegionResolver.coveringRegion(point, resolvable)?.regionId
    }
}
