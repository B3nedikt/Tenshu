package app.tenshu.maps.data

import kotlinx.coroutines.CoroutineScope
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.proxy.InProcessProxy
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.security.createDefaultSyncCryptoManager
import org.tenshu.mapregion.common.mapRegionServiceCommonModule
import org.tenshu.mapregion.client.mapRegionServiceClientModule
import org.tenshu.mapregion.proxy.InstalledRegionData
import org.tenshu.mapregion.proxy.RegionDownloadProgressHolder
import org.tenshu.mapregion.proxy.StreamingRegionSource
import org.tenshu.mapregion.proxy.mapRegionServiceProxyImplementationFactory

/**
 * A **local, in-process proxy** hosting `:map-region-service`, exposing the [RegionRepository] the
 * Maps picker reads. Mirrors Timeline's `InProcessTimelineProxy` (`timeline-services.md` §7 P3b):
 * the pragmatic first client graph that lights up the picker over the real service surface,
 * without inter-app IPC / the control proxy yet (D6's proxy-mediated delivery is the later target).
 *
 * The generic SchemaRegistry → TContext → Database → ClientRegistry → client assembly lives in
 * the reusable [InProcessProxy] (`:core-common`); this supplies the map-region wiring (schema
 * modules + the op factory over the three seams). No `init*Service()` — the service has no relations.
 *
 * The dev bring-up trio: a fixed [MockRegionCatalogSource], an [InMemoryRegionStore] seeded with
 * one installed region, and a [MockRegionDownloader] that marks a region installed on "Get"
 * (no real HTTP — the mock catalog isn't served). They swap for `HttpRegionCatalogSource` +
 * `DefaultRegionStore` + `DefaultRegionDownloader` when the app-graph DI wiring lands.
 */
class InProcessMapRegionProxy(
    serviceScope: CoroutineScope,
) {

    private val catalogSource = MockRegionCatalogSource()
    private val regionStore = InMemoryRegionStore(seed = DEV_SEED)
    private val downloader = MockRegionDownloader(catalogSource, regionStore)
    // Dev graph has no CDN, so the streaming source's fetcher is a no-op — streamRegionRange is
    // unused here (installed regions still serve from the store); the real HttpRegionRangeFetcher
    // wires in Control's DI (mapRegionComponents).
    private val streamingSource = StreamingRegionSource(
        catalogSource = catalogSource,
        store = regionStore,
        fetcher = { _, _, _ -> ByteArray(0) },
    )
    // The mock downloader marks a region installed instantly (no real fetch), so it never ticks this —
    // the dev picker's progress bar simply stays at 0 for the brief mock "Get". Real ticking is the
    // DefaultRegionDownloader path in Control's DI.
    private val progressHolder = RegionDownloadProgressHolder()

    private val operationImplementationFactory = OperationImplementationFactory { id ->
        mapRegionServiceProxyImplementationFactory(
            id, catalogSource, regionStore, downloader, streamingSource, progressHolder,
        ) ?: implementationFactory(id)
    }

    private val proxy = InProcessProxy(
        clientId = CLIENT_ID,
        serviceScope = serviceScope,
        operationImplementationFactory = operationImplementationFactory,
        syncCryptoManager = createDefaultSyncCryptoManager(),
        schemaRegistryFactory = { tContextProvider ->
            createDefaultSchemaRegistry(
                tContextProvider = tContextProvider,
                extraConfigs = listOf(
                    mapRegionServiceCommonModule,
                    mapRegionServiceClientModule,
                ),
            )
        },
    )

    val client: TenshuClient = proxy.client

    /** The app-facing seam: a [RegionRepository] over this proxy's client. */
    val repository: RegionRepository = ClientRegionRepository(client)

    private companion object {
        const val CLIENT_ID = "app.tenshu.maps"

        // One region pre-installed so the picker demonstrates installed-state + delete.
        val DEV_SEED = listOf(
            InstalledRegionData(
                regionId = "at",
                name = "Österreich",
                localPath = "/regions/at.pmtiles",
                byteSize = 95_000_000L,
                sha256 = "dev-at",
                buildDate = "2026-07",
            ),
        )
    }
}
