package app.tenshu.drive.ui.model

import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FileModel

data class FileUiModel(
    val path: String,
    val name: String,
    val subLine: String,
    val previewIcon: FileIcon,
    val previewBytes: ByteArray? = null,
    // Carried so row clicks can branch folder-vs-file without holding the
    // domain model — the paged list only ever sees FileUiModel.
    val isFolder: Boolean = false,
)

fun FileModel.toUiModel(): FileUiModel {
    return FileUiModel(
        path = path,
        name = name,
        subLine = subLine,
        previewIcon = previewIcon,
        previewBytes = previewBytes,
        isFolder = this is FileModel.Folder,
    )
}
