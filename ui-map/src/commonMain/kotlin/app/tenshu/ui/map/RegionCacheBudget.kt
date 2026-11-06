package app.tenshu.ui.map

/**
 * The eviction rule behind the streamed basemap's disk budget (`maps-region-delivery.md` §7.1 track 1a,
 * "still open: the cache has no size cap").
 *
 * Kept pure — and in `commonMain`, though the only cache is Android's — so the policy is testable without
 * a filesystem. The platform side gathers the facts and performs the deletions; everything decided here
 * is arithmetic.
 *
 * **Whole directories, never partial files.** A region's cache is one *sparse* archive whose populated
 * ranges are recorded in a sibling manifest. Freeing part of it would mean punching holes and rewriting
 * the manifest, for a cache that is re-fetchable by construction — so eviction is per region, all or
 * nothing.
 *
 * ⚠ This budgets the **streamed range cache only** — the app-side scratch built from whatever the user
 * happened to pan over. It must never reach a *downloaded* region: those live in Control's canonical store
 * (D5), are a deliberate user action, and are the whole point of "make available offline". The two are in
 * different sandboxes, which is what keeps the distinction structural rather than a rule to remember.
 */
internal const val REGION_CACHE_CEILING_BYTES: Long = 128L * 1024 * 1024

/** One region's cache directory, as the budget sees it. */
internal data class CachedRegionDir(
    val name: String,
    /** Resident bytes — the manifest's ranges, i.e. what was actually fetched, not the archive's size. */
    val bytes: Long,
    /**
     * When this cache was last opened. `null` = unknown, which sorts **newest** so it is evicted last.
     *
     * ⚠ Deliberately that way round: a device or filesystem that cannot report a time would otherwise
     * class its entire cache as coldest and wipe all of it at once — the failure being conservative about
     * is the mass eviction, not the retention.
     */
    val lastUsedMs: Long?,
    /**
     * False when the directory must be kept regardless of age — it has a live in-memory cache instance,
     * or it is the one being opened right now. Its bytes still count against [ceilingBytes]: they occupy
     * real disk whether or not we may reclaim them.
     */
    val evictable: Boolean,
)

/**
 * The directory names to delete so the total falls to [ceilingBytes], coldest first.
 *
 * Returns empty when already within budget. When even evicting everything evictable would not get under
 * the ceiling — every remaining directory is pinned — it returns all of them: the best that can be done,
 * and the caller logs the shortfall rather than pretending the budget held.
 */
internal fun regionCacheEvictions(
    dirs: List<CachedRegionDir>,
    ceilingBytes: Long = REGION_CACHE_CEILING_BYTES,
): List<String> {
    var total = dirs.sumOf { it.bytes }
    if (total <= ceilingBytes) return emptyList()

    // Coldest first; unknown ages last (see [CachedRegionDir.lastUsedMs]). Name breaks ties so the
    // outcome is deterministic — two caches written in the same millisecond are otherwise ordered by
    // whatever the directory listing happened to return.
    val coldestFirst = dirs
        .filter { it.evictable }
        .sortedWith(compareBy({ it.lastUsedMs ?: Long.MAX_VALUE }, { it.name }))

    val evict = mutableListOf<String>()
    for (dir in coldestFirst) {
        if (total <= ceilingBytes) break
        evict += dir.name
        total -= dir.bytes
    }
    return evict
}
