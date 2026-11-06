package app.tenshu.ingest.backup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.tenshu.core.ingest.IngestSource

/**
 * One ingest *source* the [SyncedFolderController] drives
 * (`docs/proposals/drive-synced-folders-phase2.md` §3.2 / §7 — "separate adapters,
 * same engine"). It bundles a [MediaSourceProvider] adapter with the per-source
 * settings the engine reads each drain: which buckets are selected and whether
 * video is included. Phase 1/2 ship only the [galleryBackupSource]; Phase 3b adds
 * the SAF document-tree source behind the same shape, with no engine change.
 *
 * The **global** gate inputs (master enabled, Wi-Fi-only, charging-only) are *not*
 * here — they live in [BackupSettings] and apply to every source at once. Only the
 * per-source dimensions ([selectedBuckets] / [includeVideo]) vary by source.
 *
 * [sourceId] is the stable registry id (§3.1); it namespaces this source's
 * per-bucket cursors in [BackupPreferences] so two sources can't collide on a
 * shared bucket key.
 */
class BackupSource(
    val sourceId: String,
    val displayName: String,
    val sourceKind: String,
    val provider: MediaSourceProvider,
    /**
     * The user's selected buckets for this source (gallery: `BackupBuckets`) — the
     * **reactive** view, for registration and change-triggering.
     *
     * ⚠ Not for the drain: these flows are screen reads that seed a default before the
     * stored row arrives, so `first()` on them returns the placeholder rather than the
     * selection (see [BackupPreferences]). The drain uses [readSelectedBuckets].
     */
    val selectedBuckets: Flow<Set<String>>,
    /** Whether video items are included (gallery: `BackupVideos`; doc-tree: always false). */
    val includeVideo: Flow<Boolean>,
    /** One-shot snapshot of [selectedBuckets] — what the drain must enumerate. */
    val readSelectedBuckets: suspend () -> Set<String>,
    /** One-shot snapshot of [includeVideo] — what the drain must enumerate. */
    val readIncludeVideo: suspend () -> Boolean,
    /**
     * Whether a bucket appearing in this source is worth *asking* the user about
     * (`NewFolderDetector`). True for the gallery, where the OS enumerates folders
     * the user never told us about. False for the SAF document-tree source: its
     * "buckets" are persisted `OPEN_DOCUMENT_TREE` grants, which can only come into
     * existence by the user picking one — announcing those would be telling the user
     * about a folder they just added themselves.
     */
    val announcesNewBuckets: Boolean = false,
)

/** Stable registry id for Drive's gallery ingest source (§3.1). */
const val GALLERY_SOURCE_ID = "drive.gallery"

/** Human label shown in Control's management surface (§8). */
const val GALLERY_DISPLAY_NAME = "Photos & Videos"

/**
 * Build the gallery [BackupSource] from the gallery [MediaSourceProvider] and the
 * device's [BackupPreferences]. The single home for the gallery wiring, shared by
 * production DI and the controller tests.
 */
fun galleryBackupSource(
    provider: MediaSourceProvider,
    preferences: BackupPreferences,
): BackupSource = BackupSource(
    sourceId = GALLERY_SOURCE_ID,
    displayName = GALLERY_DISPLAY_NAME,
    sourceKind = IngestSource.KIND_GALLERY,
    provider = provider,
    selectedBuckets = preferences.observeSelectedBuckets().map { it.toSet() },
    includeVideo = preferences.observeVideos(),
    readSelectedBuckets = { preferences.readSelectedBuckets().toSet() },
    readIncludeVideo = { preferences.readVideos() },
    announcesNewBuckets = true,
)

/** Stable registry id for Drive's SAF document-tree ingest source (Phase 3b). */
const val DOCUMENT_TREE_SOURCE_ID = "drive.document-tree"

/** Human label shown in Control's management surface (§8). */
const val DOCUMENT_TREE_DISPLAY_NAME = "Folders"

/**
 * Build the document-tree [BackupSource] (Phase 3b) from the Android SAF
 * [MediaSourceProvider] adapter and the device's [BackupPreferences]. The selected
 * "buckets" are the persisted `OPEN_DOCUMENT_TREE` URIs; video filtering never
 * applies (a document tree backs up every file, [includeVideo] is always `false`).
 * Android-only — desktop folder backup is Control-hosted (§4.2).
 */
fun documentTreeBackupSource(
    provider: MediaSourceProvider,
    preferences: BackupPreferences,
): BackupSource = BackupSource(
    sourceId = DOCUMENT_TREE_SOURCE_ID,
    displayName = DOCUMENT_TREE_DISPLAY_NAME,
    sourceKind = IngestSource.KIND_DOCUMENT_TREE,
    provider = provider,
    selectedBuckets = preferences.observeDocumentTreeUris().map { it.toSet() },
    includeVideo = flowOf(false),
    readSelectedBuckets = { preferences.readDocumentTreeUris().toSet() },
    readIncludeVideo = { false },
)

/**
 * Best-effort friendly name for a SAF document-tree URI, for the settings list +
 * the `/Backup/<device>/<name>` destination. A tree URI looks like
 * `content://com.android.externalstorage.documents/tree/primary%3ADocuments`; the
 * human part is the URL-decoded segment after the last `:` (`Documents`), falling
 * back to the last `/` segment, then the raw string. Pure + desktop-unit-tested so
 * the SAF-specific parsing isn't trapped behind the Android-only provider.
 */
fun documentTreeDisplayName(uri: String): String {
    val lastSegment = uri.trimEnd('/').substringAfterLast('/')
    val decoded = lastSegment
        .replace("%3A", ":").replace("%3a", ":")
        .replace("%2F", "/").replace("%2f", "/")
    val afterColon = decoded.substringAfterLast(':')
    val name = afterColon.substringAfterLast('/').trim()
    return name.ifEmpty { uri }
}
