package app.tenshu.drive.ui

import kotlinx.coroutines.currentCoroutineContext
import app.tenshu.error.ErrorCopyKey

import app.tenshu.liststate.ListLoadState
import app.tenshu.ingest.backup.BackupUploadOutcome
import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.CloudPreflight
import app.tenshu.drive.data.CloudPreflightDecision
import app.tenshu.drive.data.ConflictResolution
import app.tenshu.drive.data.FileActivity
import app.tenshu.drive.data.FileConflict
import app.tenshu.drive.data.FileDetailsModel
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.FolderUploadSummary
import app.tenshu.drive.data.InboundSharedRealm
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.ShareLinkPayload
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.drive.data.ShareModel
import app.tenshu.drive.data.SharedFolderEntry
import app.tenshu.drive.data.SharedFolderModel
import app.tenshu.drive.data.StreamingContentHandle
import app.tenshu.drive.data.UploadProgress
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Shared test double driving `uploadFolder` via an injected [behavior] block
 * (emit progress / surface conflicts inline). [fileDetailsResult] backs
 * [fileDetails] for the FileDetailsScreen tests. Used by
 * [FolderUploadControllerTest] (loop behaviour) and
 * [AddFileActionsViewModelFolderUploadTest] (the ViewModel mirror/delegate
 * contract).
 */
internal class FakeFilesRepository(
    private val fileDetailsResult: FileDetailsModel? = null,
    private val fileActivityResult: List<FileActivity> = emptyList(),
    private val sharedFoldersResult: List<SharedFolderModel> = emptyList(),
    // Backs [browseSharedFolder]: maps parentFileId → its children (single-realm
    // tests). [readSharedFileBytes] backs [readSharedFile].
    private val browseResults: Map<String, List<SharedFolderEntry>> = emptyMap(),
    private val readSharedFileBytes: ByteArray? = null,
    private val sharedWithMeResult: List<InboundSharedRealm> = emptyList(),
    // Backs [getPreview]: `null` (default) means "no preview" (icon fallback);
    // non-null bytes drive the icon→image swap. [onPreviewRequested] lets a
    // test assert which size the caller asked for.
    private val previewBytes: ByteArray? = null,
    private val onPreviewRequested: (path: String, width: Int, height: Int) -> Unit = { _, _, _ -> },
    private val storageStatusResult: StorageStatusModel? = null,
    // Backs [getRemoteFile]: bytes for the whole-file (D2a) path, or `null` → not stubbed.
    private val remoteFileBytes: ByteArray? = null,
    // Backs [openStreamingContent] (D2b): the result every call returns, or `null` → not stubbed.
    private val streamingResult: RepositoryResult<StreamingContentHandle>? = null,
    // Backs [moveFile]: the result every call returns (default success).
    private val moveResult: RepositoryResult<Unit> = RepositoryResult.Success(Unit),
    // Backs [uploadFile]: the result every single-file upload returns (default success).
    private val uploadResult: RepositoryResult<Unit> = RepositoryResult.Success(Unit),
    // Per-candidate-name outcome, for the *Keep both* loop (which re-uploads under "name (1)",
    // "name (2)", …). Takes precedence over [uploadResult] when set.
    private val uploadResultFor: ((fileName: String) -> RepositoryResult<Unit>)? = null,
    // Backs [uploadForBackup]: outcome per file name (default Uploaded).
    private val backupOutcome: (fileName: String) -> BackupUploadOutcome = { BackupUploadOutcome.Uploaded },
    private val behavior: suspend (
        onProgress: (UploadProgress) -> Unit,
        onConflict: suspend (FileConflict) -> ConflictResolution,
        onCloudPreflight: suspend (CloudPreflight) -> CloudPreflightDecision,
    ) -> RepositoryResult<FolderUploadSummary> = { _, _, _ -> error("uploadFolder not stubbed") },
) : FilesRepository {

    /** Records each [shareFolder] call so tests can assert the producer wiring. */
    val shareFolderCalls = mutableListOf<String>()

    override suspend fun uploadFolder(
        uriString: String,
        folderPath: String,
        onProgress: (UploadProgress) -> Unit,
        onConflict: suspend (FileConflict) -> ConflictResolution,
        onCloudPreflight: suspend (CloudPreflight) -> CloudPreflightDecision,
    ): RepositoryResult<FolderUploadSummary> = behavior(onProgress, onConflict, onCloudPreflight)

    /** Records each [uploadFile] call as (uriString, parentPath, fileName). */
    val uploadCalls = mutableListOf<Triple<String, String, String>>()

    override suspend fun uploadFile(uriString: String, parentPath: String, fileName: String):
        RepositoryResult<Unit> {
        uploadCalls += Triple(uriString, parentPath, fileName)
        return uploadResultFor?.invoke(fileName) ?: uploadResult
    }

    /** Records each [ensureBackupDestination] call's parentPath. */
    val ensureBackupDestinationCalls = mutableListOf<String>()

    override suspend fun ensureBackupDestination(parentPath: String) {
        ensureBackupDestinationCalls += parentPath
    }

    /** How often the "sync now over cellular" one-shot was asked for (also what a metered override maps to). */
    var syncNowCalls = 0

    override suspend fun syncNow() {
        syncNowCalls++
    }

    /**
     * The [uploadForBackup] calls made inside `userInitiatedWork { }` — the uploads Control may hold
     * its foreground service for (`control-background-work-off-fgs.md` §5.1).
     */
    val userStartedBackupUploads = mutableListOf<String>()

    /** Records each [uploadForBackup] call as (parentPath, fileName, uriString). */
    val backupUploadCalls = mutableListOf<Triple<String, String, String>>()

    /**
     * What [contentHashOf] answers per uri. ⚠ Absent by default, so the fake behaves like a
     * device whose bytes could not be read — which the drain must treat as "upload it".
     */
    val contentHashes = mutableMapOf<String, String>()

    /** Every uri the drain asked about — so a test can prove it did *not* ask. */
    val contentHashCalls = mutableListOf<String>()

    override suspend fun contentHashOf(uriString: String): String? {
        contentHashCalls += uriString
        return contentHashes[uriString]
    }

    override suspend fun uploadForBackup(
        parentPath: String,
        fileName: String,
        uriString: String,
    ): BackupUploadOutcome {
        // ⚠ A real upload suspends on I/O, which is what lets the controller's collector
        // coroutines (state aggregation, status reporting) run between files. Without this
        // the drain holds the test dispatcher end to end and every intermediate frame is
        // conflated away — so a test cannot see the progress the user actually watches.
        yield()
        backupUploadCalls += Triple(parentPath, fileName, uriString)
        if (currentCoroutineContext()[org.tenshu.core.UserInitiatedElement] != null) userStartedBackupUploads += fileName
        return backupOutcome(fileName)
    }

    /**
     * Backs [getFiles] (and thus the default [FilesRepository.listFilesRecursively]
     * walk): folder path → its immediate children. Absent folder → empty listing.
     */
    var filesByFolder: Map<String, List<FileModel>> = emptyMap()

    override suspend fun getFiles(folderPath: String): Flow<List<FileModel>> =
        filesByFolder[folderPath]?.let { flowOf(it) } ?: emptyFlow()

    /** Backs [searchFiles]: trimmed query → matching rows. Absent query → empty. */
    var searchResultsByQuery: Map<String, List<FileModel>> = emptyMap()

    override fun searchFiles(query: String): Flow<ListLoadState<FileModel>> =
        flowOf(ListLoadState.Loaded(searchResultsByQuery[query.trim()] ?: emptyList()))

    override fun recentFiles(limit: Int): Flow<ListLoadState<FileModel>> = emptyFlow()
    override fun getFilesPaged(folderPath: String, sort: FileSort): Flow<PagingData<FileModel>> = emptyFlow()
    override suspend fun createFolder(path: String) = error("unused")
    /** Records each [deleteFile] call so "did the delete actually run?" is assertable. */
    val deleteFileCalls = mutableListOf<String>()

    override suspend fun deleteFile(path: String): RepositoryResult<Unit> {
        deleteFileCalls += path
        return RepositoryResult.Success(Unit)
    }

    /** Records each [downloadFile] / [evictFile] call so the offline toggle is assertable. */
    val downloadFileCalls = mutableListOf<String>()
    val evictFileCalls = mutableListOf<String>()

    override suspend fun downloadFile(path: String): RepositoryResult<Unit> {
        downloadFileCalls += path
        return RepositoryResult.Success(Unit)
    }

    /**
     * Set to make [evictFile] refuse — the upload journal answers `ContentPinnedError` while an
     * unsent upload's bytes are the only copy, so eviction is not an operation that cannot fail.
     */
    var evictFileResult: RepositoryResult<Unit> = RepositoryResult.Success(Unit)

    override suspend fun evictFile(path: String): RepositoryResult<Unit> {
        evictFileCalls += path
        return evictFileResult
    }

    /** Paths the fake reports as having local bytes (the presence axis). */
    val localDataPaths: MutableSet<String> = mutableSetOf()

    override suspend fun hasLocalData(path: String): Boolean = path in localDataPaths

    override suspend fun renameFile(path: String, newName: String) = error("unused")

    /** Records each [moveFile] call as (sourcePath, destinationFolderPath). */
    val moveCalls = mutableListOf<Pair<String, String>>()

    override suspend fun moveFile(
        sourcePath: String,
        destinationFolderPath: String,
    ): RepositoryResult<Unit> {
        moveCalls += sourcePath to destinationFolderPath
        return moveResult
    }
    /** Records each [getRemoteFile] / [openStreamingContent] call path. */
    val getRemoteFileCalls = mutableListOf<String>()
    val openStreamingCalls = mutableListOf<String>()

    override suspend fun getRemoteFile(path: String): RepositoryResult<ByteArray> {
        getRemoteFileCalls += path
        return remoteFileBytes?.let { RepositoryResult.Success(it) } ?: error("getRemoteFile not stubbed")
    }

    override suspend fun openStreamingContent(path: String): RepositoryResult<StreamingContentHandle> {
        openStreamingCalls += path
        return streamingResult ?: error("openStreamingContent not stubbed")
    }
    override suspend fun getPreview(path: String, width: Int, height: Int): ByteArray? {
        onPreviewRequested(path, width, height)
        return previewBytes
    }
    override fun fileDetails(path: String): Flow<FileDetailsModel?> = flowOf(fileDetailsResult)
    override fun fileActivity(path: String): Flow<List<FileActivity>> = flowOf(fileActivityResult)
    // The uri's last segment, as every real delegate resolves it. Returning null made the view
    // model fall back to "Unknown" for every upload, which hid the *Keep both* candidate naming
    // (`keepBothCandidateName` splits on the extension) behind an extension-less placeholder.
    override fun getFileName(uriString: String): String? =
        uriString.substringAfterLast('/').ifEmpty { null }

    override suspend fun shareLink(
        path: String,
        expiresAtMillis: Long,
        maxOpens: Int,
    ): RepositoryResult<ShareLinkPayload> = error("shareLink not stubbed")

    override suspend fun revokeShareLink(shareId: String): RepositoryResult<Unit> =
        error("revokeShareLink not stubbed")

    override fun shares(): Flow<ListLoadState<ShareModel>> = emptyFlow()

    override fun storageStatus(): Flow<StorageStatusModel?> = flowOf(storageStatusResult)

    override suspend fun shareFolder(folderPath: String): RepositoryResult<SharedFolderModel> {
        shareFolderCalls += folderPath
        val name = folderPath.substringAfterLast('/').ifEmpty { folderPath }
        return RepositoryResult.Success(
            SharedFolderModel(sharedRealmId = "realm-$name", name = name, parentPath = "/")
        )
    }

    override suspend fun sharedFolders(): RepositoryResult<List<SharedFolderModel>> =
        RepositoryResult.Success(sharedFoldersResult)

    /** Records each [browseSharedFolder] call as (realmId, parentFileId). */
    val browseCalls = mutableListOf<Pair<String, String>>()

    /** Records each [readSharedFile] call as (realmId, fileId). */
    val readSharedFileCalls = mutableListOf<Pair<String, String>>()

    override suspend fun browseSharedFolder(
        realmId: String,
        parentFileId: String,
    ): RepositoryResult<List<SharedFolderEntry>> {
        browseCalls += realmId to parentFileId
        return RepositoryResult.Success(browseResults[parentFileId] ?: emptyList())
    }

    override suspend fun readSharedFile(realmId: String, fileId: String): RepositoryResult<ByteArray> {
        readSharedFileCalls += realmId to fileId
        return readSharedFileBytes
            ?.let { RepositoryResult.Success(it) }
            ?: RepositoryResult.Error(app.tenshu.drive.data.RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED))
    }

    override suspend fun sharedWithMe(): RepositoryResult<List<InboundSharedRealm>> =
        RepositoryResult.Success(sharedWithMeResult)
}
