package org.tenshu.sdk.ios

import app.tenshu.error.ErrorCopyKey

import app.tenshu.liststate.ListLoadState
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import app.tenshu.ingest.backup.BackupUploadOutcome
import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.CloudPreflight
import app.tenshu.drive.data.CloudPreflightDecision
import app.tenshu.drive.data.ConflictResolution
import app.tenshu.drive.data.FileActivity
import app.tenshu.drive.data.FileConflict
import app.tenshu.drive.data.FileDetailsModel
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.FolderUploadSummary
import app.tenshu.drive.data.InboundSharedRealm
import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.ShareLinkPayload
import app.tenshu.drive.data.ShareModel
import app.tenshu.drive.data.SharedFolderEntry
import app.tenshu.drive.data.SharedFolderModel
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.drive.data.StreamingContentHandle
import app.tenshu.drive.data.UploadProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * A complete, in-memory [FilesRepository] for driving the Drive-on-iOS UI without a proxy — the test
 * double the SwiftUI layer binds in dev/preview/tests, the counterpart of the real
 * `TenshuFilesRepository` (`EpcDriveClient.assembleClient`). Both satisfy [FilesRepository], so
 * `DriveReadSurface` injects either (the seam that makes the UI testable).
 *
 * The **files listing is reactive and mutable** ([files]) — a test or preview can change folder
 * contents and the bound `FilesScreenViewModel`/presenter updates — and mutations (create/delete/
 * rename/move/upload) are **recorded** for assertions. The read surface (details, storage, shares,
 * shared-with-me) returns benign, configurable defaults; per-screen slices (full-parity binding)
 * enrich the bits they exercise (e.g. real share results when the share screen lands).
 */
class FakeFilesRepository(
    initialListing: List<FileModel> = DEFAULT_LISTING,
    private val storageStatus: StorageStatusModel? = null,
    private val detailsFor: (path: String) -> FileDetailsModel? = { null },
    private val activityFor: (path: String) -> List<FileActivity> = { emptyList() },
    private val shareLinkResult: RepositoryResult<ShareLinkPayload> =
        RepositoryResult.Success(ShareLinkPayload(shareId = "demo-share-id", urlFragment = "demo-fragment")),
    private val sharedFolders: List<SharedFolderModel> = emptyList(),
    initialShares: List<ShareModel> = emptyList(),
    private val inboundShared: List<InboundSharedRealm> = emptyList(),
) : FilesRepository {

    /**
     * ⚠⚠ **`null` is "could not read", never "no match"** — the interface's own contract. A caller
     * must then fall back to uploading, which is the non-destructive branch: treating an unreadable
     * candidate as a duplicate would silently drop the file. A fake holding no real bytes has
     * nothing to hash, so "could not read" is the honest answer as well as the safe one.
     *
     * ⚠ Added here because PH-6 widened `FilesRepository` and this implementation was missed —
     * `iosKotlinCheck` is not in the main gate, so master shipped uncompilable `iosMain`.
     */
    override suspend fun contentHashOf(uriString: String): String? = null

    /** The current folder listing; set it to drive empty / many / changed states in tests + previews. */
    val files: MutableStateFlow<List<FileModel>> = MutableStateFlow(initialListing)

    /** Outbound Mode-A link shares (reactive — revoke drops the row). */
    val sharesFlow: MutableStateFlow<List<ShareModel>> = MutableStateFlow(initialShares)

    // --- Recorded mutations (assert against these in tests). ---
    val createdFolders: MutableList<String> = mutableListOf()
    val downloaded: MutableList<String> = mutableListOf()
    val evicted: MutableList<String> = mutableListOf()
    val deletedPaths: MutableList<String> = mutableListOf()
    val renamed: MutableList<Pair<String, String>> = mutableListOf()
    val moved: MutableList<Pair<String, String>> = mutableListOf()
    val revokedShares: MutableList<String> = mutableListOf()
    val uploads: MutableList<UploadRecord> = mutableListOf()
    val sharedLinks: MutableList<String> = mutableListOf()

    data class UploadRecord(val uriString: String, val parentPath: String, val fileName: String)

    // --- Read surface (reactive / configurable). ---

    /**
     * ⚠⚠ The load states are stated, and [files] being a never-completing
     * [MutableStateFlow] is exactly why. The deprecated single-argument
     * `PagingData.from(list)` pins refresh/prepend/append in `LoadState.Loading`,
     * and anything waiting for the listing to settle then waits forever — its only
     * other exit is the upstream flow *completing*, which a reactive listing never
     * does — a sorted listing of this shape hangs its test. This fake has the same
     * shape, so it gets the same treatment.
     *
     * A fake is not exempt from this: it is what the SwiftUI layer binds in
     * dev/preview/tests, so the pinned states put a permanent "loading more"
     * indicator under every listing the iOS UI is developed against — and the
     * *whole point* of the fake is that the UI it drives looks like the real one.
     * One page, nothing to append, which is the truth for an in-memory listing.
     */
    override fun getFilesPaged(folderPath: String, sort: FileSort): Flow<PagingData<FileModel>> =
        files.map { PagingData.from(it, WHOLE_LISTING_LOAD_STATES) }

    override suspend fun getFiles(folderPath: String): Flow<List<FileModel>> = files
    override fun recentFiles(limit: Int): Flow<ListLoadState<FileModel>> =
        files.map { ListLoadState.Loaded(it) }
    override fun storageStatus(): Flow<StorageStatusModel?> = flowOf(storageStatus)
    override suspend fun hasLocalData(path: String): Boolean = false
    override suspend fun getPreview(path: String, width: Int, height: Int): ByteArray? = null
    override fun fileDetails(path: String): Flow<FileDetailsModel?> =
        flowOf(detailsFor(path) ?: defaultDetailsFor(path))
    override fun fileActivity(path: String): Flow<List<FileActivity>> = flowOf(activityFor(path))

    /** A basic detail (file header + dashes) for any listed path, so the detail screen renders. */
    private fun defaultDetailsFor(path: String): FileDetailsModel? =
        files.value.firstOrNull { it.path == path }?.let { FileDetailsModel(file = it, size = "—", created = "—", lastModified = "—") }
    /**
     * Case-insensitive **name** substring match over the fake listing — the same semantics as the
     * real `TenshuFilesRepository.searchFiles` (which matches the last path segment, not the whole
     * path, so a hit on an ancestor folder doesn't surface its unrelated descendants).
     *
     * Overridden rather than left to the interface default (`flowOf(emptyList())`, which exists so
     * test fakes need not implement it): this fake also backs SwiftUI previews and pre-runtime
     * bring-up, where a search bar that always returns nothing looks like a broken feature.
     * Reactive over [files] so mutations show up in an open search, matching production.
     */
    override fun searchFiles(query: String): Flow<ListLoadState<FileModel>> {
        val needle = query.trim()
        if (needle.isEmpty()) return flowOf(ListLoadState.Loaded(emptyList()))
        return files.map { listing ->
            ListLoadState.Loaded(listing.filter { it.name.contains(needle, ignoreCase = true) })
        }
    }

    override fun shares(): Flow<ListLoadState<ShareModel>> =
        sharesFlow.map { ListLoadState.Loaded(it) }
    override suspend fun getRemoteFile(path: String): RepositoryResult<ByteArray> =
        RepositoryResult.Success(ByteArray(0))

    override fun getFileName(uriString: String): String =
        uriString.substringAfterLast('/').substringBefore('?').ifEmpty { "file" }

    // --- Mutations (recorded; reflected in [files] so the UI reacts). ---

    override suspend fun uploadFile(uriString: String, parentPath: String, fileName: String): RepositoryResult<Unit> {
        val path = "${parentPath.trimEnd('/')}/$fileName"
        // `UploadFileImplementation` rejects an occupied target path (unless told to overwrite) —
        // model it, or the upload conflict and its *Keep both* remedy never happen in a test or
        // preview. Recorded only once accepted, so `uploads` stays a log of real writes.
        if (files.value.any { it.path == path }) {
            return RepositoryResult.Error(RepositoryError.AlreadyExists)
        }
        uploads += UploadRecord(uriString, parentPath, fileName)
        files.value = files.value + FileModel.File(
            path = path,
            name = fileName,
            subLine = "just now",
            previewIcon = FileIcon.Document,
        )
        return RepositoryResult.Success(Unit)
    }

    override suspend fun createFolder(path: String): RepositoryResult<Unit> {
        // `CreateFolderImplementation` rejects a path that already exists — model it, or the
        // dialog's collision branch (and its *Keep both* remedy) is unreachable in tests and
        // previews. Recorded only once accepted, so `createdFolders` stays a log of real writes.
        if (files.value.any { it.path == path }) {
            return RepositoryResult.Error(RepositoryError.AlreadyExists)
        }
        createdFolders += path
        files.value = files.value + FileModel.Folder(
            path = path,
            name = path.substringAfterLast('/'),
            subLine = "0 files",
            previewIcon = FileIcon.Folder,
        )
        return RepositoryResult.Success(Unit)
    }

    override suspend fun deleteFile(path: String): RepositoryResult<Unit> {
        deletedPaths += path
        files.value = files.value.filterNot { it.path == path }
        return RepositoryResult.Success(Unit)
    }

    override suspend fun renameFile(path: String, newName: String): RepositoryResult<String> {
        val parentPath = path.substringBeforeLast("/")
        val newPath = if (parentPath.isEmpty()) "/$newName" else "$parentPath/$newName"
        // `MoveFileImplementation` rejects a target that already exists — model it, or the UI's
        // refusal path is unreachable in tests and previews. Recorded only once it is accepted,
        // so `renamed` stays a log of writes that happened.
        if (files.value.any { it.path == newPath }) {
            return RepositoryResult.Error(RepositoryError.AlreadyExists)
        }
        renamed += path to newName
        // Re-key the listing entry, not just the record: a rename is a `MoveFile`, so the old
        // path stops resolving and the new one starts. A fake that only recorded the call let a
        // host keep reading the old path successfully — hiding exactly the staleness the real
        // repository produces (drive-ios's detail screen sat on a dead path after a rename).
        files.value = files.value.map { model ->
            if (model.path != path) model else when (model) {
                is FileModel.File -> model.copy(path = newPath, name = newName)
                else -> FileModel.Folder(
                    path = newPath,
                    name = newName,
                    subLine = model.subLine,
                    previewIcon = model.previewIcon,
                    previewBytes = model.previewBytes,
                )
            }
        }
        return RepositoryResult.Success(newPath)
    }

    override suspend fun moveFile(sourcePath: String, destinationFolderPath: String): RepositoryResult<Unit> {
        moved += sourcePath to destinationFolderPath
        files.value = files.value.filterNot { it.path == sourcePath }
        return RepositoryResult.Success(Unit)
    }

    override suspend fun downloadFile(path: String): RepositoryResult<Unit> {
        downloaded += path
        return RepositoryResult.Success(Unit)
    }

    override suspend fun evictFile(path: String): RepositoryResult<Unit> {
        evicted += path
        return RepositoryResult.Success(Unit)
    }

    override suspend fun uploadForBackup(parentPath: String, fileName: String, uriString: String): BackupUploadOutcome =
        BackupUploadOutcome.Skipped

    override suspend fun uploadFolder(
        uriString: String,
        folderPath: String,
        onProgress: (UploadProgress) -> Unit,
        onConflict: suspend (FileConflict) -> ConflictResolution,
        onCloudPreflight: suspend (CloudPreflight) -> CloudPreflightDecision,
    ): RepositoryResult<FolderUploadSummary> = notConfigured("uploadFolder")

    // --- Share cluster: benign defaults; per-screen slices configure what they bind. ---

    override suspend fun shareLink(path: String, expiresAtMillis: Long, maxOpens: Int): RepositoryResult<ShareLinkPayload> {
        sharedLinks += path
        return shareLinkResult
    }
    override suspend fun revokeShareLink(shareId: String): RepositoryResult<Unit> {
        revokedShares += shareId
        sharesFlow.value = sharesFlow.value.filterNot { it.shareId == shareId }
        return RepositoryResult.Success(Unit)
    }
    override suspend fun shareFolder(folderPath: String): RepositoryResult<SharedFolderModel> = notConfigured("shareFolder")
    override suspend fun sharedFolders(): RepositoryResult<List<SharedFolderModel>> = RepositoryResult.Success(sharedFolders)
    override suspend fun browseSharedFolder(realmId: String, parentFileId: String): RepositoryResult<List<SharedFolderEntry>> =
        RepositoryResult.Success(emptyList())
    override suspend fun readSharedFile(realmId: String, fileId: String): RepositoryResult<ByteArray> =
        RepositoryResult.Success(ByteArray(0))
    override suspend fun sharedWithMe(): RepositoryResult<List<InboundSharedRealm>> = RepositoryResult.Success(inboundShared)

    // Streaming preview (D2b) has no canned source — the fake can't decrypt real chunks. Screens that
    // need it drive a real client; over the fake it's simply not configured.
    override suspend fun openStreamingContent(path: String): RepositoryResult<StreamingContentHandle> =
        notConfigured("openStreamingContent")

    private fun notConfigured(name: String): RepositoryResult<Nothing> =
        RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED))

    companion object {
        /** Settled states for a whole-listing page: refresh done, neither end can append. */
        private val WHOLE_LISTING_LOAD_STATES = LoadStates(
            refresh = LoadState.NotLoading(endOfPaginationReached = true),
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.NotLoading(endOfPaginationReached = true),
        )

        /** The default sample folder — a few entries so the Files list renders immediately. */
        val DEFAULT_LISTING: List<FileModel> = listOf(
            FileModel.Folder(path = "/Photos", name = "Photos", subLine = "12 files", previewIcon = FileIcon.Folder),
            FileModel.File(path = "/sunset.jpg", name = "sunset.jpg", subLine = "1.2 MB · Jun 12", previewIcon = FileIcon.Image),
            FileModel.File(path = "/notes.txt", name = "notes.txt", subLine = "4 KB · Jun 14", previewIcon = FileIcon.Document),
            FileModel.File(path = "/budget.pdf", name = "budget.pdf", subLine = "88 KB · Jun 15", previewIcon = FileIcon.Pdf),
        )
    }
}
