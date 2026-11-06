package app.tenshu.maps.data

import org.tenshu.mapregion.proxy.InstalledRegionData
import org.tenshu.mapregion.proxy.RegionCatalogSource
import org.tenshu.mapregion.proxy.RegionDownloader

/**
 * Dev [RegionDownloader] for the in-process bring-up graph: resolves the region in the (mock)
 * catalog and marks it installed in the shared [store], preserving the catalog's advertised size
 * — **without** a real HTTP fetch or sha256 gate (the mock catalog's paths aren't served). Stands
 * in for `DefaultRegionDownloader` until the app-graph DI wiring points at a live tile host. This
 * is what lets the picker's "Get" button actually move a region to *installed* in dev.
 */
class MockRegionDownloader(
    private val catalogSource: RegionCatalogSource,
    private val store: InMemoryRegionStore,
) : RegionDownloader {

    /**
     * The mock install is synchronous — it never registers a progress entry and there is no window in
     * which a download is "running" — so a `downloadProgress` subscriber must be told nothing is
     * coming rather than waiting forever for ticks this downloader never emits.
     */
    override suspend fun isDownloading(regionId: String): Boolean = false

    override suspend fun download(regionId: String): InstalledRegionData {
        val catalog = catalogSource.load()
        val entry = catalog.regions.firstOrNull { it.regionId == regionId }
            ?: error("No region '$regionId' in the catalog.")

        return store.install(
            InstalledRegionData(
                regionId = entry.regionId,
                name = entry.name,
                localPath = "/regions/${entry.regionId}.pmtiles",
                byteSize = entry.byteSize,
                sha256 = entry.sha256,
                buildDate = catalog.buildDate,
            ),
        )
    }
}
