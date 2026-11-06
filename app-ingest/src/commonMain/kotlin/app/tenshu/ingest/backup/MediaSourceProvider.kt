package app.tenshu.ingest.backup

import kotlinx.coroutines.flow.Flow

/**
 * The app-side **source adapter** for the Synced Folders auto-backup feature
 * (`docs/proposals/drive-synced-folders.md` §4.1). Answers the two questions the
 * (Phase-1, in-process) backup engine needs about one *kind* of device data:
 * "what are the candidate source folders?" and "what's new in them?".
 *
 * It lives app-side rather than in the proxy because enumerating the gallery / a
 * directory tree and opening file descriptors are **app-process OS permissions**
 * (Android `READ_MEDIA_IMAGES`; a SAF/file grant) that cannot move to Control
 * (§3.3). ⚠ App-side is now `:app-ingest`, not `:drive-shared` — PH-4b moved the
 * whole engine out of Drive so that more than one app can *reach* it
 * (`photos-app.md` §19.2), which is not the same as more than one app running it.
 *
 * Platform variance is via Koin-wired actuals (`AndroidMediaSourceProvider` here /
 * Drive's `DesktopMediaSourceProvider`), **not** `expect`/`actual`, mirroring
 * Drive's `FilesRepositoryPlatformDelegate` / `NetworkStateProvider` (§9).
 *
 * ⚠⚠ **Drive still owns the drain, and Photos does not have one.** The only
 * production `SyncedFolderController` constructions are Drive's
 * `FilesRepositoryModule` and iOS's `DriveBackupSurface`; Photos' Android module
 * binds this interface and `BackupPreferences` and nothing else. So Photos
 * configures **selection rows that Drive's drain consumes** — see
 * `IngestSourceRegistry`, which says the same from the reader's side. Deciding
 * drain ownership from this file alone gets it backwards.
 *
 * Phase 1 ships only the **gallery** source. The document-tree (SAF) source is a
 * second adapter behind the same shape (proposal Phase 3); the engine doesn't
 * care which kind it is driving.
 */
interface MediaSourceProvider {

    /**
     * Settings UI: enumerate the candidate source folders ("buckets").
     *
     * - **Android**: the device's `MediaStore` image (and, when [includeVideo],
     *   video) buckets — `DCIM/Camera`, `Screenshots`, `WhatsApp Images`, …
     * - **Desktop**: the immediate media folders under this provider's configured
     *   roots (default `~/Pictures`). Desktop has no OS gallery, so this is the
     *   "folders that exist", not an OS-curated set (§4.1).
     *
     * [includeVideo] folds video buckets/items into the result when the user has
     * opted into video backup (proposal open-Q4 — images default-on, video an
     * explicit cost choice).
     */
    suspend fun listBuckets(includeVideo: Boolean = false): List<MediaBucket>

    /**
     * Trigger (app alive — Phase 1, Layer 2): emits once whenever any of the
     * [watched] buckets gains an item, so the controller can re-drain. The engine
     * treats every emission as "something may be new, go diff against the cursor";
     * the emission carries no payload.
     *
     * Android = a `ContentObserver` on the `MediaStore` collection URIs; Desktop =
     * a `java.nio.file.WatchService` on the watched directories.
     */
    fun observeChanges(watched: Set<String>): Flow<Unit>

    /**
     * The loop's core read: items in [bucketId] strictly newer than [sinceCursor]
     * (the per-bucket watermark, §3.5), **oldest-first** so the cursor advances
     * monotonically as each item uploads. `null` [sinceCursor] = first run for
     * this bucket (everything currently in it).
     *
     * [includeVideo] mirrors [listBuckets]; when `false` video items are omitted
     * even from a video bucket.
     */
    suspend fun listNewItems(
        bucketId: String,
        sinceCursor: String?,
        includeVideo: Boolean = false,
    ): List<MediaItem>
}

/** What kind of media an item is. Drives the images-default-on / video-opt-in
 *  filtering (proposal §3.7 / open-Q4). */
enum class MediaType { IMAGE, VIDEO }

/**
 * One candidate source folder for the settings list (§4.1). [id] is an opaque,
 * device-local identity the engine round-trips unchanged — a `MediaStore`
 * `BUCKET_ID` on Android, an absolute directory path on Desktop (§3.1).
 */
data class MediaBucket(
    val id: String,
    val displayName: String,
    val itemCount: Int,
    /** Best-effort preview for the settings row; `null` when none is resolvable. */
    val sampleThumbnailUri: String? = null,
)

/**
 * One backup candidate. [uri] is the per-platform file handle the existing
 * per-file upload path opens (§3.3); [addedAtMillis] / [cursor] order items and
 * advance the per-bucket watermark. [cursor] is the opaque, monotonic
 * watermark value for *this* item (Android `_ID`/`date_added`, Desktop mtime) —
 * stored verbatim and compared lexically/numerically by the provider only.
 */
data class MediaItem(
    val uri: String,
    val fileName: String,
    val addedAtMillis: Long,
    val cursor: String,
    val type: MediaType = MediaType.IMAGE,
    /**
     * How many bytes this item will transfer, or `null` when this provider cannot say
     * **cheaply** — the run's byte figures (`drive-upload-progress.md` §14.4).
     *
     * ⚠ `null` is *unknown*, not zero, and the two must stay distinguishable: a run that
     * cannot size one item cannot state its remaining bytes at all, whereas a genuinely
     * empty file is known to contribute nothing. Collapsing them would let one unsizable
     * item silently understate the total by however much it holds.
     *
     * ⚠ Cheaply is the whole bar. A size worth having is one already in the enumeration the
     * drain does anyway — a `MediaStore` column, a `File.length()`. Opening every candidate
     * to `stat` it would put a per-file OS round-trip in front of a backup that has not
     * started, to improve a byte figure; that trade is not worth making, and a provider in
     * that position should answer `null` and let the banner fall back.
     */
    val sizeBytes: Long? = null,
)
