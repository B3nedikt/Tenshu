package app.tenshu.drive.data

import org.tenshu.sync.SyncHealthStatus
import app.tenshu.ingest.backup.BackupDataPlane
import app.tenshu.ingest.backup.BackupUploadOutcome
import app.tenshu.ingest.backup.StorageStatusModel

import app.tenshu.liststate.ListLoadState
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf

/**
 * ⚠ Extends [BackupDataPlane] rather than declaring the drain's five methods itself: the
 * drain lives in `:app-ingest` and must not see the other forty. Everything the drain needs — and
 * its KDoc — is on that interface; `TenshuFilesRepository` implements both through this one.
 */
interface FilesRepository : BackupDataPlane {

    suspend fun uploadFile(
        uriString: String,
        parentPath: String = FileModel.Root.path,
        fileName: String
    ): RepositoryResult<Unit>

    suspend fun getFiles(folderPath: String = FileModel.Root.path): Flow<List<FileModel>>

    /**
     * The [limit] most-recently-modified files across **all** folders, newest
     * first — the HomeScreen "recent files" surface
     * (docs/proposals/file-service-history.md §5.1). Recursively traverses the
     * whole drive (`root.files.traverse()`), sorts by the `lastModifiedAt` value
     * property, and takes the top [limit].
     *
     * Cost note: `traverse()` is a full-tree walk per emit — fine for normal
     * drives; a maintained flat collection / the modification log is the
     * optimisation if this becomes hot.
     */
    fun recentFiles(limit: Int = 20): Flow<ListLoadState<FileModel>>

    /**
     * Files and folders across the **whole drive** whose name contains [query]
     * (case-insensitive substring) — the search-bar surface. A blank [query]
     * emits an empty list. Reactive: re-emits as the drive changes while a
     * query is active.
     *
     * Name-only semantics: matches on the file's *name* (last path segment,
     * the derived `File.Name` property), not the full path, so a hit on an
     * ancestor folder's name alone doesn't surface its unrelated descendants.
     * The `name contains query` predicate resolves per row on the proxy, so
     * only matching rows cross IPC, and `select`'s relations resolve for matches
     * only. It is an O(drive) proxy-side linear scan: `contains` cannot qualify
     * for [org.tenshu.core.common.proxy.trySearchByKey] — that fast path needs
     * `search` + `equalTo` + the *key* field against a raw array reference, and
     * this is `filter` + `contains` on the derived `File.Name` over a
     * materialised list. Substring matching is unindexable by a content-address
     * hash regardless, so this is inherent rather than an oversight.
     *
     * ⚠ **The scan is not the expensive part — the predicate is ~1% of it.**
     * `RecursiveListWalk` loses the projection the top-level read established:
     * it calls `getData(dataId, asEntity = true)` and `resolveList` with no
     * projection, so `DbEntryMapper` walks `dbEntry.properties` eagerly and
     * **every node in the drive materialises all stored value properties —
     * including `File.Thumbnail` blobs — matched or not**, on top of a second
     * deserialisation of a row the parent array read already produced. Ballpark
     * 5–20 µs/node against ~0.1 µs for the match: ~5–20 ms at 1k files, ~50–200 ms
     * at 10k, re-run on every drive mutation while a query is active.
     *
     * ⇒ The first optimisation is **threading the projection through
     * `RecursiveListWalk`**, not building an index. A maintained flat name index
     * only pays above ~10k–50k files — and the resident-set heap ceiling
     * (`docs/proposals/bounded-store-cache.md`) bites before the CPU one does.
     *
     * Default no-op (empty) so fakes need not implement it;
     * [TenshuFilesRepository] provides the real signal.
     */
    fun searchFiles(query: String): Flow<ListLoadState<FileModel>> =
        flowOf(ListLoadState.Loaded(emptyList()))

    /**
     * Paged variant of [getFiles]. Each page is a windowed `drop(n).take(m)`
     * over the folder's children, so only the visible slice is materialised on
     * the proxy — and the per-row `File.Category` resolves for that
     * slice only, instead of for the whole folder up-front.
     *
     * [getFiles] stays for callers that want the entire listing in one emit
     * (e.g. the home-screen summary); the listing screen consumes this.
     *
     * **Ordered by [sort]** — one chain over the folder (folders first, then the key, then the
     * name) applied per page ahead of the window, so a page is a slice of the whole folder's
     * order and costs one bounded thin pass (`drive-sorted-folder-paging.md` §6).
     *
     * ⚠⚠ This is the **only** listing path: there is no natural (store) order listing
     * (`drive-sorted-folder-paging.md` D5) — an unsorted listing is a store order the user never
     * chose, and the sorted path is flat in folder size. Never sort a whole folder as a single
     * page instead: that costs seconds on a 600-file folder. [FileSort.Default] (Name ascending,
     * D6) is what a caller with no opinion gets.
     */
    fun getFilesPaged(
        folderPath: String = FileModel.Root.path,
        sort: FileSort = FileSort.Default,
    ): Flow<PagingData<FileModel>>

    /**
     * Get the full bytes of a file at [path]. May trigger an on-demand fetch
     * (the file's `data` is `Syncable(OnDemand)` — the proxy lazily resolves
     * bytes from the relay's content store when they are not yet present on
     * the proxy's local disk) or simply return what's already there if a
     * prior request already populated the local copy. Caller can't tell the
     * difference; that's the point.
     *
     * "Remote" here is from the Drive client's perspective — the file *lives*
     * on Control's side, so any fetch is across the IPC boundary regardless
     * of whether Control then has to round-trip to the relay or not.
     *
     * This is the *only* path the listing-screen's tap-to-view flow uses to
     * obtain bytes — the listing's [getFiles] projection deliberately omits
     * `File.Data` to keep the listing emit cheap (the eager
     * materialiser running per-entity over 20+ multi-MB photos blows through
     * Android's Binder watchdog timeout).
     */
    suspend fun getRemoteFile(path: String): RepositoryResult<ByteArray>

    /**
     * Open a seekable content stream for the file at [path] (D2b streaming,
     * `docs/proposals/drive-streaming-preview.md` §3.5) — the bounded-memory counterpart to
     * [getRemoteFile]'s whole-file read, for large media (video). The returned
     * [StreamingContentHandle] reads byte ranges on demand (only the touched chunks decrypt) and
     * must be [StreamingContentHandle.close]d when playback ends. An `Error` if the file isn't
     * streamable yet (unsynced / locked).
     */
    suspend fun openStreamingContent(path: String): RepositoryResult<StreamingContentHandle>

    /**
     * Get the [width]×[height] preview thumbnail bytes for the file at
     * [path] — the small server-rendered thumbnail, not the full-resolution
     * source bytes. Returns `null` when the proxy has no preview to give
     * (folder, unsupported type, transient backend failure).
     *
     * Cheap on warm cache: the proxy's preview cache (`PreviewCache`) serves
     * pre-rendered bytes by `sha256(sourceBytes) || "WxH"` without
     * re-decoding the source. First call for an uncached entry pays the
     * full decode + rescale (see also: tech-debt entry on the proxy
     * preview-gen path).
     *
     * Designed to be called *per visible item* by `HorizontalFileItem` —
     * the listing emit does not bundle previews. The listing renders from metadata only and previews stream
     * in as rows scroll into view.
     */
    suspend fun getPreview(path: String, width: Int, height: Int): ByteArray?

    /**
     * Metadata for the "Details & Activity" screen — size / created /
     * last-modified for the file or folder at [path]
     * (docs/proposals/drive-file-details-screen.md §4), as a reactive stream.
     * Emits `null` when no such entry exists. The activity history is
     * [fileActivity].
     */
    fun fileDetails(path: String): Flow<FileDetailsModel?>

    /**
     * The file's modification history — the FileDetailsScreen "Activity" section
     * (docs/proposals/drive-file-details-screen.md §3), newest first. A reactive
     * stream over the realm-wide modification log filtered to [path]; empty when
     * there's no recorded history (or no authoring device id was wired).
     */
    fun fileActivity(path: String): Flow<List<FileActivity>>

    suspend fun createFolder(path: String): RepositoryResult<Unit>

    suspend fun uploadFolder(
        uriString: String,
        folderPath: String,
        onProgress: (UploadProgress) -> Unit,
        onConflict: suspend (FileConflict) -> ConflictResolution,
        // Called once, after enumeration, iff the folder contains online-only
        // (cloud placeholder) files — so the user can decide before the
        // download-then-reupload work starts (drive-folder-upload.md §11.5).
        // Defaults to Include.
        onCloudPreflight: suspend (CloudPreflight) -> CloudPreflightDecision = { CloudPreflightDecision.Include },
    ): RepositoryResult<FolderUploadSummary>

    /**
     * Headless single-file upload for Synced-Folders auto-backup
     * (`docs/proposals/drive-synced-folders.md` §3.3/§3.5). Uploads the file at
     * [uriString] as [fileName] into [parentPath] through the **existing** per-file
     * `uploadPlatformFileCancellable` data plane (N invocations of the shipped op
     * — no new upload/sync surface, §6). The caller ensures the destination chain
     * first via [ensureBackupDestination].
     *
     * No user is present, so a destination collision (`AlreadyExistsError`) is the
     * §3.5 dedup backstop: it resolves **silently to [BackupUploadOutcome.Skipped]**,
     * never the folder-upload conflict dialog.
     */
    suspend fun deleteFile(path: String): RepositoryResult<Unit>

    /**
     * Make the file at [path] available offline by forcing its bytes onto the
     * local (Control-side) disk — the "Make available offline" half of Drive's
     * offline toggle (`docs/proposals/drive-offline-online.md` §2). Reuses the
     * same OnDemand-materialising chain as [getRemoteFile]; the bytes are
     * fetched + persisted as a side effect and discarded here.
     */
    suspend fun downloadFile(path: String): RepositoryResult<Unit>

    /**
     * Drop the local bytes of the file at [path] (the `evictFileBytes` op) — the
     * "Remove download" / make-online-only half of the offline toggle. The synced
     * `File` entity and its metadata stay put; a later open re-fetches OnDemand.
     */
    suspend fun evictFile(path: String): RepositoryResult<Unit>

    /**
     * All **leaf file** paths under [folderPath] (recursively; folders
     * themselves excluded). Backs the folder-level offline toggle's eager
     * evict/download of a folder's *current* contents
     * (`docs/proposals/drive-offline-online.md` §2 / §4.10). Walks [getFiles]
     * depth-first off its first emission — a one-shot snapshot, not a live
     * subscription. Cost is one listing op per folder level: fine for a
     * user-initiated action, not for a hot path.
     */
    suspend fun listFilesRecursively(folderPath: String = FileModel.Root.path): List<String> {
        val result = mutableListOf<String>()
        for (child in getFiles(folderPath).firstOrNull().orEmpty()) {
            when (child) {
                is FileModel.Folder -> result += listFilesRecursively(child.path)
                is FileModel.File -> result += child.path
            }
        }
        return result
    }

    /**
     * True byte-**presence** probe (`docs/proposals/drive-offline-online.md` §3):
     * are the bytes of the file at [path] on this
     * device's local disk right now? Distinct from the *intent* axis
     * ([OfflinePreferences] — whether the file is pinned): a file reads `true`
     * here whether it was pinned-and-downloaded or merely opened once
     * (lazy-on-open), and `false` for an online-only or pinned-but-not-yet-
     * fetched file.
     *
     * Backed by the O(1) `hasLocalData` op (`fileSize`, no payload read). Call
     * it **lazily and bounded** per visible row — never eagerly across a listing.
     * Returns `false` on any failure (treat the file as not-yet-present).
     */
    suspend fun hasLocalData(path: String): Boolean

    /**
     * Rename the file or folder at [path] to [newName], keeping its parent. Rides the same
     * `MoveFile` op as [moveFile] — **a rename changes the path**, because the path *is* the
     * file's identity (`file-content-addressing.md` §3.3.1: there is no separate `File.fileId`).
     *
     * Returns the resulting **new path** on success, so a caller holding the old one can
     * re-address itself rather than keep pointing at an entry that no longer exists. ⚠ The path
     * is derivable from the arguments (a rename never dedupes, unlike an upload) — the payload
     * is a convenience, and the *outcome* is the load-bearing half of this return type.
     * Surfaces [RepositoryError.AlreadyExists] when the parent already holds an entry of that
     * name — in which case the file keeps its old path and **nothing was written**, which every
     * caller must distinguish from success.
     */
    suspend fun renameFile(path: String, newName: String): RepositoryResult<String>

    /**
     * Move the file or folder at [sourcePath] into [destinationFolderPath],
     * keeping its name. Like [renameFile] this rides on the underlying
     * `MoveFile` op (which handles folders recursively and emits a
     * `MovedFileModification`); the only difference is the new parent rather
     * than a new name. [destinationFolderPath] is a folder path (`"/"` for the
     * root). Surfaces [RepositoryError.AlreadyExists] when the destination
     * already holds an entry of that name.
     */
    suspend fun moveFile(sourcePath: String, destinationFolderPath: String): RepositoryResult<Unit>

    fun getFileName(uriString: String): String?

    /**
     * Mode-A: mint a fresh anonymous share link for the file at [path]
     * (`docs/file-sharing.md`). Returns the relay-assigned
     * `shareId` + the per-share `K_share` (as base64url URL-fragment) the
     * caller turns into the copy-able URL —
     * `https://tenshu.app/s/{shareId}#k={urlFragment}` (host = [ShareLinkUrl.DEFAULT_HOST]).
     *
     * [expiresAtMillis] / [maxOpens] use the `0L` / `0` sentinel for "no
     * expiry" / "no cap" (the schema DSL has no nullable scalars).
     */
    suspend fun shareLink(
        path: String,
        expiresAtMillis: Long = 0L,
        maxOpens: Int = 0,
    ): RepositoryResult<ShareLinkPayload>

    /**
     * Mode-A: invalidate a previously-minted share link by its `shareId`. The
     * local [org.tenshu.core.file.share.Share] entity's `revoked` flag flips
     * to `true` and Eager-syncs to every realm device; the relay-side `DELETE
     * /share/{shareId}` is best-effort (`RevokeShareLinkImplementation`).
     */
    suspend fun revokeShareLink(shareId: String): RepositoryResult<Unit>

    /**
     * The realm-wide *My shares* list
     * (`docs/file-sharing.md`) as a reactive stream over
     * `FileService.shares`. Filters revoked entries out so the UI shows the
     * **active** shares — the SharedScreen's *My shares* tab.
     */
    fun shares(): Flow<ListLoadState<ShareModel>>

    /**
     * Reactive sync-health signal (`docs/proposals/drive-connectivity-service.md`)
     * the status banner renders its transient states from — relay reachability +
     * whether a sync is in flight. Derived Control-side and pushed over IPC (Drive
     * can't observe connectivity or the proxy's backlog itself). `null` until the
     * proxy has derived a status.
     *
     * Defaults to a constant "unknown" (`null`) so fakes / non-syncing
     * repositories need not implement it — the banner simply shows none of the
     * health-driven states. [TenshuFilesRepository] provides the real signal.
     */
    fun syncHealth(): Flow<SyncHealthStatusModel?> = flowOf(null)

    /**
     * Whether Control is pulling data in — Drive's half of the "Syncing…" indicator
     * (`suite-sync-status.md` SS-2). Uploads stay in Drive's banner; this is the pull only. Defaults
     * to never, so fakes and the mock need not implement it.
     */
    fun catchingUp(): Flow<Boolean> = flowOf(false)

    /**
     * Reactive **schema pull fence** signal (`core-schema-evolution.md` §7.3):
     * whether this device's declared per-service schema versions are behind the realm's
     * recorded epoch, in which case the proxy has stopped pulling because inbound blocks
     * are shapes its positional decoders would garble. Locally derived by Control and
     * pushed over IPC, exactly like [syncHealth]; `null` until the proxy has evaluated.
     *
     * Default constant "unknown" (`null`) so fakes / non-syncing repositories need not
     * implement it. [TenshuFilesRepository] provides the real signal.
     */
    fun schemaFence(): Flow<SchemaFenceModel?> = flowOf(null)

    /**
     * Reactive **deferred-content** signal (`sync-metered-plane-split.md` §8): how many files the
     * user's `WifiOnly` policy is holding back from upload on a metered network. Locally derived
     * by Control and pushed over IPC, exactly like [syncHealth]; `null` until the proxy's first
     * tick.
     *
     * This — not [SyncHealthStatusModel.pendingBlockCount] — is what the *"Waiting for Wi-Fi"*
     * banner counts. That field is the realm-wide metadata queue (every app's writes, not files)
     * and is not metered-gated at all.
     *
     * Default constant "unknown" (`null`) so fakes / non-syncing repositories need not implement
     * it. [TenshuFilesRepository] provides the real signal.
     */
    fun contentTransfer(): Flow<ContentTransferModel?> = flowOf(null)

    /**
     * Whether Drive can currently reach Control
     * (`docs/proposals/core-client-resubscription.md` §4.3).
     *
     * ⚠ **Client-local, unlike every other signal here.** The rest are rows Drive *reads from the
     * proxy*, so while Control is unreachable they are all absent — and the banner, which treats
     * absent as "not derived yet", would show nothing at all over an empty file list, which reads
     * as "you have no files" rather than "I cannot ask".
     *
     * Default constant `true` so fakes need not implement it, and because it is the honest answer
     * wherever client and proxy share a process (there is no channel to lose).
     */
    fun proxyConnected(): Flow<Boolean> = flowOf(true)

    /**
     * The realm's recent change-lost notices (`content-plane-cr2-b4-spec.md` §2.4), newest
     * first. Defaulted so fakes and the iOS surface need nothing; [TenshuFilesRepository] reads them.
     */
    fun changesLost(): Flow<List<ChangeLostModel>> = flowOf(emptyList())

    /**
     * User-initiated "Sync now over cellular" override (`sync-metered-deferred-status.md`
     * §2.3): drain the deferred backlog once past the `WifiOnly` policy. Fire-and-forget —
     * signals the proxy's one-shot override; the [syncHealth] banner clears as the backlog
     * actually drains. Default no-op so fakes / non-syncing repositories need not implement it.
     */
    suspend fun syncNow() {}

    /**
     * A *Back up now* the user confirmed over mobile data is the same one-shot as [syncNow]: the
     * proxy's content plane is where Wi-Fi-only holds the bytes back.
     */
    override suspend fun uploadOverMeteredOnce() = syncNow()

    /**
     * Mode-B: share the folder at [folderPath] into a fresh shared realm
     * (`docs/proposals/drive-shared-realm-file-model.md` §6). Bootstraps
     * a realm (`SyncService.createSharedRealm`), populates it with the folder's
     * subtree (`shareFolderIntoRealm`), and mounts it into the sharer's own FS
     * (`mountSharedRealm`) so it appears in *Shared*. The personal folder is left
     * untouched — not a move, not a copy. Returns the created mount.
     */
    suspend fun shareFolder(folderPath: String): RepositoryResult<SharedFolderModel>

    /**
     * The member's mounted shared realms — every [SharedFolderModel] behind the
     * `listPlacements` op (`docs/proposals/drive-shared-realm-file-model.md` §6).
     * Powers the Mode-B section of *Shared*. A one-shot read (the op returns a snapshot); the
     * VM re-reads after a `shareFolder`.
     */
    suspend fun sharedFolders(): RepositoryResult<List<SharedFolderModel>>

    /**
     * Browse a shared realm's `SharedFile` tree at [parentFileId]
     * (`docs/proposals/drive-shared-realm-file-model.md` §6). The realm
     * root is `SharedFile.ROOT_PARENT` (`""`); drill folders by their [SharedFolderEntry.fileId].
     * Backed by the `listSharedFolder` read op.
     */
    suspend fun browseSharedFolder(
        realmId: String,
        parentFileId: String,
    ): RepositoryResult<List<SharedFolderEntry>>

    /**
     * Read a shared *file*'s bytes (`readSharedFile` op) — an OnDemand fetch from
     * the realm's content store, reassembled realm-key-aware. The member must hold
     * the realm membership (the read is realm-JWT-gated).
     */
    suspend fun readSharedFile(realmId: String, fileId: String): RepositoryResult<ByteArray>

    /**
     * The shared realms **shared with this member** — the inbound *Shared with me*
     * section (`drive-shared-realm-file-model.md` §6). Backed by
     * `getSharedRealmMemberships` filtered to `role = member`; each realm's display
     * name is read from its wrapper-root `SharedFile`, so the joiner sees
     * the folder's name without it ever living on the membership. Open one by
     * browsing `sharedRealmId` via the shared-realm read ops.
     */
    suspend fun sharedWithMe(): RepositoryResult<List<InboundSharedRealm>>
}

/**
 * Caller-shaped return of [FilesRepository.shareLink]. Wraps the relay-assigned
 * `shareId` + the per-share `K_share` (as base64url) the UI assembles the
 * canonical URL from. The latter is secret-equivalent (it IS the read
 * capability — A11) and MUST be dropped on configuration change.
 */
data class ShareLinkPayload(
    val shareId: String,
    val urlFragment: String,
)

/**
 * Drive's reading of one `SyncHealthStatus.connectivity` value — split out of the repository so
 * `SuiteSyncOrderMatchesDriveTest` can drive Drive's banner through exactly the mapping production
 * uses, beside the shared strip every other app shows (`suite-sync-status.md` SS-1).
 */
fun syncHealthModelOf(
    connectivity: String,
    isSyncing: Boolean = false,
    pendingBlockCount: Int = 0,
    lastSyncedAtMillis: Long = 0L,
): SyncHealthStatusModel = SyncHealthStatusModel(
    // ⚠ Retrying is online: the relay answers, it only fails content (§5.7).
    isOnline = connectivity == SyncHealthStatus.CONNECTIVITY_ONLINE ||
        connectivity == SyncHealthStatus.CONNECTIVITY_RELAY_RETRYING,
    isSyncing = isSyncing,
    pendingBlockCount = pendingBlockCount,
    lastSyncedAtMillis = lastSyncedAtMillis,
    needsAttention = connectivity == SyncHealthStatus.CONNECTIVITY_NEEDS_ATTENTION,
    deferred = connectivity == SyncHealthStatus.CONNECTIVITY_DEFERRED,
    locked = connectivity == SyncHealthStatus.CONNECTIVITY_LOCKED,
    deviceUnrecognized = connectivity == SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED,
    storageRejected = connectivity == SyncHealthStatus.CONNECTIVITY_STORAGE_FULL,
    relayRetrying = connectivity == SyncHealthStatus.CONNECTIVITY_RELAY_RETRYING,
)

/**
 * Drive-side view of the proxy's sync-health signal
 * (`docs/proposals/drive-connectivity-service.md`). Mapped from the
 * `SyncHealthStatus` composite so the banner never touches the wire type.
 */
data class SyncHealthStatusModel(
    val isOnline: Boolean,
    val isSyncing: Boolean,
    val pendingBlockCount: Int,
    val lastSyncedAtMillis: Long,
    /**
     * The relay is reachable but rejecting this device's writes (`402`, entitlement enforcement) —
     * the "storage needs attention on this device" case
     * (`docs/proposals/enforcement-followup-tracks.md`). Drives a distinct banner instead of an
     * indefinite "Syncing…". Defaults `false`.
     */
    val needsAttention: Boolean = false,
    /**
     * Push is deferred by the user's `WifiOnly` policy on a metered network while a backlog
     * waits — a benign "Waiting for Wi-Fi" state with a "Sync now over cellular" escape hatch,
     * not an error (`sync-metered-deferred-status.md` §2). Defaults `false`.
     */
    val deferred: Boolean = false,
    /**
     * Control's realm is **locked** on this device, so nothing can sync and the entitlement
     * cannot be read. Must be checked *before* [StorageStatusModel.hasStorage]: a locked proxy
     * reports `hasStorage = false` for want of an owner id, which is indistinguishable from
     * "never subscribed" unless this flag says otherwise. Defaults `false`.
     */
    val locked: Boolean = false,
    /**
     * The relay does not recognise this device (`401`) — it holds no registration for it, or this
     * device was revoked from another one. Nothing syncs until it is re-admitted, and the relay is
     * *reachable*, so this must not read as "Offline". Defaults `false`.
     */
    val deviceUnrecognized: Boolean = false,
    /**
     * The relay refused this device's file **content** with `507` — the owner is at their storage
     * quota. Drives [app.tenshu.drive.domain.SyncBannerState.StorageFull] from the *relay* side.
     *
     * ⚠⚠ Needed alongside `StorageStatusModel.isFull` rather than instead of it: that flag asks
     * `remainingBytes <= 0` against figures that re-fetch on a 60 s backstop, so it reads
     * `false` both for a realm with a sliver of room too small for a 2 MiB chunk (e.g.
     * 836 bytes free on a 5 GiB tier) and for room another of the owner's devices has just
     * consumed. In both cases the client believes it can upload and the relay disagrees, and
     * without this flag the banner says "Uploading…" indefinitely. Defaults `false`.
     */
    val storageRejected: Boolean = false,
    /**
     * The relay answers but has been failing this device's file-content uploads (`5xx`, `429`,
     * transport), so uploads wait before trying again (`control-background-work-off-fgs.md` §5.7).
     * Rides on [app.tenshu.drive.domain.SyncBannerState.Syncing] as its reason. Defaults `false`.
     */
    val relayRetrying: Boolean = false,
)

/**
 * The proxy's schema pull-fence verdict (`core-schema-evolution.md` §7.3), as Drive sees it.
 *
 * [fenced] means some device in the realm has already written data at a schema version this
 * device does not have, so the proxy has **paused pulling** rather than garble it. Local
 * reads and *pushes* keep working — the device is read-only-stale, not bricked — and the
 * only fix is updating this device's apps.
 *
 * [serviceIds] are the offending services' **internal ids** (`tenshu/…/service`), carried
 * for diagnostics. ⚠ They are deliberately NOT rendered to the user: there is no
 * service-id→app-name mapping here, so a banner naming them would read as noise.
 */
data class SchemaFenceModel(
    val fenced: Boolean,
    val serviceIds: List<String> = emptyList(),
)

/**
 * Drive-side view of the proxy's deferred-content signal (`sync-metered-plane-split.md` §8),
 * mapped from the `ContentTransferStatus` composite so the banner never touches the wire type.
 *
 * [pendingFileCount] is a count of **files**, which is what makes the banner's copy honest: it
 * reaches zero once Drive's uploads are done, unlike the realm-wide block backlog.
 */
data class ContentTransferModel(
    val deferred: Boolean,
    val pendingFileCount: Int = 0,
    val pendingBytes: Long = 0L,
    /**
     * Files in the upload pass currently running, `0` when none is — the denominator of
     * *"Uploading 3 of 12"*. Fixed for the pass, unlike [pendingFileCount], which counts down.
     */
    val passTotal: Int = 0,
    /** Files resolved so far in the current pass. */
    val passCompleted: Int = 0,
)

/**
 * One row in the *My shares* list. Mirrors the projection
 * [app.tenshu.drive.ui.screens.shared.MyShareRow] uses but stays in the data
 * layer so the same model serves any other consumer.
 */
data class ShareModel(
    val shareId: String,
    val filePath: String,
    val fileName: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long?,
    val maxOpens: Int?,
    val revoked: Boolean,
)

/**
 * One mounted shared realm (Mode-B), the data-layer projection of a `Placement`
 * (`docs/proposals/drive-shared-realm-file-model.md` §3 D3). [sharedRealmId] keys
 * the realm; [name] + [parentPath] are where the member mounted it in their FS.
 * Open it by browsing `sharedRealmId` via the shared-realm read ops.
 */
data class SharedFolderModel(
    val sharedRealmId: String,
    val name: String,
    val parentPath: String,
)

/**
 * One entry in a shared-realm folder listing (`drive-shared-realm-file-model.md`
 * §6), the data-layer projection of a `SharedFile`. [fileId] addresses
 * it within the realm (drill folders, open files); [isFolder] picks browse-vs-open.
 */
data class SharedFolderEntry(
    val fileId: String,
    val name: String,
    val isFolder: Boolean,
)

/**
 * One inbound shared realm — a realm shared **with** this member (`role = member`),
 * the data-layer projection for the *Shared with me* section
 * (`drive-shared-realm-file-model.md` §6). [name] is read from the
 * realm's wrapper-root `SharedFile`; it is `null` until that node has synced in,
 * in which case the UI shows a fallback label. Open it by browsing [sharedRealmId].
 */
data class InboundSharedRealm(
    val sharedRealmId: String,
    val name: String?,
)
