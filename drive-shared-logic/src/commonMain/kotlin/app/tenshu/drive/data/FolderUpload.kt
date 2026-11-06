package app.tenshu.drive.data

import kotlinx.coroutines.CancellationException
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TaskHandle

/*
 * Types for client-orchestrated recursive folder upload.
 *
 * See `docs/proposals/drive-folder-upload.md`. The walk runs in the Drive
 * process (forced by SAF — the proxy cannot enumerate the tree grant) and
 * reuses the existing single-file upload path per file.
 */

/** Progress for the ambient status bar. No byte-% — uploads are serial and
 *  per-file cost is lumpy, so [completed]/[total] is the only smooth signal. */
data class UploadProgress(
    val currentFileName: String,
    val completed: Int,
    val total: Int,
    /** The current file is an online-only cloud placeholder (OneDrive / iCloud /
     *  Dropbox) being downloaded before upload — the UI says "Downloading from the
     *  cloud…" so the pause on it doesn't read as a freeze (drive-folder-upload.md
     *  §11). */
    val isOnlineOnly: Boolean = false,
)

/** A single per-file name collision, surfaced to the user inline. */
data class FileConflict(
    val fileName: String,
    val destinationFolder: String,
)

/** Pre-flight summary of the online-only (cloud placeholder) files in a picked
 *  folder — surfaced before any upload work so the download-then-reupload cost is
 *  explicit (drive-folder-upload.md §11.5). */
data class CloudPreflight(
    val onlineOnlyCount: Int,
    val onlineOnlyBytes: Long,
)

/** The user's choice at the cloud pre-flight (drive-folder-upload.md §11.5). */
enum class CloudPreflightDecision {
    /** Download the online-only files and upload them — the default. */
    Include,

    /** Skip the online-only files; upload only what's already on disk. */
    Skip,

    /** Don't upload anything. */
    Cancel,
}

/** Per-file conflict choices. [Overwrite] replaces the existing file in place
 *  via the atomic `ReplaceFile` op (no delete/tombstone). */
sealed interface ConflictResolution {
    data object Skip : ConflictResolution
    data object KeepBoth : ConflictResolution
    data object Overwrite : ConflictResolution
}

/** Best-effort outcome of a folder upload (continues past per-file failures). */
data class FolderUploadSummary(
    val total: Int,
    val uploaded: Int,
    val skipped: Int,
    val failed: Int,
    /** Online-only cloud files (OneDrive / iCloud / Dropbox) the provider wouldn't
     *  deliver on read — an *online-only* file that failed. Counted separately from
     *  [failed] so the summary reads as a cloud-delivery problem, not a Drive
     *  failure (drive-folder-upload.md §11). */
    val cloudUnavailable: Int = 0,
)

/** Flattened folder contents produced by the platform delegate. Directories
 *  appear before the files they contain (depth-first), so the walk can
 *  create a directory before placing files into it. */
data class FolderListing(
    /** Name of the picked folder itself, e.g. "Photos". */
    val rootFolderName: String,
    val entries: List<FolderEntry>,
)

data class FolderEntry(
    /** POSIX path relative to [FolderListing.rootFolderName], no leading slash. */
    val relativePath: String,
    val isDirectory: Boolean,
    val fileName: String,
    /** Platform leaf identifier consumed by the existing single-file upload
     *  (`content://` child document URI on Android, absolute path on Desktop).
     *  Empty for directories. */
    val leafUriString: String,
    /** File size in bytes (0 for directories, or when the platform can't report
     *  it). Summed by the folder-upload pre-flight (storage-capacity-gating.md
     *  §2 Tier 3) to ask storage once before starting a long job. */
    val sizeBytes: Long = 0L,
    /** The file's bytes are NOT on disk — it's a cloud File-Provider placeholder
     *  (OneDrive / iCloud / Dropbox) that materialises on read (drive-folder-upload.md
     *  §11). Detected during enumeration; drives the "Downloading from the
     *  cloud…" progress label. Always `false` for directories and off macOS. */
    val isOnlineOnly: Boolean = false,
)

/**
 * The "keep both" candidate name: `"name (n).ext"`. Shared by the single-file
 * conflict path ([app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel])
 * and the folder-upload loop so both stay byte-identical (drive-folder-upload.md §5b).
 */
fun keepBothCandidateName(originalName: String, counter: Int): String {
    val baseName = originalName.substringBeforeLast(".")
    val extension = originalName.substringAfterLast(".", "")
    val extensionWithDot = if (extension.isNotEmpty()) ".$extension" else ""
    return "$baseName ($counter)$extensionWithDot"
}

/**
 * Await an in-flight per-file [TaskHandle], propagating folder-upload-loop
 * cancellation **cross-process** (`docs/proposals/drive-folder-upload.md` §5c).
 *
 * When [app.tenshu.drive.ui.common.folderupload.FolderUploadController.cancel]
 * cancels the loop coroutine, the suspended `await()` throws. Without the
 * explicit `handle.cancel()` only the client-side wait would unwind while the
 * Control proxy kept encrypting the file; `handle.cancel()` sends the wire
 * `cancel(taskId)` so the proxy's request job is cancelled too. Already-written
 * files stay; a later re-upload resumes via the per-file conflict path. Top-level + `internal` so the propagation is unit
 * testable without a proxy.
 */
internal suspend fun TaskHandle<Data?>.awaitOrWireCancel(): TResult<Data?> =
    try {
        await()
    } catch (c: CancellationException) {
        cancel()
        throw c
    }
