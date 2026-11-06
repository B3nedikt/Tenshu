package app.tenshu.maps.data

import org.tenshu.mapregion.proxy.RegionCatalogData
import org.tenshu.mapregion.proxy.RegionCatalogSource
import org.tenshu.mapregion.proxy.RegionEntryData

/**
 * A fixed dev catalog for local bring-up (`maps-region-delivery.md` §7 Phase 1 "gateway stubbed
 * by direct CDN paths"). Stands in for `HttpRegionCatalogSource` until the Phase-1 pipeline
 * publishes a signed `catalog.json` — the DACH starter set (D7). Sizes are representative.
 *
 * [coverage] carries each region's real box as `west,south,east,north` (the boxes in
 * `deploy/tiles/regions.json`, and the exact form `GeoBounds.parseBbox` reads) — like the published
 * catalog now does. It was `""` here too, which meant the dev graph could never exercise
 * coverage-aware region resolution: every `parseBbox` returned null, so the terminus always fell back
 * to "the first region" no matter where the place was.
 */
class MockRegionCatalogSource : RegionCatalogSource {

    override suspend fun load(): RegionCatalogData = RegionCatalogData(
        schemaVersion = 1,
        buildDate = "2026-07",
        regions = listOf(
            RegionEntryData(
                regionId = "de-bayern", name = "Bayern", coverage = "8.98,47.27,13.84,50.56",
                byteSize = 210_000_000L, sha256 = "dev-bayern", tileSchemaVersion = 4,
                path = "regions/2026-07/de-bayern.pmtiles",
            ),
            RegionEntryData(
                regionId = "at", name = "Österreich", coverage = "9.53,46.37,17.16,49.02",
                byteSize = 95_000_000L, sha256 = "dev-at", tileSchemaVersion = 4,
                path = "regions/2026-07/at.pmtiles",
            ),
            RegionEntryData(
                regionId = "ch", name = "Schweiz", coverage = "5.96,45.82,10.49,47.81",
                byteSize = 120_000_000L, sha256 = "dev-ch", tileSchemaVersion = 4,
                path = "regions/2026-07/ch.pmtiles",
            ),
        ),
    )
}
