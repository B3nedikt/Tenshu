package app.tenshu.ingest

import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.file.CaptureLatE7
import org.tenshu.core.file.PixelHeight
import org.tenshu.core.file.PixelWidth
import org.tenshu.core.file.CaptureLngE7
import org.tenshu.core.file.Category
import org.tenshu.core.file.ContentHash
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.Size
import org.tenshu.core.file.path

/**
 * One file, as the index knows it — enough to decide *"is this content already here?"* and to
 * render a duplicate group without a second read.
 */
data class IndexedContent(
    val path: String,
    val contentId: String,
    val contentHash: String,
    val createdAtMs: Long,
    val sizeBytes: Long,
    val isImage: Boolean,
    val isVideo: Boolean,
    /**
     * Where the photograph was taken, in E7 degrees, or `null`.
     *
     * ⚠ Carried from the projection the Places section once grouped on (the §20 whole-realm read,
     * retired in §39/§40); the Duplicates tiles do not show a place.
     */
    val latE7: Long? = null,
    val lngE7: Long? = null,
    /**
     * The **stored** pixel size, or `null` when the metadata pass never read the file.
     *
     * ⚠ Carried for the coordinates' reason: Photos' tiles pick a thumbnail class by the photo's
     * shape (`drive-synced-thumbnails.md` §16), and a Duplicates tile would otherwise guess 4:3.
     */
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * Files grouped by [File.ContentHash] — the grouping the Duplicates album shows
 * (`photos-app.md` PH-G3).
 *
 * ## ⚠⚠ An in-memory grouping, never the realm's
 *
 * This used to be loaded with **every** file in the realm, in one reply — `ContentIndex.load` for
 * the backup drain, `RealmContentIndex`'s stream for Duplicates. One reply is one Binder
 * transaction, and at ~640 bytes a file a realm past ~1,600 files got no answer at all
 * (`photos-app.md` §39/§40). Both callers now ask narrower questions in byte-bounded replies
 * ([RealmContentReads]); this class groups only the rows those reads already narrowed to the
 * duplicated hashes. ⚠ Do not add a loader back.
 *
 * ## ⚠⚠ Client-side, and it has to stay that way
 *
 * Grouping by plaintext hash happens on a device that already holds the plaintext, so it learns
 * nothing it did not know. Pushing it into the storage or relay layer would build exactly the
 * confirmation-of-file oracle `file-content-addressing.md` exists to prevent — which is why
 * `File.ContentId` is random and `File.ContentHash` never leaves the encrypted metadata (House
 * Rule #1; threat model A1). There is **no cross-user dedup here, and there never will be.**
 *
 * ## ⚠ What a snapshot is not
 *
 * It is a value taken at a moment, with no subscription. The Duplicates screen rebuilds it per
 * open. Nothing invalidates it for you.
 *
 * ## ⚠ Two absences that are deliberately not "different"
 *
 * - A file uploaded before the hash was stamped carries **no** `contentHash`. It is omitted
 *   entirely rather than filed under `""`, because an empty hash is *unknown*, and a group of
 *   "everything we could not identify" is the one grouping that would be actively wrong.
 * - A **trashed** file is excluded, like every other read path since PH-G16. The consequence is
 *   worth stating: a copy sitting in the trash does not suppress a re-import. That is the honest
 *   reading of a deletion — the alternative is a delete that silently keeps blocking the slot it
 *   was supposed to free.
 */
class ContentIndex private constructor(
    private val byHash: Map<String, List<IndexedContent>>,
) {

    /** How many distinct contents the snapshot holds. */
    val contentCount: Int get() = byHash.size

    /** How many files it holds — larger than [contentCount] exactly when duplicates exist. */
    val fileCount: Int get() = byHash.values.sumOf { it.size }

    /**
     * Whether this content is already in the realm.
     *
     * ⚠ A blank hash answers `false`: "I could not identify it" must never read as "I have it",
     * or an ingest-time skip would drop files it never actually held.
     */
    fun holds(contentHash: String): Boolean =
        contentHash.isNotEmpty() && byHash.containsKey(contentHash)

    /** Every file carrying this content, in the order the walk found them. */
    fun copiesOf(contentHash: String): List<IndexedContent> =
        if (contentHash.isEmpty()) emptyList() else byHash[contentHash].orEmpty()

    /**
     * The duplicate groups: every content held by more than one file, largest group first, and
     * within a group **oldest first** so the copy most likely to be the original leads.
     *
     * ⚠ Exact matches only. A re-encoded or edited copy hashes differently and is not a
     * duplicate here — a perceptual-hash problem, and a later inference phase's, not this one's
     * (PH-D2). The UI says so rather than letting the user infer completeness.
     */
    fun duplicateGroups(): List<List<IndexedContent>> =
        byHash.values
            .filter { it.size > 1 }
            .map { group -> group.sortedBy { it.createdAtMs } }
            .sortedWith(compareByDescending<List<IndexedContent>> { it.size }.thenBy { it.first().path })

    /** Only the groups whose files are photos or videos — the Duplicates album's subject. */
    fun mediaDuplicateGroups(): List<List<IndexedContent>> =
        duplicateGroups().filter { group -> group.all { it.isImage || it.isVideo } }

    companion object {

        /** Visible for tests and for callers that already hold the rows. */
        fun of(entries: List<IndexedContent>): ContentIndex = ContentIndex(group(entries))

        private fun group(entries: List<IndexedContent>): Map<String, List<IndexedContent>> =
            entries.filter { it.contentHash.isNotEmpty() }.groupBy { it.contentHash }

        /**
         * One projected row → one [IndexedContent], or `null` when it cannot be identified.
         *
         * ⚠ The one mapping from [RealmContentReads]' full-row projection — exposed so a test or
         * benchmark reading the same projection cannot drift from it.
         */
        fun indexed(row: Any?): IndexedContent? = row.toIndexedOrNull()

        private fun Any?.toIndexedOrNull(): IndexedContent? {
            @Suppress("UNCHECKED_CAST")
            val entity = this as? Entity<File> ?: return null
            val path = entity.path.get()
            if (path.isEmpty()) return null
            val hash = entity.get(File.ContentHash)?.get().orEmpty()
            if (hash.isEmpty()) return null
            val category = entity.get(File.Category)
            return IndexedContent(
                path = path,
                contentId = entity.get(File.ContentId)?.get().orEmpty(),
                contentHash = hash,
                createdAtMs = entity.get(File.CreatedAt)?.get() ?: 0L,
                sizeBytes = entity.get(File.Size)?.get() ?: 0L,
                isImage = category == FileCategory.Image,
                isVideo = category == FileCategory.Video,
                latE7 = entity.get(File.CaptureLatE7)?.get(),
                lngE7 = entity.get(File.CaptureLngE7)?.get(),
                pixelWidth = entity.get(File.PixelWidth)?.get()?.toInt(),
                pixelHeight = entity.get(File.PixelHeight)?.get()?.toInt(),
            )
        }
    }
}
