package app.tenshu.control.ui.screens.settings.errors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.ErrorEventSummary
import app.tenshu.control.data.ErrorEventsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.logging.common.ErrorReport
import org.tenshu.logging.common.ErrorReportUploader

/**
 * Phase 5 of `docs/proposals/core-error-channel.md`: Settings → Errors
 * viewmodel. Collects [ErrorEventsManager.events] into [uiState] and tracks
 * which row is expanded for detail.
 *
 * Phase 6 layered on top: drives the "Send error report" CTA (confirm
 * dialog → [ErrorReportUploader.upload] → success/error feedback). The
 * upload is always user-initiated, anonymous, and one-shot — no retry
 * here; the user can tap again on failure.
 */
class ErrorsViewModel(
    private val errorEventsManager: ErrorEventsManager,
    private val uploader: ErrorReportUploader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ErrorsUiState(canUpload = uploader.isConfigured)
    )
    val uiState: StateFlow<ErrorsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            errorEventsManager.events().collect { rows ->
                _uiState.update { it.copy(events = rows) }
            }
        }
    }

    /** Toggle the expanded state for an event row. */
    fun onRowClicked(eventId: String) {
        _uiState.update { state ->
            state.copy(
                expandedEventId = if (state.expandedEventId == eventId) null else eventId
            )
        }
    }

    /**
     * The "Send error report" button arms the confirm dialog. Disabled when
     * no Console URL is configured (production stays disabled until Phase 6
     * deploy) or no events are captured yet — both surfaced via [uiState].
     */
    fun onSendClicked() {
        if (!uiState.value.canUpload || uiState.value.events.isEmpty()) return
        _uiState.update { it.copy(uploadStatus = UploadStatus.Confirming) }
    }

    fun onSendDismissed() {
        if (uiState.value.uploadStatus !is UploadStatus.Sending) {
            _uiState.update { it.copy(uploadStatus = UploadStatus.Idle) }
        }
    }

    /**
     * Confirm → upload → status feedback. uploadId is minted by
     * [ErrorReport.create] (UUID, never persisted, never reused) — proposal
     * §4.3 (anonymous, no persistent identifier).
     */
    fun onSendConfirmed() {
        val events = uiState.value.events
        if (events.isEmpty()) return
        _uiState.update { it.copy(uploadStatus = UploadStatus.Sending) }
        viewModelScope.launch {
            val outcome = runCatchingCancellable {
                uploader.upload(ErrorReport.create(events))
            }
            _uiState.update {
                it.copy(
                    uploadStatus = outcome.fold(
                        onSuccess = { UploadStatus.Sent(events.size) },
                        onFailure = { e -> UploadStatus.Failed(e.message.orEmpty()) },
                    )
                )
            }
        }
    }

    fun onStatusDismissed() {
        _uiState.update { it.copy(uploadStatus = UploadStatus.Idle) }
    }
}

data class ErrorsUiState(
    val events: List<ErrorEventSummary> = emptyList(),
    val expandedEventId: String? = null,
    /** `true` once an `ErrorReportUploader` with a configured Console URL is wired. */
    val canUpload: Boolean = false,
    val uploadStatus: UploadStatus = UploadStatus.Idle,
)

/**
 * Three-state machine for the upload CTA:
 *  - [Idle] — button enabled when [ErrorsUiState.canUpload] is true and events ≥ 1.
 *  - [Confirming] — modal asking "Send X events to Tenshu?"
 *  - [Sending] — in-flight; CTA shows spinner.
 *  - [Sent] / [Failed] — terminal toasts the user dismisses.
 */
sealed interface UploadStatus {
    data object Idle : UploadStatus
    data object Confirming : UploadStatus
    data object Sending : UploadStatus
    data class Sent(val count: Int) : UploadStatus
    data class Failed(val message: String) : UploadStatus
}
