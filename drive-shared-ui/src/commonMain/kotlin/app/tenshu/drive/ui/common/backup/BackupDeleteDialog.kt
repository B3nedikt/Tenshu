package app.tenshu.drive.ui.common.backup

import app.tenshu.ingest.backup.BackupDestination
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.backup_delete_bucket_body
import app.tenshu.drive.shared.resources.backup_delete_bucket_title
import app.tenshu.drive.shared.resources.backup_delete_cancel
import app.tenshu.drive.shared.resources.backup_delete_confirm
import app.tenshu.drive.shared.resources.backup_delete_device_body
import app.tenshu.drive.shared.resources.backup_delete_device_title
import app.tenshu.drive.shared.resources.backup_delete_manage
import org.jetbrains.compose.resources.stringResource

/**
 * Shown instead of deleting a Synced-Folders destination straight away
 * (`drive-synced-folders.md` §3.9).
 *
 * ⚠ This is **not** an "are you sure" — Drive deliberately has no confirm on ordinary
 * deletes and this does not introduce one. It exists because a backup destination is the
 * one delete whose consequences users get wrong in *both* directions: they expect it to
 * reach back and delete the photos on the phone (it cannot — backup is one-way), and they
 * do not expect the folder to keep filling afterwards (it does, because the folder
 * selection lives on the source device and no delete here can un-tick it). Saying only
 * "this cannot be undone" would answer neither.
 *
 * ⚠ There is no "and stop backing up" action, and its absence is a data limitation rather
 * than a design choice: nothing synced joins this folder to the bucket that feeds it (see
 * [BackupDestination]). *Synced Folders* routes to where the switch actually lives instead
 * of offering a button that could only guess.
 */
@Composable
fun BackupDeleteDialog(
    destination: BackupDestination,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onManageSyncedFolders: (() -> Unit)?,
) {
    val isBucket = destination is BackupDestination.Bucket
    val title = stringResource(
        if (isBucket) Res.string.backup_delete_bucket_title else Res.string.backup_delete_device_title,
        when (destination) {
            is BackupDestination.Bucket -> destination.bucketLabel
            is BackupDestination.Device -> destination.deviceLabel
        },
    )
    val body = stringResource(
        if (isBucket) Res.string.backup_delete_bucket_body else Res.string.backup_delete_device_body,
        destination.deviceLabel,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.backup_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.backup_delete_cancel))
            }
            onManageSyncedFolders?.let { manage ->
                TextButton(
                    onClick = {
                        onDismiss()
                        manage()
                    },
                ) { Text(stringResource(Res.string.backup_delete_manage)) }
            }
        },
    )
}
