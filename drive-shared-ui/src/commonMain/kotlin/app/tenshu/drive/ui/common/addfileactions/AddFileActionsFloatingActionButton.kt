package app.tenshu.drive.ui.common.addfileactions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.add_file_fab_description
import app.tenshu.drive.ui.common.addfile.AddFileBottomSheet
import app.tenshu.drive.ui.common.createfolder.CreateFolderDialog
import app.tenshu.ui.chrome.AppBottomSheet
import app.tenshu.ui.chrome.AppFab
import org.jetbrains.compose.resources.stringResource

@Composable
fun AddFileActionsFloatingActionButton(
    modifier: Modifier,
    uiState: AddFileActionsUiState,
    folderPath: String,
    onShowAddFileActions: () -> Unit,
    onHideAddFileActions: () -> Unit,
    onHideCreateFolder: () -> Unit,
    onShowCreateFolder: () -> Unit,
    onUploadFile: () -> Unit,
    onUploadFolder: () -> Unit,
    onUploadImages: () -> Unit,
) {
    val fabFocusRequester = remember { FocusRequester() }

    if (uiState.showAddFolder) {
        CreateFolderDialog(
            onDismissRequest = onHideCreateFolder,
            folderPath = folderPath
        )
    }

    LaunchedEffect(uiState.showAddFolder) {
        if (uiState.showAddFolder) {
            onHideAddFileActions()
        }
    }

    AppBottomSheet(
        visible = uiState.showAddFileActions,
        onDismissRequest = { onHideAddFileActions() }
    ) {
        AddFileBottomSheet(
            showCreateFolderDialog = onShowCreateFolder,
            onUploadFile = {
                onUploadFile()
                onHideAddFileActions()
            },
            onUploadFolder = {
                onUploadFolder()
                onHideAddFileActions()
            },
            onUploadImages = {
                onUploadImages()
                onHideAddFileActions()
            }
        )
    }

    AppFab(
        modifier = modifier.focusRequester(fabFocusRequester),
        onClick = onShowAddFileActions,
        icon = Icons.Default.Add,
        contentDescription = stringResource(Res.string.add_file_fab_description),
    )
}
