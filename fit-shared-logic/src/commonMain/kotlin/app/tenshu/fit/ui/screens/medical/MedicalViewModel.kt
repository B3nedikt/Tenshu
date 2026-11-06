package app.tenshu.fit.ui.screens.medical

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.model.MedicalRecord
import app.tenshu.fit.model.MedicalRecordKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class MedicalSection(
    val kind: MedicalRecordKind,
    /** Newest effective date first. */
    val records: List<MedicalRecord>,
)

data class MedicalUiState(
    /** Fixed section order: Medications, Immunizations, Allergies, Conditions. */
    val sections: List<MedicalSection> = emptyList(),
)

/**
 * Medical records (Ph3, docs/proposals/fit-app.md §2 screen 11) — import-only,
 * read-only (fit-agent.md §0.7): the VM exposes no mutations by design.
 */
class MedicalViewModel(
    repository: FitRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MedicalUiState())
    val uiState: StateFlow<MedicalUiState> get() = _uiState

    init {
        viewModelScope.launch {
            repository.medicalRecords().collect { records ->
                _uiState.value = MedicalUiState(
                    sections = MedicalRecordKind.entries.mapNotNull { kind ->
                        records
                            .filter { it.kind == kind }
                            .sortedByDescending { it.effectiveDate }
                            .takeIf { it.isNotEmpty() }
                            ?.let { MedicalSection(kind, it) }
                    },
                )
            }
        }
    }
}
