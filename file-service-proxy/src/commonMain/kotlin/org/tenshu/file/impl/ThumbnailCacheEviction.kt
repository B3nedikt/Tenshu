package org.tenshu.file.impl

/**
 * One stored thumbnail blob's footprint, as the keeper observes it.
 *
 * [lastAccessMs] is the blob's own recency: the `.read` stamp a read leaves
 * (`ThumbnailCacheSidecars.readStampPath`), falling back to the write time when nothing has read it
 * here yet, and `null` when the platform can report neither. See [selectThumbnailEvictions] for how
 * unknown recency is ordered.
 *
 */
internal data class CachedThumbnail(
    val path: String,
    /** The owning `File` row's path — the journal probe's other half. */
    val filePath: String,
    val contentId: String,
    val thumbnailClass: String,
    val propertyId: String,
    val sizeBytes: Long,
    val lastAccessMs: Long?,
)

/**
 * The thumbnail cache's ceiling — D-20's pure decision, the same shape as [selectCacheEvictions] one
 * plane over (`drive-xxlarge-thumbnail-rung.md` §4.7).
 *
 * Two pressures, whichever demands more:
 * - **the ceiling** — `FileSyncPreferences.MaxThumbnailBytes`, "keep at most [maxThumbnailBytes]
 *   bytes of large thumbnail classes here". ⚠⚠ Unlike the file cache's, `0` means *keep none*; only
 *   a **negative** value means no ceiling. These blobs are re-fetchable derivatives nobody asked for
 *   by name, so the bound ships on;
 * - **the free-disk floor** — `minFreeSpaceBytes` against [availableSpaceBytes], shared with the
 *   file cache. ⚠ `null` free space is *unknown* and skips the floor, never "none free".
 *
 * Coldest first, stopping at the first blob that brings the total under. ⚠ Unknown recency sorts
 * **last**: a platform that cannot report a time must not thereby read as maximally cold.
 *
 * ⚠⚠ **Which classes reach here is the caller's business, not this function's.** The keeper offers
 * only the backfilled rungs (`XLARGE` and the motion clip) of unpinned files: the classes a byte
 * holder can re-mint and any device can pull again. `LIST` and `LARGE` are never offered — a row
 * re-blanks without them, and at 64/48 KiB they are not the problem.
 *
 * ⚠ It answers **what to evict**, never what to record. Whether a dropped blob leaves the keeper's
 * "deliberately gone" sentinel depends on whether the relay has its bytes yet, which only the caller
 * can ask (`FileContentSyncEmitter.queuedContentBytesFor`).
 */
internal fun selectThumbnailEvictions(
    cached: List<CachedThumbnail>,
    maxThumbnailBytes: Long,
    availableSpaceBytes: Long?,
    minFreeSpaceBytes: Long,
): List<CachedThumbnail> {
    val coldestFirst = cached.sortedWith(
        compareBy<CachedThumbnail> { it.lastAccessMs ?: Long.MAX_VALUE }.thenBy { it.path },
    )

    val cacheBytes = cached.sumOf { it.sizeBytes }
    val overCeiling = if (maxThumbnailBytes >= 0L) cacheBytes - maxThumbnailBytes else 0L
    val overFloor = if (availableSpaceBytes != null && minFreeSpaceBytes > 0L) {
        minFreeSpaceBytes - availableSpaceBytes
    } else {
        0L
    }
    val mustFree = maxOf(overCeiling, overFloor)
    if (mustFree <= 0L) return emptyList()

    val evictions = mutableListOf<CachedThumbnail>()
    var freed = 0L
    for (blob in coldestFirst) {
        if (freed >= mustFree) break
        // ⚠ A blob whose bytes the relay has not taken yet is still evictable — the journal has no
        // ceiling of its own, so exempting it would leave the cache unbounded exactly when the
        // network is refusing. What changes for it is the sentinel, which the caller decides.
        evictions += blob
        freed += blob.sizeBytes
    }
    return evictions
}
