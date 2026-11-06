package app.tenshu.drive.ui.screens.files

import app.tenshu.drive.ui.model.FileUiModel

data class FilesScreenUiState(
    val showFolderWithPath: String? = null,
    val showFileWithPath: String? = null,
    val showAddFile: Boolean = false,
    val showFileActionsWithPath: String? = null,
    /** Whether the row whose actions sheet is open is a folder — drives the
     *  folder-level sync-rule override row (drive-offline-online.md §4.10 Phase 2b). */
    val showFileActionsForFolder: Boolean = false,
    val showRenameFileWithPath: String? = null,
    val showCreateFolder: Boolean = false,
    val showFile: FileUiModel? = null,
)
