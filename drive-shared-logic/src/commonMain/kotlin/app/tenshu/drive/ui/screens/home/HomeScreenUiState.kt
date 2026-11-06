package app.tenshu.drive.ui.screens.home

import app.tenshu.liststate.ListLoadPhase
import app.tenshu.drive.ui.model.FileUiModel

data class HomeScreenUiState(
    val recentFiles: List<FileUiModel> = emptyList(),
    /**
     * Which of loading / loaded / failed [recentFiles] is in. ⚠ Without this the list rendered
     * "No files here yet" both before the first emission and after a failed read — an assertion
     * about the user's drive made at the two moments it could not be checked.
     */
    val recentFilesLoad: ListLoadPhase = ListLoadPhase.LOADING,
    val searchBarFiles: List<FileUiModel>,
    val showFileActionsWithPath: String? = null,
    /** Whether the row whose actions sheet is open is a folder — drives the
     *  folder-level sync-rule override row (drive-offline-online.md §4.10 Phase 2b). */
    val showFileActionsForFolder: Boolean = false,
    val showRenameFileWithPath: String? = null,
    val showFile: FileUiModel? = null
)