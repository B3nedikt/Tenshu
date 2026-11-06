package org.tenshu.mapregion.test

import org.tenshu.mapregion.proxy.RegionRangeFetcher

/**
 * In-memory [RegionRangeFetcher] for tests: serves ranges out of a bundle-bytes map keyed by the
 * CDN-relative path, counting its calls so a test can assert cache hits. Stands in for the
 * `HttpRegionRangeFetcher` (a real `Range` GET).
 */
class FakeRegionRangeFetcher(
    private val bundles: Map<String, ByteArray> = emptyMap(),
) : RegionRangeFetcher {

    var fetches = 0
        private set

    override suspend fun fetchRange(path: String, offset: Long, length: Int): ByteArray {
        fetches++
        val all = bundles[path] ?: return ByteArray(0)
        if (offset >= all.size) return ByteArray(0)
        val end = minOf(all.size.toLong(), offset + length).toInt()
        return all.copyOfRange(offset.toInt(), end)
    }
}
