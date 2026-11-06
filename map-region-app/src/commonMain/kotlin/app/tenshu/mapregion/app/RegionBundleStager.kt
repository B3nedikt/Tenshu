package app.tenshu.mapregion.app

/**
 * The app-side landing spot for a region bundle's bytes. The proxy owns the canonical store
 * (in Control's sandbox); the bytes only reach the map renderer once staged into a file the app
 * process can hand to MapLibre as `pmtiles://file://…`. This seam is the app-local file store
 * that draining fills — a platform concern (Android `filesDir`, desktop cache dir), injected so
 * [RegionTerminus] stays Compose-/filesystem-free and unit-testable.
 *
 * Writes go to a temp path and are promoted atomically ([commit]) so a half-drained bundle never
 * surfaces as a "complete" file — [isStaged] gates the drain on both existence AND exact size.
 */
interface RegionBundleStager {

    /** Absolute path where region [regionId]'s bundle is (or will be) staged. */
    fun stagedPath(regionId: String): String

    /** True iff a *complete* [expectedSize]-byte bundle already sits at [stagedPath]. */
    fun isStaged(regionId: String, expectedSize: Long): Boolean

    /**
     * Positional write into the staging temp for [regionId]; a write at `offset == 0` starts a
     * fresh temp (truncating any prior partial).
     */
    fun writeChunk(regionId: String, offset: Long, bytes: ByteArray)

    /** Atomically promotes the staging temp onto [stagedPath]. */
    fun commit(regionId: String)

    /** Drops any partial staging temp for [regionId] (a drain that failed part-way). */
    fun discard(regionId: String)
}

/** Window size for a single [ReadRegionRange][org.tenshu.core.mapregion.ReadRegionRange] read. */
internal const val RegionStageWindowBytes: Int = 256 * 1024

/**
 * Drains a whole region bundle a bounded window at a time from [read] into [stager], then commits
 * it — the app-side of the render-hop byte transfer (`maps-region-delivery.md` §7 render slice).
 * Returns the absolute staged path on success, or `null` if the bundle isn't fully available
 * (a read failure / short stream); a re-drain is idempotent because [isStaged] short-circuits an
 * already-complete bundle.
 *
 * [read] is the `readRegionRange(offset, length)` op (returns the covering bytes, an empty array
 * at/after EOF, or `null` on failure).
 */
internal suspend fun stageRegionBundle(
    stager: RegionBundleStager,
    regionId: String,
    byteSize: Long,
    window: Int = RegionStageWindowBytes,
    read: suspend (offset: Long, length: Int) -> ByteArray?,
): String? {
    if (byteSize <= 0L) return null
    if (stager.isStaged(regionId, byteSize)) return stager.stagedPath(regionId)

    var offset = 0L
    while (offset < byteSize) {
        val want = minOf(window.toLong(), byteSize - offset).toInt()
        val chunk = read(offset, want)
        if (chunk == null) {
            stager.discard(regionId)
            return null
        }
        if (chunk.isEmpty()) break // premature EOF — bundle shorter than advertised
        stager.writeChunk(regionId, offset, chunk)
        offset += chunk.size
    }

    if (offset < byteSize) {
        stager.discard(regionId)
        return null
    }
    stager.commit(regionId)
    return stager.stagedPath(regionId)
}
