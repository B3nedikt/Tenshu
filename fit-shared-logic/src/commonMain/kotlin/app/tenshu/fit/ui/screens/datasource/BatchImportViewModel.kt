package app.tenshu.fit.ui.screens.datasource

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.HealthFilePicker
import app.tenshu.fit.model.BatchImportSummary
import app.tenshu.fit.model.ImportRunEntry
import app.tenshu.fit.model.ImportStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BatchImportUiState(
    val summary: BatchImportSummary? = null,
    val history: List<ImportRunEntry> = emptyList(),
    /**
     * The live import. ⚠ This screen is the one place it genuinely belongs on screen: a Takeout
     * import runs for minutes and writes tens of thousands of rows, and a running count is the
     * only thing distinguishing "working" from "hung".
     */
    val importStatus: ImportStatus = ImportStatus.Idle,
    val canImportFiles: Boolean = false,
) {
    /** Whether anything has ever been imported from a file — drives the empty state. */
    val hasHistory: Boolean get() = history.isNotEmpty()
}

/**
 * The *Batch import* detail screen (`fit-data-sources-restructure.md` §4).
 *
 * ⚠ Reads runs, not a source row: a file import is an **act**, not a standing connection, so
 * Takeout has no `HealthSource` and everything here derives from `HealthImportRun(kind = TAKEOUT)`
 * (D11). That is also why there is no auto-import toggle — there is nothing automatic to stop.
 */
class BatchImportViewModel(
    private val repository: FitRepository,
    private val filePicker: HealthFilePicker? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BatchImportUiState(canImportFiles = filePicker != null))
    val uiState: StateFlow<BatchImportUiState> get() = _uiState

    init {
        viewModelScope.launch {
            combine(
                repository.batchImport(),
                repository.importHistory(kind = null),
                repository.importStatus(),
            ) { summary, history, status -> Triple(summary, history, status) }
                .collect { (summary, history, status) ->
                    _uiState.update {
                        it.copy(summary = summary, history = history, importStatus = status)
                    }
                }
        }
    }

    /**
     * "Import again" — hands off to the shell, which owns the picker and reads the bytes. The
     * outcome comes back through [BatchImportUiState.importStatus] and, once it finishes, as a
     * new row in [BatchImportUiState.history].
     */
    fun importFromFile() {
        filePicker?.pick()
    }
}
