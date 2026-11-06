package app.tenshu.drive.ui.common.uploadconflict

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.upload_conflict_body
import app.tenshu.drive.shared.resources.upload_conflict_icon_description
import app.tenshu.drive.shared.resources.upload_conflict_keep_both
import app.tenshu.drive.shared.resources.upload_conflict_ok
import app.tenshu.drive.shared.resources.upload_conflict_title
import app.tenshu.ui.chrome.AppDialog
import app.tenshu.ui.theme.TenshuTheme
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun UploadConflictDialog(
    fileName: String,
    onConfirmKeepBoth: () -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        title = stringResource(Res.string.upload_conflict_title),
        icon = Icons.Default.Warning,
        iconContentDescription = stringResource(Res.string.upload_conflict_icon_description),
        onDismissRequest = onDismiss,
        confirmLabel = stringResource(Res.string.upload_conflict_keep_both),
        onConfirm = onConfirmKeepBoth,
        dismissLabel = stringResource(Res.string.upload_conflict_ok),
        onDismiss = onDismiss,
        content = {
            Text(text = stringResource(Res.string.upload_conflict_body, fileName))
        }
    )
}

@Preview
@Composable
fun UploadConflictDialogPreview() {
    TenshuTheme {
        Surface {
            UploadConflictDialog(
                fileName = "resume.pdf",
                onConfirmKeepBoth = {},
                onDismiss = {}
            )
        }
    }
}
