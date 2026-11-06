package org.tenshu.mapregion.proxy

/**
 * The proxy's streaming region range source (`maps-region-delivery.md` D10) — serves a byte range of
 * a region **without** a full download. This is the remote-on-demand half of the one range-provider:
 *
 *  - An **installed** region serves from the local bundle ([RegionStore], the "make available
 *    offline" mode) — zero egress.
 *  - Otherwise it range-fetches the covering [StreamingChunkBytes]-aligned chunks from the tile host
 *    on a cache miss ([RegionRangeFetcher]), caches them ([StreamingRegionCache]), and assembles the
 *    requested slice. Nothing downloads until the map looks.
 *
 * MapLibre is unaware which mode served a read — the app-side `buildStreamedPartial` assembles the
 * ranges into a sparse `.pmtiles` and MapLibre only ever opens a real local `pmtiles://file://`; the
 * app never gains `INTERNET` (D6/R1).
 *
 * ⚠ The "fd" this once named is dead. The `ProxyFileDescriptor` route was spike-proven NEGATIVE on
 * 2026-07-13 — maplibre-native `open()`s the path string itself — so a materialised sparse file is
 * the only shape it accepts, and that is what the app builds. See `ui-map-agent.md`.
 *
 * [prefetchLowZoom] eagerly warms the low-zoom prefix so the surface paints populated on first touch
 * (v1 = a bounded byte prefix — the low-zoom band sits near the start of a PMTiles extract; a
 * directory-aware z0–8 selection is a refinement).
 */
class StreamingRegionSource(
    private val catalogSource: RegionCatalogSource,
    private val store: RegionStore,
    private val fetcher: RegionRangeFetcher,
    private val cache: StreamingRegionCache = InMemoryStreamingRegionCache(),
    private val chunkBytes: Int = StreamingChunkBytes,
    private val lowZoomPrefetchBytes: Long = 4L * 1024 * 1024,
) {

    private val entries = mutableMapOf<String, RegionEntryData>()

    /**
     * Serves [length] bytes at [offset] of [regionId] — the installed bundle if present, else
     * streamed chunks (cache-first, CDN-range on a miss). Bytes at/after EOF come back **empty**; a
     * region in neither the store nor the catalog throws [UnknownRegionException].
     */
    suspend fun readRange(regionId: String, offset: Long, length: Int): ByteArray {
        store.readRange(regionId, offset, length)?.let { return it }
        val entry = resolveEntry(regionId)
        return streamRange(regionId, entry, offset, length)
    }

    /**
     * Eagerly fetches the low-zoom prefix of [regionId] into the cache (D10 first-touch prefetch), so
     * a subsequent read in that band is served from disk with no request. No-op for an installed
     * region (already local).
     */
    suspend fun prefetchLowZoom(regionId: String) {
        if (store.readRange(regionId, 0, 1) != null) return
        val entry = resolveEntry(regionId)
        val upTo = minOf(lowZoomPrefetchBytes, entry.byteSize)
        var chunkIndex = 0L
        while (chunkIndex * chunkBytes < upTo) {
            ensureChunk(regionId, entry, chunkIndex)
            chunkIndex++
        }
    }

    private suspend fun resolveEntry(regionId: String): RegionEntryData =
        entries.getOrPut(regionId) {
            catalogSource.load().regions.firstOrNull { it.regionId == regionId }
                ?: throw UnknownRegionException("No region '$regionId' in the catalog.")
        }

    private suspend fun streamRange(
        regionId: String,
        entry: RegionEntryData,
        offset: Long,
        length: Int,
    ): ByteArray {
        if (offset >= entry.byteSize) return ByteArray(0)
        val endExclusive = minOf(offset + length.toLong(), entry.byteSize)
        val out = ByteArray((endExclusive - offset).toInt())
        var pos = offset
        while (pos < endExclusive) {
            val chunkIndex = pos / chunkBytes
            val chunkStart = chunkIndex * chunkBytes
            val chunk = ensureChunk(regionId, entry, chunkIndex)
            val within = (pos - chunkStart).toInt()
            if (within >= chunk.size) break // short chunk — treat as EOF
            val n = minOf(chunk.size - within, (endExclusive - pos).toInt())
            chunk.copyInto(out, (pos - offset).toInt(), within, within + n)
            pos += n
        }
        return if (pos - offset == out.size.toLong()) out else out.copyOf((pos - offset).toInt())
    }

    private suspend fun ensureChunk(
        regionId: String,
        entry: RegionEntryData,
        chunkIndex: Long,
    ): ByteArray {
        cache.get(regionId, chunkIndex)?.let { return it }
        val chunkStart = chunkIndex * chunkBytes
        val fetchLen = minOf(chunkBytes.toLong(), entry.byteSize - chunkStart).toInt()
        val bytes = fetcher.fetchRange(entry.path, chunkStart, fetchLen)
        cache.put(regionId, chunkIndex, bytes)
        return bytes
    }
}
