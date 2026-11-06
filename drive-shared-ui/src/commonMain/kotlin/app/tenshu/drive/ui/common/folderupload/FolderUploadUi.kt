package app.tenshu.drive.ui.common.folderupload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tenshu.drive.data.CloudPreflight
import app.tenshu.drive.data.CloudPreflightDecision
import app.tenshu.drive.data.ConflictResolution
import app.tenshu.drive.data.formatByteSize
import app.tenshu.drive.data.FileConflict
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.folder_upload_cancel
import app.tenshu.drive.shared.resources.folder_upload_cloud_preflight_cancel
import app.tenshu.drive.shared.resources.folder_upload_cloud_preflight_continue
import app.tenshu.drive.shared.resources.folder_upload_cloud_preflight_skip
import app.tenshu.drive.shared.resources.folder_upload_cloud_preflight_title
import app.tenshu.drive.shared.resources.folder_upload_cloud_preflight_body
import app.tenshu.drive.shared.resources.folder_upload_conflict_apply_all
import app.tenshu.drive.shared.resources.folder_upload_conflict_body
import app.tenshu.drive.shared.resources.folder_upload_conflict_icon_description
import app.tenshu.drive.shared.resources.folder_upload_conflict_keep_both
import app.tenshu.drive.shared.resources.folder_upload_conflict_overwrite
import app.tenshu.drive.shared.resources.folder_upload_conflict_skip
import app.tenshu.drive.shared.resources.folder_upload_conflict_title
import app.tenshu.drive.shared.resources.folder_upload_dismiss
import app.tenshu.drive.shared.resources.folder_upload_downloading_cloud
import app.tenshu.drive.shared.resources.folder_upload_preparing
import app.tenshu.drive.shared.resources.folder_upload_progress
import app.tenshu.drive.shared.resources.folder_upload_scanning
import app.tenshu.drive.shared.resources.folder_upload_summary_cancelled
import app.tenshu.drive.shared.resources.folder_upload_summary_cancelled_partial
import app.tenshu.drive.shared.resources.folder_upload_summary_cloud_unavailable
import app.tenshu.drive.shared.resources.folder_upload_summary_failed
import app.tenshu.drive.shared.resources.folder_upload_summary_failed_read
import app.tenshu.drive.shared.resources.folder_upload_summary_insufficient_storage
import app.tenshu.drive.shared.resources.folder_upload_summary_nothing
import app.tenshu.drive.shared.resources.folder_upload_summary_skipped
import app.tenshu.drive.shared.resources.folder_upload_summary_uploaded
import app.tenshu.drive.shared.resources.folder_upload_uploading
import app.tenshu.drive.ui.common.addfileactions.FolderUploadOutcome
import app.tenshu.drive.ui.common.addfileactions.FolderUploadState
import app.tenshu.ui.chrome.AppDialog
import org.jetbrains.compose.resources.stringResource

/**
 * Non-blocking ambient bar. While running: indeterminate indicator + current
 * file + "N of M" (no byte-% — uploads are serial and per-file cost is lumpy,
 * §2.5) + a foreground Cancel. When [FolderUploadState.outcome] is set: a
 * terminal summary + Dismiss (auto-dismissed by the ViewModel after a few
 * seconds). See docs/proposals/drive-folder-upload.md §5.
 */
@Composable
fun FolderUploadStatusBar(
    state: FolderUploadState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val outcome = state.outcome
            if (outcome == null) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            state.progress.currentFileName.isBlank() ->
                                stringResource(Res.string.folder_upload_preparing)
                            // An online-only cloud file is downloaded before upload —
                            // say so, so the pause on a big placeholder doesn't read as
                            // a freeze (drive-folder-upload.md §11 P2).
                            state.progress.isOnlineOnly -> stringResource(
                                Res.string.folder_upload_downloading_cloud,
                                state.progress.currentFileName,
                            )
                            else -> stringResource(
                                Res.string.folder_upload_uploading,
                                state.progress.currentFileName,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = if (state.progress.total == 0) {
                            stringResource(Res.string.folder_upload_scanning)
                        } else {
                            stringResource(
                                Res.string.folder_upload_progress,
                                state.progress.completed,
                                state.progress.total,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(Res.string.folder_upload_cancel))
                }
            } else {
                Text(
                    text = folderUploadSummaryText(outcome),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.folder_upload_dismiss))
                }
            }
        }
    }
}

@Composable
private fun folderUploadSummaryText(outcome: FolderUploadOutcome): String = when (outcome) {
    is FolderUploadOutcome.Completed -> {
        val s = outcome.summary
        if (s.total == 0) {
            stringResource(Res.string.folder_upload_summary_nothing)
        } else {
            val uploaded = stringResource(Res.string.folder_upload_summary_uploaded, s.uploaded)
            val skipped = stringResource(Res.string.folder_upload_summary_skipped, s.skipped)
            val failed = stringResource(Res.string.folder_upload_summary_failed, s.failed)
            val cloudUnavailable =
                stringResource(Res.string.folder_upload_summary_cloud_unavailable, s.cloudUnavailable)
            buildList {
                add(uploaded)
                if (s.skipped > 0) add(skipped)
                if (s.failed > 0) add(failed)
                if (s.cloudUnavailable > 0) add(cloudUnavailable)
            }.joinToString(", ")
        }
    }

    is FolderUploadOutcome.Cancelled -> {
        val p = outcome.partial
        if (p.total == 0) {
            stringResource(Res.string.folder_upload_summary_cancelled)
        } else {
            stringResource(
                Res.string.folder_upload_summary_cancelled_partial,
                p.completed,
                p.total,
            )
        }
    }

    FolderUploadOutcome.Failed -> stringResource(Res.string.folder_upload_summary_failed_read)

    FolderUploadOutcome.InsufficientStorage ->
        stringResource(Res.string.folder_upload_summary_insufficient_storage)
}

/**
 * Per-file conflict dialog: Skip / Keep both, plus an "apply to all remaining"
 * toggle. Dismissing (back / scrim) is treated as Skip so the suspended
 * upload loop always resumes.
 */
@Composable
fun FolderUploadConflictDialog(
    conflict: FileConflict,
    onResolve: (ConflictResolution, applyToAll: Boolean) -> Unit,
) {
    var applyToAll by remember { mutableStateOf(false) }
    AppDialog(
        title = stringResource(Res.string.folder_upload_conflict_title),
        icon = Icons.Default.Warning,
        iconContentDescription = stringResource(Res.string.folder_upload_conflict_icon_description),
        onDismissRequest = { onResolve(ConflictResolution.Skip, applyToAll) },
        dismissButton = {
            TextButton(onClick = { onResolve(ConflictResolution.Skip, applyToAll) }) {
                Text(stringResource(Res.string.folder_upload_conflict_skip))
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onResolve(ConflictResolution.KeepBoth, applyToAll) }) {
                    Text(stringResource(Res.string.folder_upload_conflict_keep_both))
                }
                TextButton(
                    onClick = { onResolve(ConflictResolution.Overwrite, applyToAll) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                ) {
                    Text(stringResource(Res.string.folder_upload_conflict_overwrite))
                }
            }
        },
        content = {
            Column {
                Text(
                    text = stringResource(
                        Res.string.folder_upload_conflict_body,
                        conflict.fileName,
                        conflict.destinationFolder,
                        conflict.fileName.substringBeforeLast("."),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .toggleable(
                            value = applyToAll,
                            role = Role.Checkbox,
                            onValueChange = { applyToAll = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = applyToAll, onCheckedChange = null)
                    Text(
                        text = stringResource(Res.string.folder_upload_conflict_apply_all),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    )
}

/**
 * Cloud pre-flight prompt (drive-folder-upload.md §11 P2b): shown once, before any
 * upload work, when the picked folder holds online-only cloud-provider files. It is
 * transparent that those files must download before uploading, and offers the easy
 * opt-out (skip them) — Option 3. Continue with the box unchecked = Include (download
 * + upload); checked = Skip; Cancel = don't upload anything.
 */
@Composable
fun FolderUploadPreflightDialog(
    preflight: CloudPreflight,
    onDecision: (CloudPreflightDecision) -> Unit,
) {
    var skip by remember { mutableStateOf(false) }
    AppDialog(
        title = stringResource(Res.string.folder_upload_cloud_preflight_title),
        icon = Icons.Default.Warning,
        onDismissRequest = { onDecision(CloudPreflightDecision.Cancel) },
        dismissButton = {
            TextButton(onClick = { onDecision(CloudPreflightDecision.Cancel) }) {
                Text(stringResource(Res.string.folder_upload_cloud_preflight_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDecision(if (skip) CloudPreflightDecision.Skip else CloudPreflightDecision.Include)
            }) {
                Text(stringResource(Res.string.folder_upload_cloud_preflight_continue))
            }
        },
        content = {
            Column {
                Text(
                    text = stringResource(
                        Res.string.folder_upload_cloud_preflight_body,
                        preflight.onlineOnlyCount,
                        formatByteSize(preflight.onlineOnlyBytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .toggleable(
                            value = skip,
                            role = Role.Checkbox,
                            onValueChange = { skip = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = skip, onCheckedChange = null)
                    Text(
                        text = stringResource(Res.string.folder_upload_cloud_preflight_skip),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    )
}
