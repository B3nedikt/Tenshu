package app.tenshu.maps.data

import app.tenshu.mapregion.app.LatLng
import app.tenshu.mapregion.app.RegionBundleStager
import app.tenshu.mapregion.app.RegionTerminus
import app.tenshu.maps.model.GeoPoint
import app.tenshu.maps.model.RegionItem
import app.tenshu.reachability.ControlReachability
import kotlinx.coroutines.CancellationException
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.fold
import org.tenshu.core.getOrNull
import org.tenshu.core.mapregion.catalog
import org.tenshu.core.mapregion.deleteRegion
import org.tenshu.core.mapregion.downloadRegion
import org.tenshu.core.mapregion.installed
import org.tenshu.mapregion.client.mapRegionService

/**
 * The real [RegionRepository] over a [TenshuClient]'s `MapRegionService` (`maps-region-delivery.md`
 * D5/D6): joins the verified catalog with the proxy's install state, deletes an installed region,
 * downloads (the seam — see [download]), and stages an installed bundle's bytes into the app
 * sandbox for rendering ([renderableRegionPath]). Reactive-lite: the picker VM re-fetches after a
 * mutation.
 *
 * [stager] is the platform file store the render-hop drain fills (Android `filesDir` / desktop
 * cache dir); when absent (headless dev/test graph, or a platform with no staging seam) the
 * renderer just keeps the bundled demo.
 */
class ClientRegionRepository(
    private val client: TenshuClient,
    private val stager: RegionBundleStager? = null,
    /**
     * The app's shared Control-reachability engine ([mapsControlReachability]) — the region
     * reads feed the same shell-banner signal as the place reads (maps-app.md §7.1). `null`
     * (tests, the headless dev graph) = untracked. **Failure taxonomy is load-bearing:**
     * `installed()`/`deleteRegion` are proxy-local, so their failures ARE a transport signal;
     * `catalog()`/`downloadRegion` can fail with Control perfectly healthy (the proxy fetches
     * from the region host), so they mark only *success* — a folded failure there must not
     * flash the "Can't reach Control" banner over a tile-host outage. A *thrown* dispatch is
     * transport-shaped either way. The probe still owns the definitive answer.
     */
    private val reach: ControlReachability? = null,
) : RegionRepository {

    override suspend fun regions(): RegionsRead {
        // ⚠ Install state degrades, the catalogue does not. An unreadable install set costs the
        // ticks beside each row; an unreadable catalogue means there is nothing to show at all,
        // and saying "no regions" then is the lie this read used to tell.
        val installedIds = client.mapRegionService.installed().fold(
            onSuccess = { installed -> installed.toList().map { it.regionId.get() }.toSet() },
            onFailure = { emptySet() },
        )

        val catalog = client.mapRegionService.catalog().fold(
            onSuccess = { it },
            // ⚠ A catalogue failure is NOT evidence Control is unreachable — the fetch reaches the
            // region host, which can be down while the proxy is perfectly healthy. So it reports
            // `Unavailable` without touching the engine, and the shell's banner stays whatever the
            // probe and the transport say it is.
            onFailure = { null },
        ) ?: return RegionsRead.Unavailable("the region catalogue could not be read")

        return RegionsRead.Regions(
            catalog.regions.toList().map { entry ->
                RegionItem(
                    regionId = entry.regionId.get(),
                    name = entry.name.get(),
                    byteSize = entry.byteSize.get(),
                    installed = entry.regionId.get() in installedIds,
                )
            }
        )
    }

    /**
     * Downloads a region via the proxy's `downloadRegion` op (`maps-region-delivery.md` §4) and
     * returns whether it landed. In the dev in-process graph the download is simulated
     * ([MockRegionDownloader]); on-device it rides `DefaultRegionDownloader` (real fetch + sha256).
     */
    override suspend fun download(regionId: String): Boolean = try {
        val landed = client.mapRegionService.downloadRegion(regionId).getOrNull() != null
        // ⚠ A folded failure could be the region host rather than Control (see [reach]), so it
        // is not marked unreachable. The success needs no mark either: the executor classifies the
        // round trip and `answered` wakes the probe.
        landed
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reach?.markUnreachable()
        false
    }

    override suspend fun delete(regionId: String) {
        try {
            client.mapRegionService.deleteRegion(regionId).fold(
                // ⚠ Proxy-local op, so a folded failure IS a transport signal (and the result is no
                // longer silently discarded — tech-debt "TResult ops discardable"). The success
                // needs no counterpart: the executor classifies the round trip and `answered`
                // wakes the probe.
                onSuccess = { },
                onFailure = { reach?.markUnreachable() },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reach?.markUnreachable()
        }
    }

    /**
     * Delegates to the shared [RegionTerminus] (`:map-region-app`): drains the bytes of the installed
     * region **covering [location]** into the [stager]'s file store and returns the staged `.pmtiles`
     * path (`maps-region-delivery.md` §7 render slice). Coverage-aware (D7 — tightest box wins), with a
     * null/uncovered point falling back to the first installed region. No stager (headless dev) or
     * nothing installed → `null` → the basemap keeps the bundled demo.
     */
    override suspend fun renderableRegionPath(location: GeoPoint?): String? =
        RegionTerminus(client, stager).renderableRegionPathFor(location?.toLatLng())

    /**
     * The D10 streamed default, over the same shared [RegionTerminus] (`:map-region-app`) Timeline and
     * Fit already use: resolves the catalog region **covering [location]** and returns a lazy
     * [RegionStreamHandle] whose `readRange` rides the proxy's `streamRegionRange` op — the render
     * fetches only the covering tiles on demand (no full download, no stager). Streaming does **not**
     * require the region be installed: the proxy range-fetches from the tile host on a cache miss.
     *
     * This used to take **the first catalog region**, which was invisible while one region existed and
     * wrong as soon as two did (a Firenze place could stream a Bayern bundle). It also duplicated the
     * terminus's catalog/read plumbing; delegating keeps one resolution rule for all three `:ui-map`
     * consumers. Returns `null` when the catalog is empty or unreachable, so the snippet falls back to
     * [renderableRegionPath] then the bundled demo.
     */
    override suspend fun renderableStream(location: GeoPoint?): RegionStreamHandle? {
        val handle = RegionTerminus(client, stager).renderableStream(location?.toLatLng()) ?: return null
        // Re-wrap the terminus's neutral handle in the Maps-side one the VM holds (the same hop
        // Timeline/Fit make into their own RouteRegionStream — it keeps :maps-shared-logic free of
        // any :map-region dependency).
        return RegionStreamHandle(
            regionId = handle.regionId,
            totalSize = handle.totalSize,
            readRange = handle.readRange,
        )
    }

    /** Progress `0f..1f` for an in-flight download (§7.1 download UX) — pushed per chunk by the proxy. */
    override fun downloadProgress(regionId: String) =
        RegionTerminus(client, stager).downloadProgress(regionId)

    /** The covering region this place could be kept offline from (track 3d), over the shared terminus. */
    override suspend fun offlineOffer(location: GeoPoint?): OfflineRegionOffer? {
        val offer = RegionTerminus(client, stager).offerFor(location?.toLatLng()) ?: return null
        return OfflineRegionOffer(
            regionId = offer.regionId,
            name = offer.name,
            byteSize = offer.byteSize,
            installed = offer.installed,
        )
    }

}

/** Maps' own coordinate → the neutral `:map-region-app` one the terminus resolves against. */
private fun GeoPoint.toLatLng(): LatLng = LatLng(latitude = latitude, longitude = longitude)
