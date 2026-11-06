package app.tenshu.drive.ui.common.folderupload

import app.tenshu.drive.data.CloudPreflightDecision
import app.tenshu.drive.data.ConflictResolution
import app.tenshu.drive.data.FileConflict
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.UploadProgress
import app.tenshu.drive.ui.common.addfileactions.FolderUploadOutcome
import app.tenshu.drive.ui.common.addfileactions.FolderUploadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * App-scoped owner of the client-orchestrated folder-upload loop
 * (`docs/proposals/drive-folder-upload.md` §5c).
 *
 * Progress and the cross-process cancel lever must **outlive the request**, so
 * the loop runs on this controller's [scope], a process-lifetime scope owned by
 * a Koin `single` — not on a ViewModel scope, which dies with the Activity.
 * `AddFileActionsViewModel` is a thin observer of [state] that forwards user
 * actions here, and a freshly recreated ViewModel immediately re-attaches to an
 * in-flight upload.
 *
 * **Honest platform boundary.** On Desktop the proxy is the resident in-process
 * daemon, so an in-flight upload genuinely survives the window closing.
 * On Android the loop + SAF enumeration run in the **Drive** process while the
 * heavy per-file encrypt runs in **Control**; this controller surviving the
 * ViewModel does *not* by itself stop the OS reclaiming a backgrounded Drive
 * process — Control's foreground service keeps only Control alive
 * (`core-background-processing.md` §8, "L2-LIVE is necessary but not
 * sufficient"). What this delivers on Android: the upload is detached from the
 * UI lifecycle, the persisted SAF grant outlives a transient teardown, and
 * cancel reaches the in-flight per-file work in Control cross-process.
 */
class FolderUploadController(
    private val filesRepository: FilesRepository,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _state = MutableStateFlow<FolderUploadState?>(null)
    val state: StateFlow<FolderUploadState?> = _state.asStateFlow()

    private var blanketChoice: ConflictResolution? = null
    private var pendingConflict: CompletableDeferred<ConflictResolution>? = null
    private var pendingPreflight: CompletableDeferred<CloudPreflightDecision>? = null
    private var uploadJob: Job? = null
    private var dismissJob: Job? = null

    /**
     * Start (or restart) a folder upload. Files already uploaded by a previous
     * run stay on disk; a restart re-enumerates and the already-present files
     * surface as ordinary per-file conflicts (Skip / Keep both / Overwrite) —
     * cooperative resume, no extra checkpoint state. A second call while one is
     * in flight replaces it (the prior loop is cancelled, in-flight per-file
     * work wire-cancelled in the proxy).
     */
    fun uploadFolder(uriString: String, parentPath: String) {
        dismissJob?.cancel()
        uploadJob?.cancel()
        uploadJob = scope.launch {
            blanketChoice = null
            pendingConflict = null
            pendingPreflight = null
            var cancelledAtPreflight = false
            _state.value = FolderUploadState(UploadProgress("", 0, 0))

            val result = filesRepository.uploadFolder(
                uriString = uriString,
                folderPath = parentPath,
                onProgress = { progress ->
                    _state.update { current ->
                        (current ?: FolderUploadState(progress)).copy(progress = progress)
                    }
                },
                onConflict = { conflict ->
                    blanketChoice ?: run {
                        val deferred = CompletableDeferred<ConflictResolution>()
                        pendingConflict = deferred
                        _state.update { it?.copy(conflict = conflict) }
                        val resolution = deferred.await()
                        pendingConflict = null
                        _state.update { it?.copy(conflict = null) }
                        resolution
                    }
                },
                onCloudPreflight = { preflight ->
                    val deferred = CompletableDeferred<CloudPreflightDecision>()
                    pendingPreflight = deferred
                    _state.update { it?.copy(cloudPreflight = preflight) }
                    val decision = deferred.await()
                    pendingPreflight = null
                    _state.update { it?.copy(cloudPreflight = null) }
                    if (decision == CloudPreflightDecision.Cancel) cancelledAtPreflight = true
                    decision
                },
            )

            uploadJob = null
            if (cancelledAtPreflight) {
                // Backed out before any work — dismiss the bar, no summary.
                _state.value = null
            } else {
                // Cancellation never reaches here — the loop throws at a suspension
                // point and `cancel()` sets the Cancelled outcome itself.
                val outcome = when (result) {
                    is RepositoryResult.Success -> FolderUploadOutcome.Completed(result.value)
                    is RepositoryResult.Error ->
                        if (result.error is RepositoryError.InsufficientStorage) {
                            FolderUploadOutcome.InsufficientStorage
                        } else {
                            FolderUploadOutcome.Failed
                        }
                }
                _state.update {
                    (it ?: FolderUploadState(UploadProgress("", 0, 0)))
                        .copy(conflict = null, outcome = outcome)
                }
                scheduleDismiss()
            }
        }
    }

    /**
     * Cancel an in-flight upload. Stops the loop and, via the
     * cancel-propagating await in `TenshuFilesRepository.uploadFolder`,
     * wire-cancels the in-flight per-file op in the Control proxy —
     * not merely "stop between files". Already-uploaded files stay.
     */
    fun cancel() {
        val job = uploadJob ?: return
        uploadJob = null
        job.cancel()
        pendingConflict?.cancel()
        pendingConflict = null
        pendingPreflight?.cancel()
        pendingPreflight = null
        val last = _state.value?.progress ?: UploadProgress("", 0, 0)
        _state.value = FolderUploadState(last, outcome = FolderUploadOutcome.Cancelled(last))
        scheduleDismiss()
    }

    fun resolveConflict(resolution: ConflictResolution, applyToAll: Boolean) {
        if (applyToAll) blanketChoice = resolution
        pendingConflict?.complete(resolution)
    }

    /** The user's answer to the cloud pre-flight prompt (drive-folder-upload.md §11.5): Include the
     *  online-only files (download + upload), Skip them, or Cancel the upload. */
    fun resolvePreflight(decision: CloudPreflightDecision) {
        pendingPreflight?.complete(decision)
    }

    fun dismiss() {
        dismissJob?.cancel()
        _state.value = null
    }

    private fun scheduleDismiss() {
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(SUMMARY_MS)
            _state.update { if (it?.outcome != null) null else it }
        }
    }

    private companion object {
        const val SUMMARY_MS = 5000L
    }
}
