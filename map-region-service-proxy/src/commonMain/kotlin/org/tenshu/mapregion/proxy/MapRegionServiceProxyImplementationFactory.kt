package org.tenshu.mapregion.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.mapregion.Catalog
import org.tenshu.core.mapregion.DeleteRegion
import org.tenshu.core.mapregion.DownloadRegion
import org.tenshu.core.mapregion.Installed
import org.tenshu.core.mapregion.ReadRegionRange
import org.tenshu.core.mapregion.DownloadProgress
import org.tenshu.core.mapregion.RailLinesIn
import org.tenshu.core.mapregion.LinesServing
import org.tenshu.core.mapregion.NearestTransitStop
import org.tenshu.core.mapregion.ReverseGeocodePlace
import org.tenshu.core.mapregion.ReverseGeocodeCandidates
import org.tenshu.core.mapregion.StreamRegionRange
import org.tenshu.mapregion.proxy.executor.CatalogImplementation
import org.tenshu.mapregion.proxy.executor.DeleteRegionImplementation
import org.tenshu.mapregion.proxy.executor.DownloadRegionImplementation
import org.tenshu.mapregion.proxy.executor.InstalledImplementation
import org.tenshu.mapregion.proxy.executor.ReadRegionRangeImplementation
import org.tenshu.mapregion.proxy.executor.DownloadProgressImplementation
import org.tenshu.mapregion.proxy.executor.RailLinesInImplementation
import org.tenshu.mapregion.proxy.executor.LinesServingImplementation
import org.tenshu.mapregion.proxy.executor.NearestTransitStopImplementation
import org.tenshu.mapregion.proxy.executor.ReverseGeocodeImplementation
import org.tenshu.mapregion.proxy.executor.ReverseGeocodeCandidatesImplementation
import org.tenshu.mapregion.proxy.executor.StreamRegionRangeImplementation

/**
 * Resolves `:map-region-service` operations to their proxy implementations. The region ops need
 * the [RegionCatalogSource] (fetch/verify), [RegionStore] (canonical store) and [RegionDownloader]
 * (download) seams, so the factory takes them as parameters — constructed at the platform DI site
 * (slice 2c), mirroring `contactServiceProxyImplementationFactory(id, contactComponents)`.
 */
fun mapRegionServiceProxyImplementationFactory(
    id: String,
    catalogSource: RegionCatalogSource,
    regionStore: RegionStore,
    downloader: RegionDownloader,
    streamingSource: StreamingRegionSource,
    progressHolder: RegionDownloadProgressHolder,
    reverseGeocoder: ProxyReverseGeocoderEgress? = null,
    tileDecompressor: TileDecompressor? = null,
    /**
     * The offline stop tier. Defaults to the real [TileTransitStopSource] over the seams above; an
     * override exists so a test can pin the *tier order* without standing up a PMTiles archive.
     */
    transitStopTiles: TransitStopTiles? = null,
    /**
     * The metered-network gate (D5 / §7.1 track 3g). Defaults to [MeteredNetworkSignal.Unknown], which
     * never refuses — so a platform or test that wires no signal downloads exactly as it did before.
     */
    meteredNetwork: MeteredNetworkSignal = MeteredNetworkSignal.Unknown,
    /**
     * The transit-line overlay tier (§33 L4). ⚠ Needs a [RegionRangeFetcher] to fetch the artefact,
     * which the factory has no other way to reach — so a DI site that wires nothing gets a tier that
     * answers `""`: a `linesServing` op that is present and honest rather than absent.
     *
     * ⚠⚠ Appended **after** [meteredNetwork] rather than beside its sibling seams, because several
     * call sites pass these positionally — inserting in the middle silently rebinds their arguments
     * (it bound `meteredNetwork` to `transitLines` here, caught only by the type mismatch).
     */
    transitLines: TransitLineOverlays? = null,
    overlayFetcher: RegionRangeFetcher? = null,
): OperationImplementation? {
    return when (id) {
        Catalog.ID -> CatalogImplementation(catalogSource)
        Installed.ID -> InstalledImplementation(regionStore)
        DeleteRegion.ID -> DeleteRegionImplementation(regionStore)
        DownloadRegion.ID -> DownloadRegionImplementation(downloader, meteredNetwork)
        ReadRegionRange.ID -> ReadRegionRangeImplementation(regionStore)
        StreamRegionRange.ID -> StreamRegionRangeImplementation(streamingSource)
        DownloadProgress.ID -> DownloadProgressImplementation(progressHolder, downloader)
        ReverseGeocodePlace.ID -> ReverseGeocodeImplementation(reverseGeocoder)
        // ⚠ The tile tier is built from the *same* seams `RailLinesIn` already uses, so it needs no
        // new DI parameter and lights up wherever rail matching already works.
        NearestTransitStop.ID -> NearestTransitStopImplementation(
            geocoder = reverseGeocoder,
            tileStops = transitStopTiles
                ?: TileTransitStopSource(catalogSource, streamingSource, tileDecompressor),
        )
        LinesServing.ID -> LinesServingImplementation(
            overlays = transitLines ?: CatalogTransitLineOverlays(catalogSource, overlayFetcher),
        )
        ReverseGeocodeCandidates.ID -> ReverseGeocodeCandidatesImplementation(reverseGeocoder)
        RailLinesIn.ID -> RailLinesInImplementation(catalogSource, streamingSource, tileDecompressor)
        else -> null
    }
}
