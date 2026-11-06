package org.tenshu.mapregion.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.mapregion.proxy.ProxyReverseGeocoderEgress
import org.tenshu.mapregion.proxy.MeteredNetworkSignal
import org.tenshu.mapregion.proxy.RegionCatalogSource
import org.tenshu.mapregion.proxy.RegionDownloadProgressHolder
import org.tenshu.mapregion.proxy.RegionDownloader
import org.tenshu.mapregion.proxy.RegionStore
import org.tenshu.mapregion.proxy.StreamingRegionSource
import org.tenshu.mapregion.proxy.TileDecompressor
import org.tenshu.mapregion.proxy.TransitStopTiles
import org.tenshu.mapregion.proxy.mapRegionServiceProxyImplementationFactory

/**
 * Stands up an in-process proxy hosting `:map-region-service` over injected [catalogSource] +
 * [regionStore] + [downloader] + [streamingSource] fakes. No `init*Service()` call — the service
 * declares no relations, so there is no relation-maintainer to register (unlike `createPlaceProxy`).
 *
 * [streamingSource] defaults to one over an empty [FakeRegionRangeFetcher] (streaming ops unused);
 * pass a bundle-backed source to exercise `streamRegionRange` (see `MapRegionServiceTest`).
 */
fun createMapRegionProxy(
    catalogSource: RegionCatalogSource = FakeRegionCatalogSource(),
    regionStore: RegionStore = FakeRegionStore(),
    downloader: RegionDownloader = FakeRegionDownloader(),
    streamingSource: StreamingRegionSource = StreamingRegionSource(
        catalogSource = catalogSource,
        store = regionStore,
        fetcher = FakeRegionRangeFetcher(),
    ),
    progressHolder: RegionDownloadProgressHolder = RegionDownloadProgressHolder(),
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    reverseGeocoder: ProxyReverseGeocoderEgress? = null,
    /** Platform gzip for `railLinesIn` (M9-4); `null` leaves the op disabled, as on iOS. */
    tileDecompressor: TileDecompressor? = null,
    /** Offline stop tier for `nearestTransitStop`; `null` uses the real tile-backed one. */
    transitStopTiles: TransitStopTiles? = null,
    /** The metered-network gate (D5 / §7.1 3g); defaults to "not metered", so downloads are allowed. */
    meteredNetwork: MeteredNetworkSignal = MeteredNetworkSignal.Unknown,
) = TestProxy(
    schemaRegistry = createMapRegionTestRegistry(),
    coroutineScope = coroutineScope,
    implementationFactoryOverride = { id, _ ->
        mapRegionServiceProxyImplementationFactory(
            id, catalogSource, regionStore, downloader, streamingSource, progressHolder,
            reverseGeocoder, tileDecompressor, transitStopTiles, meteredNetwork,
        )
    }
)
