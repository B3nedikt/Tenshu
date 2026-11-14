package app.tenshu.drive.ui.common.uploadconflict

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.upload_error_ok
import app.tenshu.drive.shared.resources.upload_error_title
import app.tenshu.drive.shared.resources.upload_error_too_many_copies
import app.tenshu.drive.shared.resources.upload_error_upload_failed
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsUiState
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.ui.share.StorageGateDialog
import app.tenshu.ui.chrome.AppDialog
import app.tenshu.ui.theme.TenshuTheme
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * Everything a **single-file** upload can have to say: the name conflict and its *Keep both*
 * remedy, the quota gate, and any other failure.
 *
 * One composable because the Home / Files / Folder screens each rendered the same block, and a
 * third dialog added in three places is a third dialog that can drift in three places (the
 * `rowMenu` argument). The folder-upload bar is separate — it has its own progress surface.
 */
@Composable
fun UploadFeedback(
    uiState: AddFileActionsUiState,
    /** Answers carry the conflict's id: a stale or repeated answer is then a no-op. */
    onKeepBoth: (conflictId: Long) -> Unit,
    onCancelConflict: (conflictId: Long) -> Unit,
    onDismissStorageGate: () -> Unit,
    onDismissUploadError: () -> Unit,
) {
    uiState.uploadConflict?.let { conflict ->
        UploadConflictDialog(
            fileName = conflict.fileName,
            onConfirmKeepBoth = { onKeepBoth(conflict.id) },
            onDismiss = { onCancelConflict(conflict.id) },
        )
    }

    uiState.storageGateBlock?.let { decision ->
        StorageGateDialog(decision = decision, onDismiss = onDismissStorageGate)
    }

    uiState.uploadError?.let { key ->
        UploadErrorDialog(errorKey = key, onDismiss = onDismissUploadError)
    }
}

/**
 * The acknowledge-only failure dialog. Nothing to decide — the upload did not happen, and unlike a
 * conflict there is no remedy to offer, so a single button is the honest shape.
 */
@Composable
fun UploadErrorDialog(
    errorKey: String,
    onDismiss: () -> Unit,
) {
    AppDialog(
        title = stringResource(Res.string.upload_error_title),
        icon = Icons.Default.Warning,
        onDismissRequest = onDismiss,
        confirmLabel = stringResource(Res.string.upload_error_ok),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        content = {
            Text(
                modifier = Modifier.testTag("upload_error_body"),
                text = when (errorKey) {
                    AddFileActionsViewModel.ERROR_TOO_MANY_COPIES ->
                        stringResource(Res.string.upload_error_too_many_copies)

                    else -> stringResource(Res.string.upload_error_upload_failed)
                },
            )
        }
    )
}

@Preview
@Composable
fun UploadErrorDialogPreview() {
    TenshuTheme {
        Surface {
            UploadErrorDialog(
                errorKey = AddFileActionsViewModel.ERROR_UPLOAD_FAILED,
                onDismiss = {},
            )
        }
    }
}
