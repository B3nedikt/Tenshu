package app.tenshu.drive.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.vector.ImageVector
import app.tenshu.drive.data.FileIcon

/**
 * Compose rendering of the platform-neutral [FileIcon] the logic layer carries
 * (§9.3). Kept on the UI side so the model/VM layer stays Compose-free and
 * iOS-capable; SwiftUI provides its own SF-Symbol mapping.
 */
fun FileIcon.asImageVector(): ImageVector = when (this) {
    FileIcon.Home -> Icons.Filled.Home
    FileIcon.Folder -> Icons.Filled.Folder
    FileIcon.Audio -> Icons.Filled.AudioFile
    FileIcon.Document -> Icons.Filled.TextFields
    FileIcon.Image -> Icons.Filled.PictureInPicture
    FileIcon.Video -> Icons.Filled.VideoFile
    FileIcon.Archive -> Icons.Filled.Archive
    FileIcon.Other -> Icons.Filled.FilePresent
    FileIcon.GenericFile -> Icons.Filled.Description
    FileIcon.Pdf -> Icons.Filled.PictureAsPdf
}
