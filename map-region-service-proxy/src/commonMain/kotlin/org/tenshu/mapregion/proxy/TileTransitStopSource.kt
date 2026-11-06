package org.tenshu.mapregion.proxy

import app.tenshu.vectortile.GeoBounds
import app.tenshu.vectortile.StopTileReader
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * The offline stop tier as a seam, mirroring [ProxyReverseGeocoderEgress] and [TileDecompressor]: the
 * op composes two tiers, and the *order* is the load-bearing decision, so it has to be testable
 * without standing up a PMTiles archive.
 *
 * ⚠ Public for the same reason its sibling seams are — it is a parameter of the public
 * `mapRegionServiceProxyImplementationFactory`, which the platform DI sites call. The tile-backed
 * implementation stays internal.
 */
fun interface TransitStopTiles {

    /** The nearest named stop within [withinM] metres, or `null` for "these tiles cannot say". */
    suspend fun nearestStop(lat: Double, lng: Double, withinM: Double): String?
}

/**
 * The **offline transit-stop tier** (§28.2): resolves the nearest named stop out of the map tiles the
 * proxy already streams for rail matching, so naming a station needs no third-party lookup.
 *
 * Why this is worth its own tier rather than just a cache in front of Photon:
 *
 * - **No coordinate leaves for a third party.** Today every coarse dwell near a station sends its
 *   position to komoot's public Photon instance. Here the egress is a byte range against our own tile
 *   CDN — tile-granular, on our own infrastructure — and *none at all* once the region is installed.
 * - **It answers with no network.** A traveller with a downloaded region gets station names on a
 *   plane, in a tunnel, or abroad on a dead SIM.
 * - **No rate limit and no availability dependency.** Photon's public instance is a courtesy.
 *
 * ⚠ It is not strictly better everywhere: coverage is whatever the region catalogue holds, so outside
 * a catalogued region this cannot answer and the network tier still runs. That is why the two compose
 * as tiers rather than one replacing the other.
 *
 * ⚠⚠ **Region resolution does not require an installed bundle** — [StreamingRegionSource] range-fetches
 * an uninstalled region, which is exactly the traveller case above. Requiring a download would refuse
 * to answer in the cases that matter most.
 */
internal class TileTransitStopSource(
    private val catalogSource: RegionCatalogSource,
    private val streamingSource: StreamingRegionSource,
    private val decompressor: TileDecompressor?,
) : TransitStopTiles {

    override suspend fun nearestStop(lat: Double, lng: Double, withinM: Double): String? {
        // No platform gzip binding ⇒ we cannot look. `null` hands the question to the next tier, which
        // is the honest answer: this is ignorance, not "there is no stop here".
        val decompress = decompressor ?: return null

        val point = GeoBounds(minLon = lng, minLat = lat, maxLon = lng, maxLat = lat)
        val regionId = resolveRegion(point) ?: return null

        return StopTileReader.nearestStop(
            lat = lat,
            lng = lng,
            withinM = withinM,
            readRange = { offset, length ->
                runCatchingCancellable { streamingSource.readRange(regionId, offset, length) }.getOrNull()
            },
            decompress = { bytes, compression -> decompress.decompress(bytes, compression) },
        )?.name
    }

    /**
     * The smallest catalogued region containing the point.
     *
     * Smallest-area wins, mirroring [RailLinesInImplementation]'s resolver and `RegionResolver`
     * app-side: a country bundle and a city bundle may both contain a point, and the smaller one is
     * cheaper to range-read.
     */
    private suspend fun resolveRegion(point: GeoBounds): String? =
        runCatchingCancellable { catalogSource.load().regions }.getOrNull()
            ?.mapNotNull { entry -> parseBbox(entry.coverage)?.let { entry.regionId to it } }
            ?.filter { (_, box) -> box.contains(point) }
            ?.minByOrNull { (_, box) -> (box.maxLon - box.minLon) * (box.maxLat - box.minLat) }
            ?.first

    /** Catalogue coverage is a `"minLng,minLat,maxLng,maxLat"` bbox string. */
    private fun parseBbox(coverage: String): GeoBounds? {
        val p = coverage.split(',').mapNotNull { it.trim().toDoubleOrNull() }
        if (p.size != 4) return null
        return GeoBounds(minLon = p[0], minLat = p[1], maxLon = p[2], maxLat = p[3])
    }

    private fun GeoBounds.contains(other: GeoBounds): Boolean =
        minLon <= other.minLon && maxLon >= other.maxLon &&
            minLat <= other.minLat && maxLat >= other.maxLat
}
