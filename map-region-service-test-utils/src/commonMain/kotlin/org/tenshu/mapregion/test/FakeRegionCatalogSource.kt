package org.tenshu.mapregion.test

import org.tenshu.mapregion.proxy.RegionCatalogData
import org.tenshu.mapregion.proxy.RegionCatalogSource

/**
 * Deterministic [RegionCatalogSource] for tests. Returns a fixed [RegionCatalogData], or throws
 * [failure] to exercise the typed-failure path (a verification/transport error must not look
 * like an empty catalog). Stands in for the slice-2b HTTP source.
 */
class FakeRegionCatalogSource(
    private val data: RegionCatalogData = RegionCatalogData(
        schemaVersion = 1,
        buildDate = "2026-07",
        regions = emptyList(),
    ),
    private val failure: Throwable? = null,
) : RegionCatalogSource {

    override suspend fun load(): RegionCatalogData =
        failure?.let { throw it } ?: data
}
