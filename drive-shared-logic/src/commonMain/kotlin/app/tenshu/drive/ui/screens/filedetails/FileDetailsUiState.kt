package app.tenshu.drive.ui.screens.filedetails

import app.tenshu.drive.data.FileActivity
import app.tenshu.drive.ui.model.FileUiModel

/**
 * State for the "Details & Activity" screen
 * ([docs/proposals/drive-file-details-screen.md]).
 *
 * The header [file] (name + icon) seeds from the path and is refined once the
 * metadata stream resolves. [size] / [created] / [lastModified] are
 * pre-formatted display strings, `null` until the first emit (the screen shows a
 * placeholder dash meanwhile). [activity] is the modification history, newest
 * first (empty until its stream emits). The "Who has access" section is deferred
 * to the sharing proposal.
 */
data class FileDetailsUiState(
    val file: FileUiModel,
    val size: String? = null,
    val created: String? = null,
    val lastModified: String? = null,
    val activity: List<FileActivity> = emptyList(),
)
