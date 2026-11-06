package org.tenshu.file.proxy

import org.tenshu.core.common.proxy.FileDatabase

/**
 * The tiny files that live **beside** a stored thumbnail blob and say something about it
 * (`drive-xxlarge-thumbnail-rung.md` §4.7). Three today, all under the blob's own
 * `/.thumbnails/<contentId>/<class>` address, all local-only and never synced:
 *
 * | suffix | written by | means |
 * |---|---|---|
 * | `.declined` | the backfill (`BackfillThumbnailClassesImplementation`) | this device tried to mint the class and the render said no |
 * | `.evicted` | the keeper's thumbnail sweep | the class was **deliberately** dropped to stay under the ceiling, and the relay has it |
 * | `.read` | `GetStoredThumbnailImplementation` | when something last read the blob here |
 *
 * ⚠⚠ **Keyed by `contentId`, like the blob.** A re-upload changes the content id, so it starts with
 * a clean slate rather than inheriting a previous content's "no" or "gone".
 *
 * ⚠ A sidecar is never a blob: [FileBytesPropertyWriter.thumbnailPath] addresses
 * `<class>` exactly, so a suffixed name cannot be mistaken for one.
 */
internal object ThumbnailCacheSidecars {

    /**
     * How stale a `.read` stamp may get before the next read rewrites it.
     *
     * ⚠⚠ A stamp per read would be a write per rendered tile. The eviction order only has to be
     * roughly right, and six hours is far finer than the ceiling it feeds — a blob read this week
     * sorts after one nothing has opened since it arrived, which is the whole question.
     */
    const val READ_STAMP_INTERVAL_MS: Long = 6L * 60 * 60 * 1000

    /**
     * How long an `.evicted` sentinel is believed before the backfill checks the relay still has the
     * class (`drive-xxlarge-thumbnail-rung.md` §4.7).
     *
     * ⚠ A week, against a pass that runs daily: the sentinel's only job is to stop a re-mint, and the
     * case it guards against — the relay losing content this device deliberately dropped — is rare
     * and not urgent. Checking sooner would spend relay round trips on a library that is fine.
     */
    const val EVICTED_REVALIDATE_INTERVAL_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** ⚠ The classes the keeper may evict: exactly the ones a byte holder re-mints and a client pulls. */
    fun isEvictable(thumbnailClass: String): Boolean =
        ThumbnailClasses.forClass(thumbnailClass)?.backfilled == true

    fun evictedPath(fileContentId: String, thumbnailClass: String): String =
        FileBytesPropertyWriter.thumbnailPath(fileContentId, thumbnailClass) + ".evicted"

    fun readStampPath(fileContentId: String, thumbnailClass: String): String =
        FileBytesPropertyWriter.thumbnailPath(fileContentId, thumbnailClass) + ".read"

    /**
     * Epoch millis, as decimal text — the same shape the `.declined` sentinel uses for its format.
     *
     * ⚠ **Stat first.** Most blobs have no stamp, and on Android a `readBytes` miss logs a warning
     * (`AndroidFileDatabaseImpl.getFile`) — thousands of them per sweep over a real library. The
     * sibling `declinedClasses` guards the same way for the same reason.
     */
    fun readStampMs(fileDatabase: FileDatabase, fileContentId: String, thumbnailClass: String): Long? =
        stampAt(fileDatabase, readStampPath(fileContentId, thumbnailClass))

    private fun stampAt(fileDatabase: FileDatabase, path: String): Long? = runCatching {
        if (fileDatabase.fileSize(path) == null) return null
        fileDatabase.readBytes(path)?.get()?.decodeToString()?.trim()?.toLongOrNull()
    }.getOrNull()

    /**
     * Records that the blob was read now, unless the stamp is younger than [READ_STAMP_INTERVAL_MS].
     *
     * ⚠⚠ Best-effort, always. This runs inside a preview read on every grid tile: a full disk, a
     * read-only store or a missing clock must cost the picture nothing. A stamp that fails to write
     * only makes the blob look colder than it is.
     */
    fun stampRead(fileDatabase: FileDatabase, fileContentId: String, thumbnailClass: String, nowMs: Long) {
        if (!isEvictable(thumbnailClass)) return
        val previous = readStampMs(fileDatabase, fileContentId, thumbnailClass)
        // ⚠ `in 0 until` and not `<`: a stamp written while the device clock was ahead would otherwise
        // never be rewritten, and that blob would sort as the warmest thing here for good.
        if (previous != null && nowMs - previous in 0 until READ_STAMP_INTERVAL_MS) return
        runCatching {
            fileDatabase.writeRawByteArray(
                filePath = readStampPath(fileContentId, thumbnailClass),
                bytes = nowMs.toString().encodeToByteArray(),
            )
        }
        // ⚠⚠ The blob is here, so any `.evicted` sentinel beside it is stale — whatever put it back.
        // This is the **only** self-heal that fires for a pulled class: inbound content is promoted by
        // `FileContentManifestApplier` with a `moveFile`, which never reaches an `ExternalPropertyWriter`.
        clearEvicted(fileDatabase, fileContentId, thumbnailClass)
    }

    /**
     * When the keeper evicted this class, or `null` when it did not (no sentinel, or an unreadable
     * one). ⚠ Stats first, like [readStampMs]: the backfill asks this for every class a file lacks.
     */
    fun evictedStampMs(fileDatabase: FileDatabase, fileContentId: String, thumbnailClass: String): Long? =
        stampAt(fileDatabase, evictedPath(fileContentId, thumbnailClass))

    /**
     * Restamps a sentinel whose class the relay still has, so the next check is another interval away.
     *
     * ⚠ Best-effort: a failed restamp only costs one more relay check next pass.
     */
    fun renewEvicted(fileDatabase: FileDatabase, fileContentId: String, thumbnailClass: String, nowMs: Long) {
        runCatching {
            fileDatabase.writeRawByteArray(
                filePath = evictedPath(fileContentId, thumbnailClass),
                bytes = nowMs.toString().encodeToByteArray(),
            )
        }
    }

    /**
     * Drops the `.evicted` sentinel for a class that is present again — a pull that landed, or a
     * mint that re-rendered it.
     *
     * ⚠⚠ Without this the blob and its sentinel coexist, and the next sweep would evict the blob and
     * find the sentinel already there: harmless once, but the backfill would keep counting a class
     * as "evicted, the relay has it" for bytes this device has since re-minted. Best-effort: a
     * failure leaves a sentinel beside a live blob, which reads as present either way.
     */
    fun clearEvicted(fileDatabase: FileDatabase, fileContentId: String, thumbnailClass: String) {
        runCatching { fileDatabase.deleteFile(evictedPath(fileContentId, thumbnailClass)) }
    }
}
