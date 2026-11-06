package org.tenshu.mapregion.proxy.executor

import app.tenshu.vectortile.GeoBounds
import app.tenshu.vectortile.LonLatPolyline
import app.tenshu.vectortile.RailTileReader
import org.tenshu.core.Data
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.mapregion.RailLineGeometry
import org.tenshu.core.mapregion.RailLinesIn
import org.tenshu.core.number.TInteger
import org.tenshu.mapregion.proxy.RegionCatalogSource
import org.tenshu.mapregion.proxy.StreamingRegionSource
import org.tenshu.mapregion.proxy.TileDecompressor

/**
 * Serves railway geometry for a bounding box (M9-4).
 *
 * The proxy owns this because it is the only side that can do it: the app has no `INTERNET`, no
 * access to region bundles, and no gzip. Here the region is resolved from the catalogue's coverage
 * boxes, tiles are read through [StreamingRegionSource] (which serves an installed bundle from disk
 * or range-fetches an uninstalled one), decoded, and filtered to *running* track.
 *
 * ⚠ **Only the bounding box crosses the boundary — never the user's trace.** Matching happens
 * app-side against geometry returned here, which costs more bytes than sending the route to Control
 * would have, and keeps the most sensitive artefact inside one process.
 */
internal class RailLinesInImplementation(
    private val catalogSource: RegionCatalogSource,
    private val streamingSource: StreamingRegionSource,
    private val decompressor: TileDecompressor?,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val context = executionContext.context
        val decompress = decompressor
            // No platform gzip binding ⇒ we cannot look. Empty is the honest answer here; the app
            // treats a failure/empty from this op as `RailEvidence.Unavailable`, never as evidence.
            ?: return emptyList<RailLineGeometry>().toTList(context) as T

        val minLatT: TInteger = params.read(RailLinesIn.Params.MIN_LAT_E7)
        val minLngT: TInteger = params.read(RailLinesIn.Params.MIN_LNG_E7)
        val maxLatT: TInteger = params.read(RailLinesIn.Params.MAX_LAT_E7)
        val maxLngT: TInteger = params.read(RailLinesIn.Params.MAX_LNG_E7)
        val minLat = minLatT.get() / 1e7
        val minLng = minLngT.get() / 1e7
        val maxLat = maxLatT.get() / 1e7
        val maxLng = maxLngT.get() / 1e7
        val bounds = GeoBounds(minLon = minLng, minLat = minLat, maxLon = maxLng, maxLat = maxLat)

        val regionId = resolveRegion(bounds)
            ?: return emptyList<RailLineGeometry>().toTList(context) as T

        val lines = RailTileReader.railLinesIn(
            bounds = bounds,
            readRange = { offset, length ->
                runCatchingCancellable { streamingSource.readRange(regionId, offset, length) }.getOrNull()
            },
            decompress = { bytes, compression -> decompress.decompress(bytes, compression) },
        )

        return lines.map { line ->
            RailLineGeometry.create(
                context = context,
                kind = line.kind,
                polyline = LonLatPolyline.encode(line.points),
            )
        }.toTList(context) as T
    }

    /**
     * The smallest catalogued region whose coverage box contains the query box.
     *
     * Smallest-area wins, mirroring `RegionResolver` app-side: a country bundle and a city bundle
     * may both contain a point, and the city one is cheaper to range-read. Installed state is *not*
     * consulted — [StreamingRegionSource] serves uninstalled regions by range-fetching, so requiring
     * a download would refuse to answer in exactly the cases a traveller cares about.
     */
    private suspend fun resolveRegion(bounds: GeoBounds): String? =
        runCatchingCancellable { catalogSource.load().regions }.getOrNull()
            ?.mapNotNull { entry -> parseBbox(entry.coverage)?.let { entry.regionId to it } }
            ?.filter { (_, box) -> box.contains(bounds) }
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
