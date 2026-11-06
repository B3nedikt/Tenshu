package app.tenshu.contacts.ui.screens.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.contacts.data.ContactsRepository
import app.tenshu.contacts.data.RepositoryResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Why an import stopped short of creating contacts. Typed rather than a message, so the
 * copy lives in the resource catalogue (ADR 0016) and the platform layer never has to
 * invent user-facing English.
 */
enum class ContactImportProblem {
    /** The user declined the OS permission or dismissed its dialog. */
    PermissionDenied,

    /** The picker returned something that could not be read at all. */
    Unreadable,

    /** It was read, and held no vCards — an empty address book, or a file that is not one. */
    NoCards,

    /** The cards parsed, but the service refused every one of them. */
    NothingImported,
}

data class ContactImportUiState(
    /** The import sheet is open. */
    val visible: Boolean = false,
    /** A platform picker is up, or its cards are being written. */
    val isImporting: Boolean = false,
    /**
     * How many contacts the last run created. Kept after the picker closes so the sheet can
     * report the outcome — an import that silently does nothing visible is indistinguishable
     * from one that failed.
     */
    val importedCount: Int? = null,
    val problem: ContactImportProblem? = null,
) {
    /** True while nothing has happened yet — the sheet shows its source list. */
    val showingSources: Boolean get() = !isImporting && importedCount == null && problem == null
}

/**
 * The import flow's state (`docs/proposals/contacts-app.md`).
 *
 * Deliberately knows nothing about pickers: the platform layer hands it `.vcf` **text**, and
 * everything downstream of that is `ContactsRepository.importVcards`. That split is what keeps the flow testable — the parts that can only be
 * exercised on a device (a `ContactsContract` query, an AWT file dialog) contain no logic
 * beyond producing bytes.
 */
class ContactImportViewModel(
    private val repository: ContactsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactImportUiState())
    val uiState: StateFlow<ContactImportUiState> get() = _uiState

    fun onImportClicked() {
        _uiState.value = ContactImportUiState(visible = true)
    }

    /** Closes the sheet and drops the last outcome — reopening starts clean. */
    fun onDismissed() {
        _uiState.value = ContactImportUiState()
    }

    /** Back to the source list after a result — "import some more". */
    fun onResultAcknowledged() = _uiState.update {
        it.copy(importedCount = null, problem = null)
    }

    /** A picker was launched; the sheet shows progress until it comes back. */
    fun onPickStarted() = _uiState.update {
        it.copy(isImporting = true, importedCount = null, problem = null)
    }

    /** The user backed out of the picker. Not a failure — say nothing, show the list again. */
    fun onPickCancelled() = _uiState.update { it.copy(isImporting = false) }

    fun onPickFailed(problem: ContactImportProblem) = _uiState.update {
        it.copy(isImporting = false, problem = problem)
    }

    /**
     * Import `.vcf` text produced by a platform picker.
     *
     * ⚠ Every card becomes a **new** contact (D7, `ContactsRepository.importVcards`), so
     * importing the same file twice doubles it — the honest behaviour, since the alternative
     * is silently folding two people together because they share a name.
     */
    fun onVcardText(text: String) {
        if (text.isBlank()) {
            onPickFailed(ContactImportProblem.NoCards)
            return
        }
        _uiState.update { it.copy(isImporting = true, importedCount = null, problem = null) }
        viewModelScope.launch {
            when (val result = repository.importVcards(text)) {
                is RepositoryResult.Success ->
                    // Zero created out of a non-empty file is a failure, not a quiet success:
                    // the file held cards and none of them landed.
                    if (result.value == 0) {
                        _uiState.update {
                            it.copy(
                                isImporting = false,
                                problem = ContactImportProblem.NothingImported,
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(isImporting = false, importedCount = result.value)
                        }
                    }

                is RepositoryResult.Failure ->
                    // The only failure the repository reports is "no cards in that text".
                    _uiState.update {
                        it.copy(isImporting = false, problem = ContactImportProblem.NoCards)
                    }
            }
        }
    }
}
