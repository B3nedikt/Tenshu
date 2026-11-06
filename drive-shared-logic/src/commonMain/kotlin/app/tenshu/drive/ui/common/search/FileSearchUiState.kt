package app.tenshu.drive.ui.common.search

import app.tenshu.liststate.ListLoadPhase
import app.tenshu.drive.ui.model.FileUiModel

data class FileSearchBarUiState(
    val files: List<FileUiModel> = emptyList(),
    /**
     * Which of loading / loaded / failed [files] is in. ⚠ A failed search used to arrive as an
     * empty result set, i.e. as "nothing matches" — the one answer a search must be able to give
     * truthfully. Starts LOADED because an untouched search bar has genuinely searched for
     * nothing.
     */
    val filesLoad: ListLoadPhase = ListLoadPhase.LOADED,
    val query: String = "",
    val isActive: Boolean = false
)
