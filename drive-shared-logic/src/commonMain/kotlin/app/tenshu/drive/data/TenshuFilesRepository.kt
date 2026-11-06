package app.tenshu.drive.data

import app.tenshu.error.ErrorCopyKey
import app.tenshu.error.errorCopyKey

import app.tenshu.ingest.backup.BackupUploadOutcome
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.liststate.ListLoadState
import org.tenshu.core.common.util.bestEffort
import org.tenshu.sync.client.BackgroundTaskHandle
import org.tenshu.sync.client.withBulkWriteTask
import org.tenshu.core.userInitiatedWork
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import app.tenshu.drive.domain.LogUnexpectedErrorUseCase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.tenshu.core.NotFoundError
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.messageOrNull
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.flow
import org.tenshu.notifications.client.notificationService
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.core.ListBuilder
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.thenBy
import org.tenshu.core.collection.list.thenByDescending
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.contains
import org.tenshu.core.isA
import org.tenshu.core.lowercase
import org.tenshu.core.equalTo
import org.tenshu.core.entity.get
import org.tenshu.core.file.Category
import org.tenshu.core.file.ContentModifiedAt
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.Data
import org.tenshu.core.file.Device
import org.tenshu.core.file.File
import org.tenshu.core.file.FileHistory
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.FileCount
import org.tenshu.core.file.Folder
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.name
import org.tenshu.core.file.path
import org.tenshu.core.file.MovedFileModification
import org.tenshu.core.file.Preview
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.RenamedFileModification
import org.tenshu.core.file.Size
import org.tenshu.core.file.files
import org.tenshu.core.file.share.Share
import org.tenshu.core.file.share.createdAtMillis
import org.tenshu.core.file.share.expiresAtMillis
import org.tenshu.core.file.share.fileId
import org.tenshu.core.file.share.maxOpens
import org.tenshu.core.file.share.revoked
import org.tenshu.core.file.share.revokeShareLink
import org.tenshu.core.file.share.shareId
import org.tenshu.core.file.share.shareLink
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.file.placement.Placement
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.system.AlreadyExistsError
import org.tenshu.core.file.system.StillSyncingError
import org.tenshu.core.file.system.MoveNeedsLocalBytesError
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.evictFileBytes
import org.tenshu.core.file.system.hasLocalData
import org.tenshu.core.file.system.listPlacements
import org.tenshu.core.file.system.listSharedFolder
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.mountSharedRealm
import org.tenshu.core.file.system.readSharedFile
import org.tenshu.core.file.system.shareFolderIntoRealm
import org.tenshu.core.fold
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.core.getOrNull
import org.tenshu.core.ifEmptyError
import org.tenshu.core.paging.toPagingData
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.eager
import org.tenshu.core.relation.relation
import org.tenshu.file.client.fileService
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.identity.client.identityService
import org.tenshu.identity.getSharedRealmMemberships
import org.tenshu.sync.SyncHealthStatus
import org.tenshu.sync.client.contentTransfer
import org.tenshu.sync.client.contentUploadProgress
import org.tenshu.sync.client.schemaFence
import org.tenshu.sync.client.syncHealth
import org.tenshu.sync.fencedServices
import org.tenshu.sync.client.syncService
import org.tenshu.sync.closeContentStream
import org.tenshu.sync.createSharedRealm
import org.tenshu.sync.openContentStream
import org.tenshu.sync.readContentRange
import org.tenshu.sync.syncNow
import org.tenshu.storage.InsufficientStorageError
import org.tenshu.storage.checkCapacity
import org.tenshu.storage.client.status
import org.tenshu.storage.client.storageService

class TenshuFilesRepository(
    private val client: TenshuClient,
    private val fileMapper: FileMapper,
    private val logUnexpectedError: LogUnexpectedErrorUseCase,
    private val delegate: FilesRepositoryPlatformDelegate
) : FilesRepository {

    override suspend fun uploadFile(
        uriString: String,
        parentPath: String,
        fileName: String
    ): RepositoryResult<Unit> = guardingSourceRead(
        // ⚠ The throwable's own text is deliberately dropped: `RepositoryError.Unknown`
        // carries an `ErrorCopyKey`, not freetext, so a platform message can never reach a user
        // verbatim. Same key the sibling catch in `uploadFolder` uses.
        onUnreadable = { RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED)) },
    ) {
        delegate.uploadPlatformFile(
            client = client,
            parentPath = parentPath,
            fileName = fileName,
            uriString = uriString
        ).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error ->
                val repositoryError = when (error) {
                    is AlreadyExistsError -> RepositoryError.AlreadyExists
                    // Local-first (ADR 0019): no account does not fail an upload, so
                    // the only storage error here is a subscribed user over their
                    // paid quota — surface it instead of silently dropping it.
                    is InsufficientStorageError -> RepositoryError.InsufficientStorage(
                        requiredBytes = error.requiredBytes.get(),
                        availableBytes = error.availableBytes.get(),
                    )
                    else -> RepositoryError.Unknown(error.errorCopyKey())
                }
                RepositoryResult.Error(repositoryError)
            }
        )
    }

    override suspend fun getFiles(folderPath: String): Flow<List<FileModel>> {
        return client.fileService.defaultFileSystem.root
            .files
            .navigateTo(folderPath)
            .select {
                fields(File.Field.PATH)
                // Metadata only. Bytes and previews are pulled per-row by
                // [getRemoteFile] / [getPreview] when scrolled into view —
                // resolving them in the listing would block first paint on
                // 20+ cold decodes and blow Binder for large files.
                // size + lastModifiedAt are value properties (§3.3), projected
                // via relations so the row subline can render them.
                // Folder.FileCount replaces projecting the FILES field — the
                // field materialised + shipped EVERY child entity per folder
                // row just for the "N files" count; the relation is a
                // member-hash count, no children on the wire.
                relations(
                    File.Category,
                    File.Size,
                    File.CreatedAt,
                    File.LastModifiedAt,
                    File.ContentModifiedAt,
                    Folder.FileCount,
                )
            }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { files -> files.toList().map { fileMapper.map(it) } },
                    onFailure = { error ->
                        if (error is NotFoundError) {
                            logUnexpectedError(error)
                        }
                        emptyList()
                    }
                )
            }
            // `fileMapper.map` decodes bitmaps on Android; collecting on the
            // default Main.immediate would stall the UI on first paint.
            .flowOn(Dispatchers.Default)
    }

    override fun recentFiles(limit: Int): Flow<ListLoadState<FileModel>> {
        return client.fileService.defaultFileSystem.root
            .files
            .traverse()                              // every file/folder in the drive
            .sortByDescending(File.LastModifiedAt)   // newest first (value property)
            .take(limit)
            .select {
                fields(File.Field.PATH)
                relations(
                    File.Category,
                    File.Size,
                    File.CreatedAt,
                    File.LastModifiedAt,
                    File.ContentModifiedAt,
                    Folder.FileCount,
                )
            }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { files ->
                        ListLoadState.Loaded(files.toList().map { fileMapper.map(it) })
                    },
                    onFailure = { error ->
                        if (error is NotFoundError) logUnexpectedError(error)
                        // ⚠ `Failed`, never `emptyList()`, which would tell Home the drive is
                        // empty at the one moment it cannot know — see [ListLoadState].
                        ListLoadState.Failed
                    }
                )
            }
            .flowOn(Dispatchers.Default)
    }

    override fun searchFiles(query: String): Flow<ListLoadState<FileModel>> {
        val needle = query.trim()
        if (needle.isEmpty()) return flowOf(ListLoadState.Loaded(emptyList()))

        return client.fileService.defaultFileSystem.root
            .files
            .traverse()                                  // every file/folder in the drive
            // Match on the derived name (last path segment), resolved proxy-side per
            // row via the `File.Name` dynamic property — so a hit on an ancestor
            // folder name alone never surfaces its unrelated descendants, and only
            // the matching rows cross IPC (no client-side re-filter).
            // ignoreCase: user-facing search wants "photo" to match "Photo.jpg" —
            // an EXPLICIT opt-in, since `contains` defaults case-sensitive like
            // Kotlin's (core-operation-dispatch.md §4).
            .filter<File> { name.contains(needle, ignoreCase = true) }
            .select {
                fields(File.Field.PATH)
                relations(
                    File.Category,
                    File.Size,
                    File.CreatedAt,
                    File.LastModifiedAt,
                    File.ContentModifiedAt,
                    Folder.FileCount,
                )
            }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { files ->
                        ListLoadState.Loaded(files.toList().map { fileMapper.map(it) })
                    },
                    onFailure = { error ->
                        if (error is NotFoundError) logUnexpectedError(error)
                        // ⚠ `Failed`, never `emptyList()` — indistinguishable from "no file
                        // matches", which is the one thing a search result must be able to say.
                        ListLoadState.Failed
                    }
                )
            }
            .flowOn(Dispatchers.Default)
    }

    override fun fileDetails(path: String): Flow<FileDetailsModel?> {
        // The entity carrying the metadata lives in its PARENT folder's listing
        // (`navigateTo(parent)` then match by full path) — for both files and
        // folders. `filter` narrows the stream to the ONE matching row proxy-side
        // (rather than shipping the whole parent listing per update), which is what
        // makes projecting `File.Preview` here safe: exactly one row carries the
        // deferred marker.
        //
        // `File.Preview(480, 480)` is deferred-by-default (`core-deferred-properties.md`):
        // the row emits instantly with metadata (preview null), then the stream
        // PATCHES the row when the proxy resolves the bytes. Lists stay on the
        // per-row loader (§6).
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        return client.fileService.defaultFileSystem.root
            .files
            .navigateTo(parent)
            .filter<File> { this.path equalTo path }
            .select {
                fields(File.Field.PATH)
                relations(
                    File.Category,
                    File.Size,
                    File.CreatedAt,
                    File.LastModifiedAt,
                    File.ContentModifiedAt,
                    Folder.FileCount,
                    File.Preview(width = 480, height = 480),
                )
            }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { files ->
                        // `mapDetails` (Entity<File> -> model) drives the element
                        // type just as getFiles' `fileMapper::map` does; then match
                        // on the resulting model's path.
                        files.toList()
                            .map(fileMapper::mapDetails)
                            .firstOrNull { it.file.path == path }
                    },
                    onFailure = { error ->
                        if (error is NotFoundError) logUnexpectedError(error)
                        null
                    }
                )
            }
            .flowOn(Dispatchers.Default)
    }

    override fun fileActivity(path: String): Flow<List<FileActivity>> {
        // File-rooted history (core-nested-projection.md §7.4): navigate to the file
        // then into `File.FileHistory` — the per-file `EntityPropertyDataId` array
        // maintained by the generic `RelationMaintainer` (a `relationMembership` spec in
        // `fileServiceCommonModule`). O(this file's mods) and
        // observed in place: the chain's `.flow()` rides the same `setArrayListener`
        // infra `fileService.modifications` does, so re-emits live on every
        // `addToArray`/`removeFromArray` of *this* file's array — not a realm-wide
        // `modifications.filter { … }`.
        return client.fileService.defaultFileSystem.root.files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .ifEmptyError()
            .relation(File.FileHistory)
            // `select` projects ONLY what's named, so list every scalar mapActivity
            // reads + the compositeKey (the row's dataId key) alongside the Device
            // relation. Subtype fields absent on a given row are simply skipped.
            .select {
                fields(
                    FileModification.Field.COMPOSITE_KEY,
                    FileModification.Field.TIMESTAMP,
                    FileModification.Field.DEVICE_ID,
                    RenamedFileModification.Field.OLD_NAME,
                    RenamedFileModification.Field.NEW_NAME,
                    MovedFileModification.Field.NEW_PARENT_PATH,
                )
                relations(FileModification.Device)
            }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { mods ->
                        // `mapActivity` (Entity → model) drives the element type;
                        // the per-file array isn't ordered, so sort newest-first here
                        // (the list is RETENTION_CAP-bounded, so client-side is fine).
                        mods.toList()
                            .map(fileMapper::mapActivity)
                            .sortedByDescending { it.timestampMillis }
                    },
                    onFailure = { error ->
                        // Surface EVERY failure, not just NotFoundError. A wire /
                        // registration failure on the `.relation(File.FileHistory)`
                        // read otherwise vanishes into an empty Activity list with no
                        // trace. Logged to Settings → Errors.
                        logUnexpectedError(error)
                        emptyList()
                    }
                )
            }
            .flowOn(Dispatchers.Default)
    }

    /**
     * The folder listing — the only one; there is no natural (store) order listing.
     *
     * Pages are cut under [listingOrder] and projected by [listingRow]. The three hooks are
     * specified in `drive-sorted-folder-paging.md` §6 and their contracts live on
     * [org.tenshu.core.paging.pagingSource]; what is load-bearing *here* is which argument each
     * receives:
     *
     * - `ordering` runs **in the chain**, ahead of each page's window, so
     *   `sortBy(…).drop(pos).take(n).select` fuses to one bounded thin pass
     *   (`core-ordered-window-fusion.md` §8). ⚠ Only keys that fusion reads off the thin row may
     *   appear in it — see [listingOrder].
     * - `invalidation` observes the **projected** chain, never the bare receiver. ⚠⚠ Bare rows
     *   are composites and diff by `path` alone, so an in-place overwrite (`ReplaceFile`, or a
     *   peer's sync landing the same way) signals nothing: the row keeps its stale place under a
     *   date sort, and one that moved above the loaded boundary is never served.
     * - `rowKey` is `path`, the same key the list renders under. ⚠⚠ A duplicate `LazyColumn` key
     *   is a crash, and a head insert arriving between an append and its reload re-serves the
     *   previous page's last row.
     */
    override fun getFilesPaged(folderPath: String, sort: FileSort): Flow<PagingData<FileModel>> {
        return client.fileService.defaultFileSystem.root
            .files
            .navigateTo(folderPath)
            .toPagingData(
                mapper = fileMapper::map,
                pagingConfig = LISTING_PAGING_CONFIG,
                ordering = { it.listingOrder(sort) },
                invalidation = { it.select { listingRow() } },
                rowKey = { it.path },
            ) { window ->
                window.select { listingRow() }
            }
    }

    /**
     * The Drive twin of Fit's `withImportTask` — see `BackupDataPlane.withBulkUploadTask`.
     * The active handle is kept so the per-file loops can heartbeat
     * ([renewActiveUploadTaskLease]) — a multi-minute single file must not
     * lease-expire the task (expiry degrades gracefully: the lane closes and
     * later saves fall back per-save, but batching is lost).
     */
    private var activeUploadTask: BackgroundTaskHandle<*>? = null

    private suspend fun renewActiveUploadTaskLease() {
        activeUploadTask?.let {
            bestEffort("TenshuFilesRepository -> renewing the upload task lease") { it.renewLease() }
        }
    }

    override suspend fun <T> withBulkUploadTask(kind: String, block: suspend () -> T): T =
        client.withBulkWriteTask(kind) { task ->
            // Null (degraded) is not a special case here: the field simply stays
            // null and `renewActiveUploadTaskLease` becomes a no-op, which is
            // exactly right — there is no lease to renew.
            activeUploadTask = task
            try {
                block()
            } finally {
                activeUploadTask = null
            }
        }

    /**
     * A folder upload always starts from the user's own pick, so its tasks are marked user-started:
     * on Android Control may then hold its foreground service until the folder is done, after the
     * user leaves Drive (`control-background-work-off-fgs.md` §5.1).
     */
    override suspend fun uploadFolder(
        uriString: String,
        folderPath: String,
        onProgress: (UploadProgress) -> Unit,
        onConflict: suspend (FileConflict) -> ConflictResolution,
        onCloudPreflight: suspend (CloudPreflight) -> CloudPreflightDecision,
    ): RepositoryResult<FolderUploadSummary> = userInitiatedWork {
        uploadPickedFolder(uriString, folderPath, onProgress, onConflict, onCloudPreflight)
    }

    private suspend fun uploadPickedFolder(
        uriString: String,
        folderPath: String,
        onProgress: (UploadProgress) -> Unit,
        onConflict: suspend (FileConflict) -> ConflictResolution,
        onCloudPreflight: suspend (CloudPreflight) -> CloudPreflightDecision,
    ): RepositoryResult<FolderUploadSummary> {
        // The proxy can't enumerate the SAF tree grant, so the walk is
        // client-side. See docs/proposals/drive-folder-upload.md.
        val listing = try {
            delegate.listFolderEntries(uriString)
        } catch (e: Throwable) {
            return RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED))
        }

        val base = joinPaths(folderPath, listing.rootFolderName)

        val fileEntries = listing.entries.filter { !it.isDirectory }

        // Tier-3 pre-flight (storage-capacity-gating.md §2): sum the folder's
        // bytes (known locally from the SAF/file walk) and ask storage ONCE
        // before doing any work, so a doomed upload fails up-front rather than
        // file-by-file midway. A single `checkCapacity` round trip; per-file
        // uploads still hit the Tier-2 op gate. An unbound / over-quota result
        // blocks the whole job. (Bytes the platform couldn't size count as 0.)
        val totalBytes = fileEntries.sumOf { it.sizeBytes }
        val capacity = client.storageService.checkCapacity(totalBytes).getOrNull()
        if (capacity != null && !capacity.fits.get()) {
            return RepositoryResult.Error(
                RepositoryError.InsufficientStorage(
                    requiredBytes = totalBytes,
                    availableBytes = capacity.availableBytes.get(),
                ),
            )
        }

        // Cloud pre-flight (drive-folder-upload.md §11.5): if any files are
        // online-only cloud placeholders, tell the user they must download first
        // and let them Include (default), Skip just those, or Cancel — before any
        // work starts. No online-only files ⇒ no prompt, no behaviour change.
        var skipOnlineOnly = false
        val onlineOnly = fileEntries.filter { it.isOnlineOnly }
        if (onlineOnly.isNotEmpty()) {
            when (onCloudPreflight(CloudPreflight(onlineOnly.size, onlineOnly.sumOf { it.sizeBytes }))) {
                CloudPreflightDecision.Include -> Unit
                CloudPreflightDecision.Skip -> skipOnlineOnly = true
                CloudPreflightDecision.Cancel ->
                    return RepositoryResult.Success(FolderUploadSummary(0, 0, 0, 0))
            }
        }

        val total = fileEntries.size
        var uploaded = 0
        var skipped = 0
        var failed = 0
        var cloudUnavailable = 0
        var completed = 0

        // The whole walk is ONE task — folder creates, file uploads,
        // modifications and notifications all stage into its sync lane.
        withBulkUploadTask("drive/folder-upload") {
        ensureFolder(base)

        for (entry in listing.entries) {
            // Cancel between files; cancel *during* a file is wired
            // cross-process by [awaitOrWireCancel].
            currentCoroutineContext().ensureActive()
            if (entry.isDirectory) {
                ensureFolder(joinPaths(base, entry.relativePath))
                continue
            }

            // The user chose to skip online-only files — count them skipped
            // (no read, so no download) and move on.
            if (skipOnlineOnly && entry.isOnlineOnly) {
                skipped++
                completed++
                continue
            }

            onProgress(UploadProgress(entry.fileName, completed, total, isOnlineOnly = entry.isOnlineOnly))

            val parentRelative = entry.relativePath.substringBeforeLast('/', "")
            val parentPath = if (parentRelative.isEmpty()) base else joinPaths(base, parentRelative)

            // A single file must never sink the whole walk. Any per-file failure
            // — including a cloud (online-only) file whose on-demand download
            // stalled and timed out (drive-folder-upload.md §11) — fails just
            // that file and the loop moves on. Real cancellation (user pressed
            // Cancel) still propagates.
            val outcome = try {
                uploadOneFile(parentPath, entry.fileName, entry.leafUriString, onConflict)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                FileOutcome.Failed
            }
            when (outcome) {
                FileOutcome.Uploaded -> uploaded++
                FileOutcome.Skipped -> skipped++
                // An online-only file that failed = the cloud provider wouldn't
                // deliver it on read (§11). Attribute it separately so the summary
                // says "couldn't download from the cloud", not a generic failure.
                FileOutcome.Failed -> if (entry.isOnlineOnly) cloudUnavailable++ else failed++
            }
            completed++
            renewActiveUploadTaskLease()
        }
        }

        return RepositoryResult.Success(
            FolderUploadSummary(
                total = total,
                uploaded = uploaded,
                skipped = skipped,
                failed = failed,
                cloudUnavailable = cloudUnavailable,
            )
        )
    }

    // Auto-backup destination folders are created lazily, top-down, the first
    // time a bucket uploads — ensure the whole /Backup/<device>/<bucket> chain.
    override suspend fun ensureBackupDestination(parentPath: String) {
        ensureFolderChain(parentPath)
    }

    override suspend fun contentHashOf(uriString: String): String? =
        delegate.contentHashOf(uriString)

    override suspend fun uploadForBackup(
        parentPath: String,
        fileName: String,
        uriString: String,
    ): BackupUploadOutcome {
        return when (val attempt = attemptUpload(parentPath, fileName, uriString)) {
            UploadAttempt.Success -> BackupUploadOutcome.Uploaded
            // §3.5 backstop: a file already at its destination is a silent Skip
            // (no user present), not a conflict dialog.
            UploadAttempt.AlreadyExists -> BackupUploadOutcome.Skipped
            is UploadAttempt.OtherFailure -> BackupUploadOutcome.Failed(attempt.reason)
        }
    }

    /** Ensure every ancestor folder of [path] exists (idempotent on AlreadyExists). */
    private suspend fun ensureFolderChain(path: String) {
        var current = ""
        for (segment in path.split("/").filter { it.isNotBlank() }) {
            current = "$current/$segment"
            ensureFolder(current)
        }
    }

    private enum class FileOutcome { Uploaded, Skipped, Failed }

    private sealed interface UploadAttempt {
        data object Success : UploadAttempt
        data object AlreadyExists : UploadAttempt

        /**
         * ⚠⚠ Carries the cause both arms of [attemptUpload] have in hand — a `Throwable` from the
         * source read, a [TError] from the op. The interactive paths at least show a user
         * *something*; without [reason] the headless backup drain has nothing
         * (`drive-synced-folders.md` §8c).
         */
        data class OtherFailure(val reason: String) : UploadAttempt
    }

    /**
     * ⚠ Message first, type id only as a fallback: `TenshuException`'s message *is* the error id
     * for a typed throw, so this costs nothing there and wins wherever the error carries a real
     * sentence.
     */
    private fun TError.describe(): String =
        messageOrNull()?.takeIf { it.isNotBlank() } ?: schema.id.get()

    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "source could not be read"

    /**
     * Run a platform-delegate call whose failure mode is a **throw**, not a [TResult].
     *
     * The delegates turn the user's pick into bytes, and a source that cannot be read throws
     * rather than failing: an iOS document *package* (`.key` / `.pages` / `.numbers` are
     * directories, which `NSData.dataWithContentsOfFile` cannot read), a desktop directory, a
     * lapsed content grant, a file deleted mid-drain. That happens while evaluating the
     * **arguments** of the upload op, so it never becomes a `TResult.Failure` and none of the
     * `fold(onFailure = ...)` below can see it — it propagates out of the caller's
     * `viewModelScope.launch`, and on Kotlin/Native an unhandled coroutine exception is
     * `abort()` — a picked Keynote package would crash Drive-on-iOS.
     *
     * This is the boundary that turns such a throw back into an ordinary repository failure,
     * so every caller keeps the one failure vocabulary it already handles. The delegates stay
     * free to throw; nothing above this class has to know they do.
     *
     * ⚠⚠ [CancellationException] is rethrown, never mapped. Upload STOP and
     * [awaitOrWireCancel] cancel the upload coroutine, and swallowing it here would report a
     * user-initiated stop as a failure *and* break structured concurrency. Same rule as the
     * per-file guard in [uploadFolder] — see the comment there.
     */
    private suspend fun <T> guardingSourceRead(
        onUnreadable: (Throwable) -> T,
        read: suspend () -> T,
    ): T = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        onUnreadable(e)
    }

    private suspend fun attemptUpload(
        parentPath: String,
        fileName: String,
        leafUriString: String,
    ): UploadAttempt = guardingSourceRead(onUnreadable = { UploadAttempt.OtherFailure(it.describe()) }) {
        delegate.uploadPlatformFileCancellable(
            client = client,
            parentPath = parentPath,
            fileName = fileName,
            uriString = leafUriString,
        ).awaitOrWireCancel().fold(
            onSuccess = { UploadAttempt.Success },
            onFailure = { error ->
                if (error is AlreadyExistsError) UploadAttempt.AlreadyExists
                else UploadAttempt.OtherFailure(error.describe())
            }
        )
    }

    private suspend fun uploadOneFile(
        parentPath: String,
        fileName: String,
        leafUriString: String,
        onConflict: suspend (FileConflict) -> ConflictResolution,
    ): FileOutcome = when (attemptUpload(parentPath, fileName, leafUriString)) {
        UploadAttempt.Success -> FileOutcome.Uploaded
        is UploadAttempt.OtherFailure -> FileOutcome.Failed
        UploadAttempt.AlreadyExists -> when (onConflict(FileConflict(fileName, parentPath))) {
            ConflictResolution.Skip -> FileOutcome.Skipped
            ConflictResolution.KeepBoth -> uploadKeepingBoth(parentPath, fileName, leafUriString)
            ConflictResolution.Overwrite -> replaceExisting(parentPath, fileName, leafUriString)
        }
    }

    /** In-place overwrite via the atomic ReplaceFile op. A successful
     *  overwrite counts as [FileOutcome.Uploaded]. */
    private suspend fun replaceExisting(
        parentPath: String,
        fileName: String,
        leafUriString: String,
    ): FileOutcome = guardingSourceRead(onUnreadable = { FileOutcome.Failed }) {
        delegate.replacePlatformFileCancellable(
            client = client,
            parentPath = parentPath,
            fileName = fileName,
            uriString = leafUriString,
        ).awaitOrWireCancel().fold(
            onSuccess = { FileOutcome.Uploaded },
            onFailure = { FileOutcome.Failed }
        )
    }

    private suspend fun uploadKeepingBoth(
        parentPath: String,
        fileName: String,
        leafUriString: String,
    ): FileOutcome {
        var counter = 1
        while (counter < 100) {
            when (attemptUpload(parentPath, keepBothCandidateName(fileName, counter), leafUriString)) {
                UploadAttempt.Success -> return FileOutcome.Uploaded
                is UploadAttempt.OtherFailure -> return FileOutcome.Failed
                UploadAttempt.AlreadyExists -> counter++
            }
        }
        return FileOutcome.Failed
    }

    /** Idempotent on AlreadyExists — a pre-existing folder is normal on
     *  re-upload. A failure here isn't fatal; the per-file uploads will
     *  surface in the summary's `failed` count. */
    private suspend fun ensureFolder(path: String) {
        createFolder(path)
    }

    private fun joinPaths(vararg parts: String): String =
        "/" + parts
            .flatMap { it.split("/") }
            .filter { it.isNotBlank() }
            .joinToString("/")

    override suspend fun createFolder(path: String): RepositoryResult<Unit> {
        val result = client.fileService.defaultFileSystem
            .createFolder(path)

        return result.fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error ->
                val repositoryError = when (error) {
                    is AlreadyExistsError -> RepositoryError.AlreadyExists
                    else -> RepositoryError.Unknown(error.errorCopyKey())
                }
                RepositoryResult.Error(repositoryError)
            }
        )
    }

    override suspend fun deleteFile(path: String): RepositoryResult<Unit> {
        val result = client.fileService.defaultFileSystem
            .deleteFile(path)

        return result.fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error ->
                RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey()))
            }
        )
    }

    override suspend fun downloadFile(path: String): RepositoryResult<Unit> {
        // Reuse the OnDemand-materialising read: a successful fetch has already
        // persisted the bytes on the proxy's disk, which is all "download" means.
        return when (val result = getRemoteFile(path)) {
            is RepositoryResult.Success -> RepositoryResult.Success(Unit)
            is RepositoryResult.Error -> result
        }
    }

    override suspend fun evictFile(path: String): RepositoryResult<Unit> {
        val result = client.fileService.defaultFileSystem
            .evictFileBytes(path)

        return result.fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error ->
                RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey()))
            }
        )
    }

    override suspend fun hasLocalData(path: String): Boolean {
        // O(1) byte-presence probe; any failure ⇒ treat as not-yet-present so the
        // row falls back to its intent-based glyph rather than claiming presence.
        return client.fileService.defaultFileSystem
            .hasLocalData(path)
            .fold(
                onSuccess = { it?.get() ?: false },
                onFailure = { false }
            )
    }

    override suspend fun renameFile(path: String, newName: String): RepositoryResult<String> {
        val parentPath = path.substringBeforeLast("/")
        val newPath = if (parentPath.isEmpty()) "/$newName" else "$parentPath/$newName"
        val result = client.fileService.defaultFileSystem
            .moveFile(path, newPath)

        return result.fold(
            onSuccess = { RepositoryResult.Success(newPath) },
            onFailure = { error ->
                val repositoryError = when (error) {
                    is AlreadyExistsError -> RepositoryError.AlreadyExists
                    is StillSyncingError -> RepositoryError.StillSyncing
                    is MoveNeedsLocalBytesError -> RepositoryError.MoveNeedsLocalBytes
                    else -> RepositoryError.Unknown(error.errorCopyKey())
                }
                RepositoryResult.Error(repositoryError)
            }
        )
    }

    override suspend fun moveFile(
        sourcePath: String,
        destinationFolderPath: String,
    ): RepositoryResult<Unit> {
        val name = sourcePath.substringAfterLast("/")
        val targetPath = if (destinationFolderPath == "/") {
            "/$name"
        } else {
            "${destinationFolderPath.trimEnd('/')}/$name"
        }
        val result = client.fileService.defaultFileSystem
            .moveFile(sourcePath, targetPath)

        return result.fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error ->
                val repositoryError = when (error) {
                    is AlreadyExistsError -> RepositoryError.AlreadyExists
                    is StillSyncingError -> RepositoryError.StillSyncing
                    is MoveNeedsLocalBytesError -> RepositoryError.MoveNeedsLocalBytes
                    else -> RepositoryError.Unknown(error.errorCopyKey())
                }
                RepositoryResult.Error(repositoryError)
            }
        )
    }

    override suspend fun getRemoteFile(path: String): RepositoryResult<ByteArray> {
        // `relations(File.Data)` so the OnDemand materialiser resolves
        // bytes on the proxy before the entity crosses Binder; navigate to
        // the parent first so nested-folder files are reachable (same shape
        // as [getFiles]).
        val result = client.fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .select { relations(File.Data) }
            .resolve()

        return result.fold(
            onSuccess = { entity ->
                // `TByteArray.getOrNull()` is graceful on a SharedMemory
                // cache miss — see its KDoc for why we don't use `get()` here.
                val bytes = entity
                    ?.get(File.Data)
                    ?.getOrNull()
                if (bytes != null) RepositoryResult.Success(bytes)
                else RepositoryResult.Error(
                    // ⚠⚠ Not `UNEXPECTED`. Absent bytes are the ordinary state of `OnDemand`
                    // content that has not arrived — a reclaimed content, an upload still in
                    // flight, a peer that has not pushed, a transient relay failure — and the
                    // generic "something went wrong" sentence tells the user their app is broken
                    // when their file is merely late. The preview screens render this key via
                    // `error.driveLabel()`.
                    RepositoryError.Unknown(ErrorCopyKey.CONTENT_UNAVAILABLE)
                )
            },
            onFailure = { RepositoryResult.Error(RepositoryError.Unknown(it.errorCopyKey())) }
        )
    }

    override suspend fun openStreamingContent(path: String): RepositoryResult<StreamingContentHandle> {
        // Personal-file resolve triple (drive-streaming-preview.md SQ8): the generic op can't see
        // File, so we pass (File.ID, path, File.Data.id). The same op also serves shared files;
        // this wrapper is the personal-file convenience the video preview uses.
        return client.syncService.openContentStream(
            entityType = File.ID,
            entityKey = path,
            propertyId = File.Data.id,
        ).fold(
            onSuccess = { opening ->
                RepositoryResult.Success(
                    StreamingContentHandle(
                        handle = opening.handle.get(),
                        totalSize = opening.totalSize.get(),
                        readRangeFn = { h, offset, length ->
                            client.syncService.readContentRange(h, offset, length).fold(
                                onSuccess = { it.getOrNull() },
                                onFailure = { null },
                            )
                        },
                        closeFn = { h -> client.syncService.closeContentStream(h) },
                    )
                )
            },
            onFailure = { RepositoryResult.Error(RepositoryError.Unknown(it.errorCopyKey())) },
        )
    }

    override suspend fun getPreview(
        path: String,
        width: Int,
        height: Int
    ): ByteArray? {
        readPreview(path, width, height)?.let { return it }

        // Cold path: the thumbnail may not be on this device yet, and `File.Preview` will
        // not fetch it — see [SyncStrategy.OnDemand], "reading one". Pull it into the cache
        // and ask again.
        //
        // ⚠ Two reads, not one projection of both: within a single select `File.Preview`
        // resolves before the terminal materialises anything.
        // ⚠ The class policy stays inside `GetPreview` — this only fills the cache and
        // re-asks, so `File.Preview` remains the single best-available preview API.
        cacheThumbnails(path)
        return readPreview(path, width, height)
    }

    private suspend fun readPreview(path: String, width: Int, height: Int): ByteArray? {
        val result = client.fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            // `eager(...)` is load-bearing: `File.Preview` is deferred-by-default
            // (`core-deferred-properties.md`) and this is a ONE-SHOT
            // `.get()` — without the override every thumbnail resolves to null.
            .select { relations(eager(File.Preview(width = width, height = height))) }
            .resolve()

        return result.getOrNull()?.get(File.Preview)?.getOrNull()
    }

    /**
     * Fetch this file's thumbnail blobs into the **local cache** if they are not here yet
     * — `retention-quota-model.md` tier 3, so losing them is a re-download, never data loss.
     *
     * The fetched values are deliberately discarded: the point is the side effect. See
     * [SyncStrategy.OnDemand] for why projecting the properties through this `Maybe.get()`
     * is what performs the fetch.
     */
    private suspend fun cacheThumbnails(path: String) {
        client.fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .select { relations(File.Thumbnail, File.ThumbnailLarge) }
            .resolve()
    }

    override fun getFileName(uriString: String): String? {
        return delegate.getPlatformFileName(uriString)
    }

    override suspend fun shareLink(
        path: String,
        expiresAtMillis: Long,
        maxOpens: Int,
    ): RepositoryResult<ShareLinkPayload> {
        val result = client.fileService.defaultFileSystem.shareLink(
            path = path,
            expiresAtMillis = expiresAtMillis,
            maxOpens = maxOpens.toLong(),
        )
        return result.fold(
            onSuccess = { share ->
                if (share == null) {
                    RepositoryResult.Error(
                        RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED)
                    )
                } else {
                    RepositoryResult.Success(
                        ShareLinkPayload(
                            shareId = share.shareId.get(),
                            urlFragment = share.urlFragment.get(),
                        )
                    )
                }
            },
            onFailure = { error ->
                RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey()))
            }
        )
    }

    override suspend fun revokeShareLink(shareId: String): RepositoryResult<Unit> {
        val result = client.fileService.defaultFileSystem.revokeShareLink(shareId)
        return result.fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error ->
                RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey()))
            }
        )
    }

    override fun shares(): Flow<ListLoadState<ShareModel>> {
        // Realm-wide `FileService.shares` projection (docs/file-sharing.md
        // §6.1). Mirrors how `recentFiles` projects the realm-wide
        // modification log. Each row carries enough state to render *My
        // shares* + drive the Revoke action without a per-row round-trip.
        return client.fileService.shares
            .select {
                fields(
                    Share.Field.SHARE_ID,
                    Share.Field.FILE_ID,
                    Share.Field.CREATED_AT_MILLIS,
                    Share.Field.EXPIRES_AT_MILLIS,
                    Share.Field.MAX_OPENS,
                    Share.Field.REVOKED,
                )
            }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { shares ->
                        ListLoadState.Loaded(
                            shares.toList()
                            .filter { !it.revoked.get() }
                            .map { entity ->
                                val filePath = entity.fileId.get()
                                ShareModel(
                                    shareId = entity.shareId.get(),
                                    filePath = filePath,
                                    fileName = filePath.substringAfterLast('/'),
                                    createdAtMillis = entity.createdAtMillis.get(),
                                    expiresAtMillis = entity.expiresAtMillis.get()
                                        .takeIf { it != 0L },
                                    maxOpens = entity.maxOpens.get().toInt()
                                        .takeIf { it != 0 },
                                    revoked = entity.revoked.get(),
                                )
                            }
                        )
                    },
                    onFailure = { error ->
                        logUnexpectedError(error)
                        // ⚠ `Failed`, never `emptyList()` — "you have shared nothing", claimed
                        // while the read that would know has failed.
                        ListLoadState.Failed
                    }
                )
            }
            .flowOn(Dispatchers.Default)
    }

    /**
     * ⚠⚠ Joined with sync health, not read from the storage service alone: the relay's `507`
     * refusal is the only authority that catches a realm whose *figures* still show room — a
     * sliver too small for the next chunk, or bytes another of the owner's devices has spent
     * inside the 60 s usage-refresh window. See [StorageStatusModel.isFull].
     *
     * ⚠ `onStart { emit(null) }` on the health flow is load-bearing: `combine` withholds its first
     * value until **every** source has emitted, so without a seed a device with no health row yet
     * would publish no storage status at all — blanking the figures, the banner and the backup
     * gate on exactly the cold starts where they matter most.
     */
    override fun storageStatus(): Flow<StorageStatusModel?> =
        combine(
            client.storageService.status(),
            client.syncService.syncHealth().onStart { emit(null) },
        ) { status, health ->
            status?.let {
                StorageStatusModel(
                    hasStorage = it.hasStorage.get(),
                    usedBytes = it.usedBytes.get(),
                    limitBytes = it.limitBytes.get(),
                    relayRejected = health?.connectivity?.get() ==
                        SyncHealthStatus.CONNECTIVITY_STORAGE_FULL,
                )
            }
        }
            .flowOn(Dispatchers.Default)

    override fun syncHealth(): Flow<SyncHealthStatusModel?> =
        client.syncService.syncHealth()
            .map { status ->
                status?.let {
                    SyncHealthStatusModel(
                        // ⚠ Retrying is online: the relay answers, it only fails content (§5.7).
                        isOnline = it.connectivity.get() == SyncHealthStatus.CONNECTIVITY_ONLINE ||
                            it.connectivity.get() == SyncHealthStatus.CONNECTIVITY_RELAY_RETRYING,
                        isSyncing = it.isSyncing.get(),
                        pendingBlockCount = it.pendingBlockCount.get().toInt(),
                        lastSyncedAtMillis = it.lastSyncedAtMillis.get(),
                        needsAttention =
                            it.connectivity.get() == SyncHealthStatus.CONNECTIVITY_NEEDS_ATTENTION,
                        deferred =
                            it.connectivity.get() == SyncHealthStatus.CONNECTIVITY_DEFERRED,
                        locked =
                            it.connectivity.get() == SyncHealthStatus.CONNECTIVITY_LOCKED,
                        deviceUnrecognized = it.connectivity.get() ==
                            SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED,
                        storageRejected = it.connectivity.get() ==
                            SyncHealthStatus.CONNECTIVITY_STORAGE_FULL,
                        relayRetrying = it.connectivity.get() ==
                            SyncHealthStatus.CONNECTIVITY_RELAY_RETRYING,
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    override fun schemaFence(): Flow<SchemaFenceModel?> =
        client.syncService.schemaFence()
            .map { status ->
                status?.let {
                    SchemaFenceModel(
                        fenced = it.fenced.get(),
                        serviceIds = it.fencedServices(),
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    override fun proxyConnected(): Flow<Boolean> = client.proxyConnected

    /**
     * The newest [CHANGE_LOST_WINDOW] notifications, narrowed to the change-lost ones. ⚠ A window, not
     * a type filter: the relation holds every notification type, and the chain has no type predicate.
     * A notice pushed out of the window by newer activity stops showing here (Control's feed keeps it).
     */
    override fun changesLost(): Flow<List<ChangeLostModel>> =
        client.notificationService.notifications
            .sortByDescending { timestamp }
            .take(CHANGE_LOST_WINDOW)
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { rows ->
                        rows.toList()
                            .filterIsInstance<org.tenshu.core.file.notifications.ChangeLostNotification>()
                            .map { ChangeLostModel(it.uuid.get(), it.fileName.get(), it.path.get()) }
                    },
                    // Not knowing is not "nothing lost", but a notice is not worth an error state either.
                    onFailure = { emptyList() },
                )
            }
            .flowOn(Dispatchers.Default)

    override fun contentTransfer(): Flow<ContentTransferModel?> =
        // Two proxy rows, one client model: `ContentTransferStatus` (what is queued and why) and
        // `ContentUploadProgress` (how far the current pass has got). They are separate rows only
        // because the first is a positional composite that cannot grow fields — the banner wants
        // them together, so they are combined here rather than leaking that split into the UI.
        combine(
            client.syncService.contentTransfer(),
            client.syncService.contentUploadProgress(),
        ) { status, progress ->
            status?.let {
                ContentTransferModel(
                    deferred = it.deferred.get(),
                    pendingFileCount = it.pendingFileCount.get().toInt(),
                    pendingBytes = it.pendingBytes.get(),
                    passTotal = progress?.passTotal?.get()?.toInt() ?: 0,
                    passCompleted = progress?.passCompleted?.get()?.toInt() ?: 0,
                )
            }
        }
            .flowOn(Dispatchers.Default)

    override suspend fun syncNow() {
        // Fire-and-forget kick — the op signals the scheduler's one-shot override and
        // returns; the drain runs on the next tick. Best-effort: a failure here just means
        // the user retries (the banner stays "deferred").
        //
        // The result is not surfaced to the UI (there is no banner state for "the kick
        // failed"), but it MUST be recorded: an IPC-level failure here is otherwise
        // completely invisible — the banner simply stays put, which is indistinguishable
        // from a successful kick whose drain then failed. That ambiguity is exactly what
        // makes "the button does nothing" unfalsifiable from a bug report.
        val result = client.syncService.syncNow()
        if (result is TResult.Failure) {
            println("Drive -> syncNow (Sync now over cellular) failed: ${result.error}")
        } else {
            println("Drive -> syncNow (Sync now over cellular) signalled to the proxy")
        }
    }

    override suspend fun shareFolder(folderPath: String): RepositoryResult<SharedFolderModel> {
        // Mode-B share = bootstrap a realm + populate it + self-mount, none of which
        // touches the personal folder (drive-shared-realm-file-model.md §3 D5).
        val realmResult = client.syncService.createSharedRealm()
        val realmId = realmResult.getOrNull()?.let { it as? TString }?.get()
            ?: return RepositoryResult.Error(
                RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED)
            )

        // shareFolderIntoRealm returns null on success (Shape A) — only a Failure is
        // an error; a null value is the expected success shape.
        val shareResult = client.fileService.defaultFileSystem
            .shareFolderIntoRealm(folderPath = folderPath, realmId = realmId)
        if (shareResult is org.tenshu.core.TResult.Failure) {
            return RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED))
        }

        // The sharer mounts the realm into their own FS too, so it shows in *Shared*.
        // name = the shared folder's name; parentPath = its personal-FS parent.
        val name = folderPath.substringAfterLast('/').ifEmpty { folderPath }
        val parentPath = folderPath.substringBeforeLast('/').ifEmpty { "/" }
        val mountResult = client.fileService.defaultFileSystem
            .mountSharedRealm(sharedRealmId = realmId, parentPath = parentPath, name = name)
        return mountResult.fold(
            onSuccess = {
                RepositoryResult.Success(
                    SharedFolderModel(sharedRealmId = realmId, name = name, parentPath = parentPath)
                )
            },
            onFailure = { error -> RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey())) },
        )
    }

    override suspend fun sharedFolders(): RepositoryResult<List<SharedFolderModel>> {
        // *Shared folders* (My shares) = folders I share **out** (role = owner). A
        // realm I've only joined (role = member) belongs in *Shared with me*, not here.
        // `listPlacements` is role-blind, so without this filter a joiner-created mount
        // would leak into the outbound group. Joiners cannot mount into their personal
        // tree (drive-shared-realm-file-model.md §6), so only owners hold placements and
        // this filter is a safety net that keeps the group correct if joiners ever can.
        val ownerRealmIds = ownedRealmIds()
        val result = client.fileService.defaultFileSystem.listPlacements()
        return result.fold(
            onSuccess = { placements ->
                val models = (placements?.toList() ?: emptyList())
                    .filter { it.sharedRealmId.get() in ownerRealmIds }
                    .map { p ->
                        SharedFolderModel(
                            sharedRealmId = p.sharedRealmId.get(),
                            name = p.name.get(),
                            parentPath = p.parentPath.get(),
                        )
                    }
                RepositoryResult.Success(models)
            },
            onFailure = { error ->
                logUnexpectedError(error)
                RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey()))
            },
        )
    }

    /**
     * The ids of the shared realms this member **owns** (`role = owner`), read from
     * `getSharedRealmMemberships`. Empty on read failure — the owner then sees no
     * *Shared folders* rather than a leaked inbound mount (memberships are local +
     * Eager-synced, so failure is unlikely).
     */
    private suspend fun ownedRealmIds(): Set<String> {
        val result = client.identityService.getSharedRealmMemberships()
        if (result !is org.tenshu.core.TResult.Success) return emptySet()
        return (result.value?.toList() ?: emptyList())
            .filter { it.role.get() == SharedRealmMembership.Role.OWNER }
            .map { it.sharedRealmId.get() }
            .toSet()
    }

    override suspend fun browseSharedFolder(
        realmId: String,
        parentFileId: String,
    ): RepositoryResult<List<SharedFolderEntry>> {
        val result = client.fileService.defaultFileSystem.listSharedFolder(realmId, parentFileId)
        return result.fold(
            onSuccess = { children ->
                val entries = (children?.toList() ?: emptyList()).map { sharedFile ->
                    SharedFolderEntry(
                        fileId = sharedFile.fileId.get(),
                        name = sharedFile.name.get(),
                        isFolder = sharedFile.isFolder.get(),
                    )
                }
                RepositoryResult.Success(entries)
            },
            onFailure = { error ->
                logUnexpectedError(error)
                RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey()))
            },
        )
    }

    override suspend fun readSharedFile(realmId: String, fileId: String): RepositoryResult<ByteArray> {
        val result = client.fileService.defaultFileSystem.readSharedFile(realmId, fileId)
        return result.fold(
            onSuccess = { data ->
                val bytes = (data as? TByteArray)?.get()
                    ?: return@fold RepositoryResult.Error(
                        RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED)
                    )
                RepositoryResult.Success(bytes)
            },
            onFailure = { error ->
                logUnexpectedError(error)
                RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey()))
            },
        )
    }

    override suspend fun sharedWithMe(): RepositoryResult<List<InboundSharedRealm>> {
        // `wrapperName` is a suspend call, so we can't fold over the result (its
        // lambda isn't a suspend context) — branch with an early return instead.
        val result = client.identityService.getSharedRealmMemberships()
        if (result is org.tenshu.core.TResult.Failure) {
            logUnexpectedError(result.error)
            return RepositoryResult.Error(RepositoryError.Unknown(result.error.errorCopyKey()))
        }
        val memberships = (result as org.tenshu.core.TResult.Success).value?.toList() ?: emptyList()
        val inbound = mutableListOf<InboundSharedRealm>()
        memberships
            .filter { it.role.get() == SharedRealmMembership.Role.MEMBER }
            .forEach { membership ->
                val realmId = membership.sharedRealmId.get()
                inbound += InboundSharedRealm(sharedRealmId = realmId, name = wrapperName(realmId))
            }
        return RepositoryResult.Success(inbound)
    }

    /**
     * The realm's display name = the name of its wrapper-root `SharedFile`,
     * or `null` if that node hasn't synced in yet (the joiner just joined). The UI
     * shows a fallback label in that case.
     */
    private suspend fun wrapperName(realmId: String): String? =
        client.fileService.defaultFileSystem
            .listSharedFolder(realmId, SharedFile.ROOT_PARENT)
            .getOrNull()
            ?.toList()
            ?.firstOrNull()
            ?.name?.get()
}

/**
 * ⚠ `initialLoadSize` deliberately equals `pageSize`: Paging's default of `3 × pageSize` triples
 * the first-paint projection cost, and a screenful is ~15 rows.
 */
private val LISTING_PAGING_CONFIG = PagingConfig(
    pageSize = 20,
    initialLoadSize = 20,
    enablePlaceholders = false,
)

/**
 * Everything `FileMapper.map` renders for a listing row — metadata only, never `File.Preview`.
 *
 * ⚠⚠ The exclusion is **structural, not a cost**: a paging window loads through
 * `TenshuPagingSource`'s `getAll()`, a one-shot terminal. Deferred properties read as `null` on
 * one-shot terminals and never patch, and `getAll()` never materialises
 * `PendingOnDemandProperty` — so projecting `File.Preview` here, `deferred(…)` or `eager(…)`
 * alike, yields permanent nulls rather than a slow first paint. Thumbnails load per row through
 * [FilesRepository.getPreview] instead (a `Maybe.get()`, viewport-driven, four at a time).
 *
 * ⚠⚠ This projection is also the listing's **invalidation** chain (see [getFilesPaged]),
 * re-evaluated on every change to the folder — the second reason it must stay thumbnail-free: a
 * projection carrying `File.Preview` re-resolves every thumbnail on every write.
 *
 * ⚠ `PATH` is not optional: a `File`'s `DataId` derives from its path, so rows projected without
 * it diff with no identity. `Folder.FileCount` is a member-hash count; projecting the `FILES`
 * field instead ships every child entity per folder row.
 */
private fun ListBuilder<Selector<File>>.listingRow() {
    fields(File.Field.PATH)
    relations(
        File.Category,
        File.Size,
        File.CreatedAt,
        File.LastModifiedAt,
        // The row's date and the MODIFIED sort key. ⚠ A sorted listing observes the projected
        // chain (`core-ordered-window-fusion.md` §6), so a key that is not projected here is a key
        // whose changes never re-order the page.
        File.ContentModifiedAt,
        Folder.FileCount,
    )
}

/**
 * The order a sorted listing pages in — **folders first, then the "Sort by" key, then the
 * name** — as one ordered chain over the folder (`drive-sorted-folder-paging.md` §4.3/§6).
 *
 * - **Folders first is a sort key, not a client-side partition** (D5): `isA<Folder>()` descending
 *   orders every folder ahead of every file *in the chain*, so a page is a slice of the whole
 *   order rather than a grouping of its own 20 rows. ⚠ Its direction is fixed — folders stay on
 *   top when the user flips the sort.
 * - **The user's key** is a value property (`File.ContentModifiedAt` / `CreatedAt` / `Size`), or
 *   the name, in the chosen direction.
 * - ⚠ **"Modified" is the file's own date, `File.ContentModifiedAt` — never `LastModifiedAt`**,
 *   which is "last touched in the realm" and would order a library by the day it was backed up
 *   while every row shows the year it was taken. A row without the property (a folder; a file no
 *   backfill has reached) has no key: it sorts after every dated row ascending, before them
 *   descending, and among its own kind by `LastModifiedAt` — the date a folder shows.
 * - **The name is `path.lowercase()`** (D4), both as the Name key and as every other key's
 *   tie-break. ⚠ Siblings share a parent prefix, so it orders like `name.lowercase()`, and
 *   the fold is explicit at the call site rather than engine policy
 *   (`core-operation-dispatch.md` §4). Raw `path` fuses too but is case-sensitive.
 *
 * ⚠⚠ Every key here must be one that `core-ordered-window-fusion.md` §8 fuses. A derived property
 * or an op with parameters is still *correct* but silently drops the page to a full sort;
 * `FilesListingEfficiencyTest` is what pins the fused cost.
 */
private fun TList<File>.listingOrder(sort: FileSort): TList<File> {
    val foldersFirst = sortByDescending { isA<Folder>() }
    return when (sort.key) {
        FileSortKey.NAME ->
            if (sort.descending) foldersFirst.thenByDescending { path.lowercase() }
            else foldersFirst.thenBy { path.lowercase() }
        FileSortKey.MODIFIED ->
            foldersFirst
                .thenByProperty(File.ContentModifiedAt, sort.descending)
                // A folder has no content date, and the date it shows as "Modified" is
                // `LastModifiedAt` ([FileMapper.mapDetails]) — so that is what orders folders
                // among themselves. ⚠ Not `CreatedAt`: a move or rename leaves that alone.
                .thenByProperty(File.LastModifiedAt, sort.descending)
        FileSortKey.CREATED -> foldersFirst.thenByProperty(File.CreatedAt, sort.descending)
        FileSortKey.SIZE -> foldersFirst.thenByProperty(File.Size, sort.descending)
    }.let { keyed ->
        if (sort.key == FileSortKey.NAME) keyed else keyed.thenBy { path.lowercase() }
    }
}

private fun TList<File>.thenByProperty(property: Property<*>, descending: Boolean): TList<File> =
    if (descending) thenByDescending(property) else thenBy(property)

/**
 * Descend into the given parent folder (empty string or `"/"` = root),
 * one path segment per hop.
 *
 * Segment-wise on purpose: each `search` is then a **direct-child** key
 * lookup, which the proxy's by-key fast path serves as a point read (the
 * membership probe hits at every level). A single deep search
 * (`search { path equalTo "/a/b/c" }` against the root list) always misses
 * the top-level membership check and falls back to the recursive scan —
 * reading every intermediate folder array on each call, which is exactly
 * the per-row cost the fast path exists to kill.
 */
private fun TList<File>.navigateTo(parentPath: String): TList<File> {
    val parent = parentPath.ifEmpty { "/" }
    if (parent == "/") return this
    var list = this
    var prefix = ""
    for (segment in parent.trimStart('/').split('/')) {
        prefix = "$prefix/$segment"
        val level = prefix
        list = list.search<Folder> { path equalTo level }.ifEmptyError().files
    }
    return list
}

/** How many of the newest notifications [TenshuFilesRepository.changesLost] looks through. */
private const val CHANGE_LOST_WINDOW = 50
