package org.tenshu.core.common.proxy

/**
 * In-memory [PreviewCache] backed by a single map keyed by
 * `<sha256-hex>/<w>x<h>`. Used on desktop (matches the `InMemoryFileDatabase`
 * pairing) and in tests.
 *
 * Persistence is process-lifetime only — fine for desktop today (the
 * desktop `FileDatabase` is itself in-memory) and the right shape for
 * `:core-test-utils` style fixtures. The Android variant
 * (`AndroidPreviewCache`) writes to `<filesDir>/.previews/` for cross-restart
 * persistence; that's where the Binder-watchdog crash lives.
 *
 * Concurrency: the proxy serialises operation execution per
 * `ExecutionContext`, so `GetPreview` dispatch is non-concurrent within
 * an execution scope. Same convention as `InMemoryFileDatabase` — no
 * lock here; if a future profile shows cross-context contention, layer
 * synchronisation in (kotlinx.atomicfu or a Mutex) without changing the
 * surface.
 */
class InMemoryPreviewCache(
    override val maxEntryBytes: Int = PreviewCache.DEFAULT_MAX_ENTRY_BYTES
) : PreviewCache {

    private val entries = mutableMapOf<String, ByteArray>()

    override fun get(contentHash: String, width: Int, height: Int): ByteArray? {
        return entries[cacheKey(contentHash, width, height)]
    }

    override fun put(
        contentHash: String,
        width: Int,
        height: Int,
        bytes: ByteArray
    ): Boolean {
        if (bytes.size > maxEntryBytes) return false
        entries[cacheKey(contentHash, width, height)] = bytes
        return true
    }
}

internal fun cacheKey(contentHash: String, width: Int, height: Int): String =
    "$contentHash/${width}x$height"
