package org.tenshu.file.impl

import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.SyncRuleSet

/**
 * One file's local cache footprint, as the keeper's tree walk observes it.
 *
 * [lastModifiedMs] is `null` when the file is absent **or** the platform cannot
 * report it (`FileDatabase.lastModifiedMs`); see [selectCacheEvictions] for how
 * unknown recency is ordered.
 */
internal data class CachedFile(
    val path: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long?,
)

/**
 * The local-cache ceiling's pure decision (`retention-quota-model.md` Face B) —
 * the evict half of the offline keeper, and the sibling of
 * [selectKeepOfflineDownloads]. Returns the paths whose local bytes should go,
 * coldest first.
 *
 * Two independent pressures, whichever demands more:
 *
 *  - **the ceiling** — "keep at most [maxLocalBytes] bytes of *cached* content
 *    here". `maxLocalBytes <= 0` means no ceiling.
 *  - **the free-disk floor** — `availableSpaceBytes < minFreeSpaceBytes` ("always
 *    leave N free"). ⚠ `availableSpaceBytes == null` means *unknown*, and skips
 *    the floor entirely — a platform that cannot report free space must not be
 *    treated as having none, which would evict the whole cache on every sweep.
 *
 * ⚠⚠ **The ceiling measures only evictable content — pinned files neither count
 * against it nor can be spent on it** (D6 as corrected 2026-08-03). Counting them
 * makes the setting *unsatisfiable by construction* the moment pins exceed it: a
 * user who pins 50 GB under a 10 GB ceiling would have every opportunistically
 * cached byte evicted forever, and no amount of further eviction could ever bring
 * the total under. So this is a **cache** ceiling, not a total-footprint one — the
 * device holds `pinned + at most maxLocalBytes`, and the *setting's label must say
 * so*. Bounding the total is the free-disk floor's job, and it is the only one of
 * the two that can be legitimately unsatisfiable (a disk filled by other apps),
 * in which case this evicts everything it may and stops.
 *
 * **Coldest-first** by [CachedFile.lastModifiedMs] ascending. ⚠ That is a *write*
 * time — least-recently-**fetched**, not least-recently-read (see
 * `FileDatabase.lastModifiedMs` for why an access time is not portable). For a
 * write-once content cache the two coincide until something starts touching files
 * on read. Unknown recency sorts **last** (evicted only after every file with a
 * known age): an impl that cannot report times would otherwise have its entire
 * cache read as maximally cold and evicted first. Ties break by path so a sweep is
 * deterministic.
 */
internal fun selectCacheEvictions(
    ruleSet: SyncRuleSet,
    cached: List<CachedFile>,
    maxLocalBytes: Long,
    availableSpaceBytes: Long?,
    minFreeSpaceBytes: Long,
): List<String> {
    val evictable = cached
        .filter { ruleSet.effectivePolicy(it.path) != SyncPolicy.KEEP_OFFLINE }
        .sortedWith(
            compareBy<CachedFile> { it.lastModifiedMs ?: Long.MAX_VALUE }
                .thenBy { it.path },
        )

    val cacheBytes = evictable.sumOf { it.sizeBytes }
    val overCeiling = if (maxLocalBytes > 0L) cacheBytes - maxLocalBytes else 0L
    val overFloor = if (availableSpaceBytes != null && minFreeSpaceBytes > 0L) {
        minFreeSpaceBytes - availableSpaceBytes
    } else {
        0L
    }
    val mustFree = maxOf(overCeiling, overFloor)
    if (mustFree <= 0L) return emptyList()

    val chosen = mutableListOf<String>()
    var freed = 0L
    for (file in evictable) {
        if (freed >= mustFree) break
        chosen += file.path
        freed += file.sizeBytes
    }
    return chosen
}
