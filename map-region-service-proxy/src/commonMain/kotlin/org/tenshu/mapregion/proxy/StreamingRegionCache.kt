package org.tenshu.mapregion.proxy

/**
 * The proxy's per-region streamed-tile cache (`maps-region-delivery.md` D10): fixed-size,
 * chunk-aligned byte ranges fetched on demand from the tile host. Caching is what keeps streaming
 * cheap and privacy-bounded — re-views and overlapping reads make no new request, and a streamed
 * user accrues a **partial offline cache** over a session (§5 mitigation). Chunks are keyed by
 * `(regionId, chunkIndex)` where a chunk is [StreamingChunkBytes] bytes at `chunkIndex * chunkBytes`.
 */
interface StreamingRegionCache {
    fun get(regionId: String, chunkIndex: Long): ByteArray?
    fun put(regionId: String, chunkIndex: Long, bytes: ByteArray)
    /** Drops every cached chunk of [regionId] (e.g. on a catalog build change). */
    fun evictRegion(regionId: String)
}

/** Chunk size for streamed range fetches + cache alignment (matches the app's 256 KiB read window). */
const val StreamingChunkBytes: Int = 256 * 1024

/**
 * Bounded in-memory [StreamingRegionCache]: at most [maxChunks] chunks total across all regions
 * (≈ `maxChunks × 256 KiB`), evicting the oldest on overflow (insertion-order; a re-`put` refreshes
 * a chunk's position). v1 is memory-only — a disk-backed variant (a growing partial bundle that
 * survives across sessions) is a later refinement. Not internally synchronised; the op layer drives
 * it single-threaded.
 */
class InMemoryStreamingRegionCache(
    private val maxChunks: Int = 1024,
) : StreamingRegionCache {

    private data class Key(val regionId: String, val chunkIndex: Long)

    private val chunks = LinkedHashMap<Key, ByteArray>()

    override fun get(regionId: String, chunkIndex: Long): ByteArray? =
        chunks[Key(regionId, chunkIndex)]

    override fun put(regionId: String, chunkIndex: Long, bytes: ByteArray) {
        val key = Key(regionId, chunkIndex)
        chunks.remove(key)
        chunks[key] = bytes
        while (chunks.size > maxChunks) {
            chunks.remove(chunks.keys.first())
        }
    }

    override fun evictRegion(regionId: String) {
        chunks.keys.removeAll { it.regionId == regionId }
    }
}
