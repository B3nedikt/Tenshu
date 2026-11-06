package app.tenshu.drive.data

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TaskHandle
import org.tenshu.core.common.TenshuClient

/**
 * The per-platform half of [FilesRepository]: turns the URI/path a picker handed back into the
 * bytes (or the enumeration) the shared upload paths need.
 *
 * ⚠⚠ **These methods may throw, and that is part of the contract.** The byte-reading ones fail on
 * a source that cannot be read at all — an iOS document *package* (`.key` / `.pages` / `.numbers`
 * are directories), a desktop directory, a lapsed content grant, a file the user deleted
 * mid-drain — and they signal it by throwing, not by returning a failed `TResult`. The read
 * happens while evaluating the **arguments** of the upload op, so there is no `TResult` yet to
 * fail. [TenshuFilesRepository] is the single boundary that converts such a throw into a
 * [RepositoryError]; do NOT rely on `fold(onFailure = ...)` seeing it, and do not add a second
 * catch in a ViewModel. An uncaught one abort()s the app on Kotlin/Native.
 */
interface FilesRepositoryPlatformDelegate {

    fun getPlatformFileName(uriString: String): String?

    /**
     * Enumerate a picked folder into a flat, depth-first listing (directories
     * before the files they contain). Runs in the Drive process — the proxy
     * cannot enumerate the SAF tree grant. See `docs/proposals/drive-folder-upload.md`.
     */
    suspend fun listFolderEntries(uriString: String): FolderListing

    suspend fun uploadPlatformFile(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TResult<Data?>

    /** In-place overwrite of an existing file (the "Overwrite" conflict
     *  choice). Same platform shape as [uploadPlatformFile] but routes to the
     *  atomic `ReplaceFile` op — no delete, no tombstone. */
    suspend fun replacePlatformFile(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TResult<Data?>

    /**
     * Folder-upload (Phase 3a) counterpart of [uploadPlatformFile]: routes to
     * the `BackgroundOperation` upload-op variant so the returned [TaskHandle]
     * can wire-cancel the **in-flight** per-file encrypt in the Control proxy
     * (Phase L2), not merely stop the loop between files. The single-file
     * upload path keeps using [uploadPlatformFile]. See
     * `docs/proposals/drive-folder-upload.md` §3a.
     */
    suspend fun uploadPlatformFileCancellable(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TaskHandle<Data?>

    /**
     * The whole-file plaintext hash of the bytes behind [uriString], or `null` when they could not
     * be read — the question an ingest-time duplicate skip asks *before* committing an upload
     * (`photos-app.md` PH-6 / PH-G3).
     *
     * ⚠⚠ It MUST be byte-identical to what `UploadFileImplementation` stamps on `File.contentHash`,
     * which means `IncrementalSha256` folded at exactly `FileContentChunkSizeBytes`. A second
     * construction that merely looks like a hash would compare unequal against every stored value,
     * and the symptom is not a crash — it is a skip that never fires and a setting that quietly
     * does nothing. `PlatformContentHash` exists so no implementation has to get this right twice.
     *
     * ⚠ `null` means *"could not read"*, never *"no match"*. A caller must fall back to uploading:
     * treating an unreadable candidate as a duplicate would drop it silently.
     *
     * ⚠ This reads the bytes a second time — the upload will read them again. Local I/O against a
     * file the device already holds, paid only when the skip setting is on, and cheaper than the
     * upload it may avoid.
     */
    suspend fun contentHashOf(uriString: String): String?

    /** Cancellable (Phase 3a) counterpart of [replacePlatformFile]; see
     *  [uploadPlatformFileCancellable]. */
    suspend fun replacePlatformFileCancellable(
        client: TenshuClient,
        parentPath: String,
        fileName: String,
        uriString: String
    ): TaskHandle<Data?>
}
