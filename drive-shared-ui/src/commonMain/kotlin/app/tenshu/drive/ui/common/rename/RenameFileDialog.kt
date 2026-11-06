package app.tenshu.drive.ui.common.rename

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.rename_file_dialog_confirm
import app.tenshu.drive.shared.resources.rename_file_dialog_dismiss
import app.tenshu.drive.shared.resources.rename_file_dialog_error_failed
import app.tenshu.drive.shared.resources.rename_file_dialog_error_name_separator
import app.tenshu.drive.shared.resources.rename_file_dialog_error_name_taken
import app.tenshu.drive.shared.resources.rename_file_dialog_error_still_syncing
import app.tenshu.drive.shared.resources.rename_file_dialog_error_move_needs_local_bytes
import app.tenshu.drive.shared.resources.rename_file_dialog_icon_description
import app.tenshu.drive.shared.resources.rename_file_dialog_placeholder
import app.tenshu.drive.shared.resources.rename_file_dialog_title
import app.tenshu.ui.chrome.AppDialog
import app.tenshu.ui.input.AppTextField
import app.tenshu.ui.theme.TenshuTheme
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun RenameFileDialog(
    onDismissRequest: () -> Unit,
    filePath: String
) {
    val viewModel: RenameFileDialogViewModel =
        koinViewModel(key = filePath, parameters = { parametersOf(filePath) })
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    // Close on the COMMIT, not on the tap. The dialog used to dismiss the moment Confirm was
    // pressed, so a refused rename (the parent already holds that name) looked exactly like a
    // successful one: the dialog closed and nothing changed.
    LaunchedEffect(uiState.renamedPath) {
        if (uiState.renamedPath != null) {
            focusManager.clearFocus()
            onDismissRequest()
        }
    }

    RenameFileDialogContent(
        uiState = uiState,
        onNewNameChanged = viewModel::onNewNameChanged,
        onConfirm = viewModel::renameFile,
        onDismiss = {
            focusManager.clearFocus()
            onDismissRequest()
        }
    )
}

@Composable
fun RenameFileDialogContent(
    uiState: RenameFileDialogUiState,
    onNewNameChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    AppDialog(
        title = stringResource(Res.string.rename_file_dialog_title),
        icon = Icons.Default.Edit,
        iconContentDescription = stringResource(Res.string.rename_file_dialog_icon_description),
        onDismissRequest = onDismiss,
        // The slot overload rather than the label one: Confirm has to be disabled for a name
        // that cannot be submitted (blank, or unchanged — see the view model's `canRename`).
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = uiState.canRename && !uiState.isRenaming,
                modifier = Modifier.testTag("rename_confirm"),
            ) {
                Text(stringResource(Res.string.rename_file_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.rename_file_dialog_dismiss))
            }
        },
        content = {
            AppTextField(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .testTag("rename_field"),
                value = uiState.newName,
                onValueChange = onNewNameChanged,
                placeholder = stringResource(Res.string.rename_file_dialog_placeholder),
                errorText = uiState.errorMessage?.let { key ->
                    when (key) {
                        RenameFileDialogViewModel.ERROR_ALREADY_EXISTS ->
                            stringResource(Res.string.rename_file_dialog_error_name_taken)

                        RenameFileDialogViewModel.ERROR_NAME_HAS_SEPARATOR ->
                            stringResource(Res.string.rename_file_dialog_error_name_separator)

                        RenameFileDialogViewModel.ERROR_STILL_SYNCING ->
                            stringResource(Res.string.rename_file_dialog_error_still_syncing)

                        RenameFileDialogViewModel.ERROR_MOVE_NEEDS_LOCAL_BYTES ->
                            stringResource(Res.string.rename_file_dialog_error_move_needs_local_bytes)

                        else -> stringResource(Res.string.rename_file_dialog_error_failed)
                    }
                },
            )
        }
    )
}

@Preview
@Composable
fun RenameFileDialogContentPreview() {
    TenshuTheme {
        Surface {
            RenameFileDialogContent(
                uiState = RenameFileDialogUiState(newName = "renamed_file.pdf", canRename = true),
                onNewNameChanged = {},
                onConfirm = {},
                onDismiss = {}
            )
        }
    }
}

@Preview
@Composable
fun RenameFileDialogContentNameTakenPreview() {
    TenshuTheme {
        Surface {
            RenameFileDialogContent(
                uiState = RenameFileDialogUiState(
                    newName = "budget.pdf",
                    canRename = true,
                    errorMessage = RenameFileDialogViewModel.ERROR_ALREADY_EXISTS,
                ),
                onNewNameChanged = {},
                onConfirm = {},
                onDismiss = {}
            )
        }
    }
}
