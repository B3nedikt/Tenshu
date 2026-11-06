package app.tenshu.drive.ui.common.addfile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.add_file_create_file
import app.tenshu.drive.shared.resources.add_file_create_folder
import app.tenshu.drive.shared.resources.add_file_create_header
import app.tenshu.drive.shared.resources.add_file_upload_file
import app.tenshu.drive.shared.resources.add_file_upload_folder
import app.tenshu.drive.shared.resources.add_file_upload_header
import app.tenshu.drive.shared.resources.add_file_upload_images
import app.tenshu.ui.theme.TenshuTheme
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun AddFileBottomSheet(
    showCreateFolderDialog: () -> Unit,
    onUploadFile: () -> Unit,
    onUploadFolder: () -> Unit,
    onUploadImages: () -> Unit,
) {
    AddFileBottomSheetContent(
        onCreateFolderClicked = showCreateFolderDialog,
        onCreateFileClicked = { /* TODO */ },
        onUploadFileClicked = onUploadFile,
        onUploadFolderClicked = onUploadFolder,
        onUploadImagesClicked = onUploadImages
    )
}

@Composable
fun AddFileBottomSheetContent(
    onCreateFolderClicked: () -> Unit,
    onCreateFileClicked: () -> Unit,
    onUploadFileClicked: () -> Unit,
    onUploadFolderClicked: () -> Unit,
    onUploadImagesClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                bottom = MaterialTheme.spacing.xxxl,
                start = MaterialTheme.spacing.l,
                end = MaterialTheme.spacing.l,
            ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.l)
    ) {
        Text(
            text = stringResource(Res.string.add_file_create_header),
            style = MaterialTheme.typography.labelSmall
        )

        ContentRow(
            icon = Icons.Default.CreateNewFolder,
            text = stringResource(Res.string.add_file_create_folder),
            onClick = onCreateFolderClicked
        )

        ContentRow(
            icon = Icons.Default.FilePresent,
            text = stringResource(Res.string.add_file_create_file),
            onClick = onCreateFileClicked
        )

        HorizontalDivider()

        Text(
            text = stringResource(Res.string.add_file_upload_header),
            style = MaterialTheme.typography.labelSmall
        )

        ContentRow(
            icon = Icons.Default.FileUpload,
            text = stringResource(Res.string.add_file_upload_file),
            onClick = onUploadFileClicked
        )

        ContentRow(
            icon = Icons.Default.DriveFolderUpload,
            text = stringResource(Res.string.add_file_upload_folder),
            onClick = onUploadFolderClicked
        )

        ContentRow(
            icon = Icons.Default.PhotoLibrary,
            text = stringResource(Res.string.add_file_upload_images),
            onClick = onUploadImagesClicked
        )
    }
}

@Composable
private fun ContentRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.l)
    ) {
        Icon(imageVector = icon, contentDescription = "")
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

@Preview
@Composable
fun AddFileBottomSheetContentPreview() {
    TenshuTheme {
        Surface {
            AddFileBottomSheetContent(
                onCreateFolderClicked = {},
                onCreateFileClicked = {},
                onUploadFileClicked = {},
                onUploadFolderClicked = {},
                onUploadImagesClicked = {}
            )
        }
    }
}
