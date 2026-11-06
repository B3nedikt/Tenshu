package org.tenshu.mapregion.proxy

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.FileDatabase

/**
 * The proxy's canonical region store (D5), backed by two **device-local** (never synced)
 * mechanisms:
 *
 * - the small install **index** (id → path/sha/size/build) as a JSON blob in
 *   [SecureStorageDelegate] (`storeString` — non-secret device-local bucket), and
 * - the bundle **bytes** on the local filesystem via [FileDatabase] (the same byte store the
 *   file-content plane uses).
 *
 * The realm DB / preference-service are deliberately NOT used — those persist to the *synced*
 * realm, and installed regions are per-device (a coarse location-interest signal, R2). Bytes
 * land under [regionsDir]`/<id>.pmtiles`.
 */
class DefaultRegionStore(
    private val storageDelegate: SecureStorageDelegate,
    private val fileDatabase: FileDatabase,
    private val regionsDir: String,
    private val indexKey: String = DEFAULT_INDEX_KEY,
) : RegionStore {

    override fun installed(): List<InstalledRegionData> = readIndex()

    /**
     * Lands a region's bytes and indexes them — **atomically** (`maps-region-delivery.md` §7.1 track 3c).
     *
     * The bytes go to a temporary path and are then **renamed** into place, rather than overwritten in
     * situ. This matters most for the case that motivated it: a *staleness refresh* re-downloads a region
     * that is already resident and being served. Writing a multi-hundred-MB bundle over the live file
     * means that, for the whole write, the file on disk is neither the old bundle nor the new one — and if
     * the process dies mid-write (or the disk fills), what survives is a **truncated file the index still
     * vouches for**, with a sha it no longer has. `StreamingRegionSource` serves installed regions
     * straight from the store, so the map would then render torn tiles from a bundle that looks valid.
     * A rename is atomic, so a reader sees only the old file or the whole new one.
     *
     * The index is written *after* the rename: a crash in between leaves the new bytes on disk but the old
     * (or no) index entry — the region is simply re-downloaded, never served half-written.
     *
     * ⚠ The atomicity is [FileDatabase.moveFile]'s: `AndroidFileDatabaseImpl` (where regions actually live
     * on-device) implements it as a real `File.renameTo`, and `InMemoryFileDatabase` (desktop/iOS Control)
     * as a map-key move — neither can tear. The *interface default* is a non-atomic copy+delete fallback,
     * so a future `FileDatabase` that does not override `moveFile` would silently lose this guarantee.
     */
    override fun add(entry: RegionEntryData, buildDate: String, bytes: ByteArray): InstalledRegionData {
        val localPath = bundlePath(entry.regionId)

        fileDatabase.writeRawByteArray(stagingPath(entry.regionId), bytes)
        fileDatabase.moveFile(stagingPath(entry.regionId), localPath)

        val record = InstalledRegionData(
            regionId = entry.regionId,
            name = entry.name,
            localPath = localPath,
            byteSize = bytes.size.toLong(),
            sha256 = entry.sha256,
            buildDate = buildDate,
        )
        // Replace any prior copy of the same region (an update lands as add-over-existing).
        writeIndex(readIndex().filterNot { it.regionId == entry.regionId } + record)
        return record
    }

    /**
     * Prepares staging, **resuming** an earlier attempt's bytes when they belong to the same content
     * (§7.1 track 3e/resume). Returns the reusable byte count, or 0 after clearing.
     *
     * ⚠ The marker file is the whole safety argument. Without it, a `.part` is just "some bytes for this
     * region id" — and region bundles are immutable dated *builds*, so bytes staged before a rebuild
     * belong to different content. Appending to them would produce a file that fails the sha gate only
     * after the entire remainder had been downloaded. Recording the expected digest next to the staging
     * file makes "is this resumable?" answerable instead of assumed.
     */
    override fun beginStaging(regionId: String, expectedSha256: String): Long {
        val marker = fileDatabase.readBytesRange(markerPath(regionId), 0, MARKER_MAX_BYTES)
            ?.decodeToString()
            ?.trim()
        val staged = fileDatabase.fileSize(stagingPath(regionId)) ?: 0L
        if (marker == expectedSha256 && staged > 0L) return staged

        // Different content, no marker, or nothing staged — start clean and record what we are about to
        // fetch. The marker is written even for a fresh start, so the *next* attempt can resume this one.
        fileDatabase.deleteFile(stagingPath(regionId))
        fileDatabase.writeRawByteArray(markerPath(regionId), expectedSha256.encodeToByteArray())
        return 0L
    }

    override fun readStaged(regionId: String, offset: Long, length: Int): ByteArray? =
        fileDatabase.readBytesRange(stagingPath(regionId), offset, length)

    /**
     * Appends a chunk of the streamed bundle. Goes to the **staging** path, which nothing reads: the
     * canonical bundle (and therefore [installed] / [readRange]) is untouched until [commitStaged], so a
     * download that dies half-way leaves the previously-resident region intact and servable.
     */
    override fun writeStaged(regionId: String, offset: Long, bytes: ByteArray) {
        fileDatabase.writeBytesRange(stagingPath(regionId), offset, bytes)
    }

    /**
     * Promotes the staged bytes once they have passed the sha256 gate: an atomic rename onto the canonical
     * path, then the index. Same ordering (and the same guarantee) as [add] — see its KDoc.
     */
    override fun commitStaged(
        entry: RegionEntryData,
        buildDate: String,
        byteSize: Long,
    ): InstalledRegionData {
        val localPath = bundlePath(entry.regionId)
        fileDatabase.moveFile(stagingPath(entry.regionId), localPath)
        // The staging file is gone, so its resume marker would only ever mislead the next attempt.
        fileDatabase.deleteFile(markerPath(entry.regionId))

        val record = InstalledRegionData(
            regionId = entry.regionId,
            name = entry.name,
            localPath = localPath,
            byteSize = byteSize,
            sha256 = entry.sha256,
            buildDate = buildDate,
        )
        writeIndex(readIndex().filterNot { it.regionId == entry.regionId } + record)
        return record
    }

    /**
     * Discards the staged bytes **and** their resume marker. Called when the staged content is known to
     * be wrong (the sha gate failed), never on a plain transport failure — that case deliberately leaves
     * the bytes for the next attempt to resume.
     */
    override fun abortStaging(regionId: String) {
        fileDatabase.deleteFile(stagingPath(regionId))
        fileDatabase.deleteFile(markerPath(regionId))
    }

    private fun stagingPath(regionId: String): String = "${bundlePath(regionId)}$STAGING_SUFFIX"

    /** Records which content the staging file holds, so a resume can prove it is the same build. */
    private fun markerPath(regionId: String): String = "${stagingPath(regionId)}$MARKER_SUFFIX"

    /**
     * Evicts a region. The **index is cleared first**, then the bytes: a crash in between leaks an
     * unreferenced file (harmless — the next `add` for that region overwrites it), whereas deleting the
     * bytes first would leave an index entry vouching for a file that no longer exists, i.e. a region that
     * reports itself installed and then serves nothing.
     */
    override fun remove(regionId: String): Boolean {
        val index = readIndex()
        val target = index.firstOrNull { it.regionId == regionId } ?: return false
        writeIndex(index.filterNot { it.regionId == regionId })
        fileDatabase.deleteFile(target.localPath)
        return true
    }

    override fun readRange(regionId: String, offset: Long, length: Int): ByteArray? {
        val target = readIndex().firstOrNull { it.regionId == regionId } ?: return null
        // Positional read straight off the byte store — bounded-memory, no whole-bundle load
        // (AndroidFileDatabaseImpl overrides readBytesRange with a true RandomAccessFile.seek).
        return fileDatabase.readBytesRange(target.localPath, offset, length)
    }

    private fun bundlePath(regionId: String): String = "$regionsDir/$regionId.pmtiles"

    private fun readIndex(): List<InstalledRegionData> {
        val raw = storageDelegate.getString(indexKey) ?: return emptyList()
        return runCatching { json.decodeFromString(indexSerializer, raw) }.getOrDefault(emptyList())
    }

    private fun writeIndex(index: List<InstalledRegionData>) {
        storageDelegate.storeString(indexKey, json.encodeToString(indexSerializer, index))
    }

    private companion object {
        /** Bytes land here first, then get renamed into place — never written over a live bundle. */
        const val STAGING_SUFFIX = ".part"
        const val MARKER_SUFFIX = ".sha"
        /** A hex sha256 is 64 chars; the cap just bounds a read of a file we did not write. */
        const val MARKER_MAX_BYTES = 128
        const val DEFAULT_INDEX_KEY = "map_regions_installed"
        val json = Json { ignoreUnknownKeys = true }
        val indexSerializer = ListSerializer(InstalledRegionData.serializer())
    }
}
