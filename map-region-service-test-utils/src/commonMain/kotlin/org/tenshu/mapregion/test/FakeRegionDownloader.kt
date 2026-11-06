package org.tenshu.mapregion.test

import org.tenshu.mapregion.proxy.InstalledRegionData
import org.tenshu.mapregion.proxy.RegionDownloader

/**
 * Deterministic [RegionDownloader] for tests of the `downloadRegion` op pipe: returns a fixed
 * [InstalledRegionData] (defaulting to a synthetic one for the requested id), or throws [failure]
 * to exercise the typed-failure path. Stands in for `DefaultRegionDownloader` (tested separately
 * against a MockEngine).
 */
class FakeRegionDownloader(
    private val result: InstalledRegionData? = null,
    private val failure: Throwable? = null,
    /**
     * What [isDownloading] reports. Defaults to `true` — "a download is in flight" — so a test that
     * only exercises the `downloadRegion` pipe keeps the progress stream open exactly as before this
     * flag existed. Set `false` to model a proxy that has no such download (a restarted proxy), which
     * is what makes the stream terminate.
     */
    private val downloading: Boolean = true,
) : RegionDownloader {

    override suspend fun isDownloading(regionId: String): Boolean = downloading

    override suspend fun download(regionId: String): InstalledRegionData =
        failure?.let { throw it } ?: result ?: InstalledRegionData(
            regionId = regionId,
            name = regionId,
            localPath = "/fake/$regionId.pmtiles",
            byteSize = 0L,
            sha256 = "",
            buildDate = "2026-07",
        )
}
