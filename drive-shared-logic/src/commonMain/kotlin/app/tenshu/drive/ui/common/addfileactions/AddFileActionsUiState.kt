package app.tenshu.drive.ui.common.addfileactions

import app.tenshu.drive.data.CloudPreflight
import app.tenshu.drive.data.FileConflict
import app.tenshu.drive.data.FolderUploadSummary
import app.tenshu.drive.data.UploadProgress
import app.tenshu.drive.domain.StorageGateDecision

data class AddFileActionsUiState(
    val showAddFolder: Boolean = false,
    val showAddFileActions: Boolean = false,
    val uploadConflict: UploadConflict? = null,
    val folderUpload: FolderUploadState? = null,
    /**
     * Non-null when a single-file upload was blocked by the Tier-2 quota gate —
     * a subscribed user over their paid quota ([StorageGateDecision.Full]). Local-first
     * (ADR 0019): a *missing* subscription never blocks an upload, so this is only
     * ever [StorageGateDecision.Full], never `NeedsStorage`. The screens render
     * `StorageGateDialog` when set.
     */
    val storageGateBlock: StorageGateDecision? = null,
    /**
     * A single-file upload failed for a reason the user has no dialog for otherwise — a stable
     * token (see [AddFileActionsViewModel]'s companion) the screen resolves to localised copy per
     * ADR 0016.
     *
     * ⚠ Every failure that is neither a name collision nor the quota gate used to fall into an
     * `else -> Unit`: the picked file simply never appeared, with nothing said. Same for a
     * *Keep both* that ran out of candidate names or hit a non-collision error partway through.
     */
    val uploadError: String? = null,
)

data class UploadConflict(
    val uriString: String,
    val parentPath: String,
    val fileName: String
)

/** Non-null while a folder upload is in flight or briefly after it ends.
 *  [conflict] non-null ⇒ the per-file conflict dialog is shown and the loop
 *  is suspended. [outcome] non-null ⇒ the upload finished; the bar shows a
 *  terminal summary instead of progress, then auto-dismisses. */
data class FolderUploadState(
    val progress: UploadProgress,
    val conflict: FileConflict? = null,
    /** Set while the cloud pre-flight prompt is awaiting the user's Include /
     *  Skip / Cancel choice (drive-folder-upload.md §11 P2b). */
    val cloudPreflight: CloudPreflight? = null,
    val outcome: FolderUploadOutcome? = null,
)

/**
 * Terminal result of a folder upload, shown briefly in the ambient bar then
 * auto-dismissed. Phase 2 reuses the bar rather than a Snackbar — the app has
 * no `SnackbarHost` and Phase 2 adds no new platform surface (see
 * `docs/proposals/drive-folder-upload.md` §5; deviation from the §5
 * "snackbar" wording).
 */
sealed interface FolderUploadOutcome {
    data class Completed(val summary: FolderUploadSummary) : FolderUploadOutcome
    data class Cancelled(val partial: UploadProgress) : FolderUploadOutcome
    data object Failed : FolderUploadOutcome

    /**
     * Blocked by the Tier-3 storage pre-flight before any file was uploaded
     * (`docs/proposals/storage-capacity-gating.md` §2). Distinct from [Failed]
     * so the bar can show a "not enough storage" message.
     */
    data object InsufficientStorage : FolderUploadOutcome
}
