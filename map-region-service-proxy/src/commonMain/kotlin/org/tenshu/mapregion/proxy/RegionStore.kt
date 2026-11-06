package org.tenshu.mapregion.proxy

/**
 * The proxy's canonical region store (D5), as the operation layer sees it. The production
 * implementation (slice 2b) keeps the small install index in `SecureStorageDelegate` (device-
 * local, **not** the synced realm DB) and the bundle bytes on the local filesystem, promoted
 * atomically after a sha256 check.
 *
 * Slice 2a shipped the read/evict surface ([installed]/[remove]); slice 2b adds [add] (the
 * download-landing sink).
 */
interface RegionStore {

    /** Every region bundle currently present in the store. */
    fun installed(): List<InstalledRegionData>

    /**
     * Lands a downloaded, sha256-verified bundle: writes [bytes] to the canonical store and
     * records the index entry (keyed by `entry.regionId`, replacing any prior copy). [buildDate]
     * is the catalog-level build stamp the bundle came from. Returns the resulting index entry.
     *
     * ⚠ Takes the **whole bundle in memory**. Fine for a small region or a test fixture; a real region
     * is hundreds of MB, so the downloader uses the staged API below instead ([writeStaged] →
     * [commitStaged]) and never materialises the bundle at all (§7.1 track 3e).
     */
    fun add(entry: RegionEntryData, buildDate: String, bytes: ByteArray): InstalledRegionData

    /**
     * Appends [bytes] at [offset] of [regionId]'s **staging** file — the bounded-memory landing path for
     * a streamed download (§7.1 track 3e). The staging file is invisible to [installed] / [readRange]:
     * until [commitStaged] runs, the store still serves the *old* copy (or nothing), so a download that
     * dies half-way can never be served. Call [beginStaging] first.
     */
    fun writeStaged(regionId: String, offset: Long, bytes: ByteArray)

    /**
     * Prepares [regionId]'s staging file for a download of the content whose digest is
     * [expectedSha256], and returns **how many already-staged bytes are reusable** — `0` to start clean.
     *
     * ⚠⚠ The digest argument is what makes resuming safe. A `.part` left by an earlier attempt is only
     * reusable if it belongs to the *same content*: the catalog publishes immutable dated builds, so a
     * rebuild between attempts would otherwise have the new build's bytes appended onto the old build's
     * and fail the sha gate after downloading the whole remainder. The store records the digest alongside
     * the staging file and discards bytes staged for anything else.
     */
    fun beginStaging(regionId: String, expectedSha256: String): Long

    /**
     * Reads back staged bytes, so a resumed download can rehydrate its running sha256 over what is
     * already on disk. Returns null when there is no staging file.
     *
     * ⚠ There is no way around re-reading: the digest is streaming state that does not survive the
     * process, and the integrity gate is over the *whole* bundle. It is a local disk read, not a
     * re-download, which is the entire point.
     */
    fun readStaged(regionId: String, offset: Long, length: Int): ByteArray?

    /**
     * Promotes [regionId]'s staging file to the canonical bundle — **atomically**, by rename — and
     * records the index entry. Only ever called once the streamed bytes have passed the sha256 gate, so
     * a reader sees either the old bundle or the whole verified new one, never a torn mix.
     */
    fun commitStaged(entry: RegionEntryData, buildDate: String, byteSize: Long): InstalledRegionData

    /** Removes a failed download's staging file. Safe to call when there is none. */
    fun abortStaging(regionId: String)

    /** Evicts a region (index entry + bytes). Returns whether it was present. */
    fun remove(regionId: String): Boolean

    /**
     * Reads up to [length] bytes at [offset] from the installed bundle [regionId] — the bounded
     * byte source behind the [org.tenshu.core.mapregion.ReadRegionRange] op (the app terminus
     * drains a whole bundle a window at a time). Returns the (possibly shorter, at the final
     * window) slice, an empty array at/after EOF, or `null` if the region is not installed.
     */
    fun readRange(regionId: String, offset: Long, length: Int): ByteArray?
}
