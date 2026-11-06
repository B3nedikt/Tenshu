package app.tenshu.drive.ui.common.createfolder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.create_folder_dialog_confirm
import app.tenshu.drive.shared.resources.create_folder_dialog_dismiss
import app.tenshu.drive.shared.resources.create_folder_dialog_error_failed
import app.tenshu.drive.shared.resources.create_folder_dialog_error_name_separator
import app.tenshu.drive.shared.resources.create_folder_dialog_error_name_taken
import app.tenshu.drive.shared.resources.create_folder_dialog_error_storage
import app.tenshu.drive.shared.resources.create_folder_dialog_icon_description
import app.tenshu.drive.shared.resources.create_folder_dialog_keep_both
import app.tenshu.drive.shared.resources.create_folder_dialog_name_placeholder
import app.tenshu.drive.shared.resources.create_folder_dialog_ok
import app.tenshu.drive.shared.resources.create_folder_dialog_title
import app.tenshu.drive.shared.resources.create_folder_dialog_title_name_taken
import app.tenshu.ui.chrome.AppDialog
import app.tenshu.ui.input.AppTextField
import app.tenshu.ui.theme.TenshuTheme
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CreateFolderDialog(
    onDismissRequest: () -> Unit,
    folderPath: String = FileModel.Root.path
) {
    val viewModel: CreateFolderDialogViewModel =
        koinViewModel(key = folderPath, parameters = { parametersOf(folderPath) })
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    // Close on the write, which now runs on `viewModelScope` rather than a scope remembered by
    // this composition — dismissing the dialog no longer cancels the folder being created.
    LaunchedEffect(uiState.created) {
        if (uiState.created) {
            focusManager.clearFocus()
            onDismissRequest()
        }
    }

    CreateFolderDialogContent(
        uiState = uiState,
        onFolderNameChanged = viewModel::onFolderNameChanged,
        onConfirm = viewModel::createFolder,
        onKeepBoth = viewModel::keepBoth,
        onDismiss = {
            focusManager.clearFocus()
            onDismissRequest()
        }
    )
}

@Composable
fun CreateFolderDialogContent(
    uiState: CreateFolderDialogUiState,
    onFolderNameChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onKeepBoth: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    AppDialog(
        // The name-taken title belongs to the name-taken case only. Keyed on `canKeepBoth` rather
        // than on "some error happened", which is what used to announce a collision when the real
        // failure was something else entirely.
        title = stringResource(
            if (uiState.canKeepBoth) Res.string.create_folder_dialog_title_name_taken
            else Res.string.create_folder_dialog_title
        ),
        icon = Icons.Default.CreateNewFolder,
        iconContentDescription = stringResource(Res.string.create_folder_dialog_icon_description),
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)) {
                if (uiState.canKeepBoth) {
                    TextButton(
                        onClick = onKeepBoth,
                        enabled = !uiState.isCreating,
                        modifier = Modifier.testTag("create_folder_keep_both"),
                    ) {
                        Text(stringResource(Res.string.create_folder_dialog_keep_both))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.create_folder_dialog_ok))
                    }
                } else {
                    TextButton(
                        onClick = onConfirm,
                        enabled = uiState.canCreate && !uiState.isCreating,
                        modifier = Modifier.testTag("create_folder_confirm"),
                    ) {
                        Text(stringResource(Res.string.create_folder_dialog_confirm))
                    }
                }
            }
        },
        dismissButton = if (!uiState.canKeepBoth) {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.create_folder_dialog_dismiss))
                }
            }
        } else null,
        content = {
            AppTextField(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .testTag("folder_name_field"),
                value = uiState.folderName,
                onValueChange = onFolderNameChanged,
                placeholder = stringResource(Res.string.create_folder_dialog_name_placeholder),
                errorText = uiState.errorMessage?.let { key ->
                    when (key) {
                        CreateFolderDialogViewModel.ERROR_ALREADY_EXISTS ->
                            stringResource(Res.string.create_folder_dialog_error_name_taken)

                        CreateFolderDialogViewModel.ERROR_NAME_HAS_SEPARATOR ->
                            stringResource(Res.string.create_folder_dialog_error_name_separator)

                        CreateFolderDialogViewModel.ERROR_INSUFFICIENT_STORAGE ->
                            stringResource(Res.string.create_folder_dialog_error_storage)

                        else -> stringResource(Res.string.create_folder_dialog_error_failed)
                    }
                }
            )
        }
    )
}

@Preview
@Composable
fun CreateFolderDialogContentPreview() {
    TenshuTheme {
        Surface {
            CreateFolderDialogContent(
                uiState = CreateFolderDialogUiState(folderName = "New Folder", canCreate = true),
                onFolderNameChanged = {},
                onConfirm = {},
                onKeepBoth = {},
                onDismiss = {}
            )
        }
    }
}

@Preview
@Composable
fun CreateFolderDialogContentErrorPreview() {
    TenshuTheme {
        Surface {
            CreateFolderDialogContent(
                uiState = CreateFolderDialogUiState(
                    folderName = "Existing Folder",
                    canCreate = true,
                    errorMessage = CreateFolderDialogViewModel.ERROR_ALREADY_EXISTS,
                ),
                onFolderNameChanged = {},
                onConfirm = {},
                onKeepBoth = {},
                onDismiss = {}
            )
        }
    }
}
