package app.tenshu.drive.ui.common.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.ui.common.asImageVector
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.file_actions_delete
import app.tenshu.drive.shared.resources.file_actions_details_activity
import app.tenshu.drive.shared.resources.file_actions_make_folder_offline
import app.tenshu.drive.shared.resources.file_actions_make_available_offline
import app.tenshu.drive.shared.resources.file_actions_make_folder_online_only
import app.tenshu.drive.shared.resources.file_actions_remove_download
import app.tenshu.drive.shared.resources.file_actions_link
import app.tenshu.drive.shared.resources.file_actions_manage_access
import app.tenshu.drive.shared.resources.file_actions_move
import app.tenshu.drive.shared.resources.file_actions_rename
import app.tenshu.drive.shared.resources.file_actions_share
import app.tenshu.drive.ui.common.backup.BackupDeleteDialog
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.drive.ui.share.CreateShareLinkSheet
import app.tenshu.drive.ui.share.CreateShareLinkViewModel
import app.tenshu.drive.ui.share.StorageGateDialog
import app.tenshu.ui.chrome.AppBottomSheet
import app.tenshu.ui.theme.TenshuTheme
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun FileActionsBottomSheet(
    viewModel: FileActionsViewModel,
    onActionCompleted: () -> Unit,
    onMoveRequested: (String) -> Unit,
    onRenameRequested: (String) -> Unit,
    onDetailsRequested: (String) -> Unit,
    onManageAccessRequested: (String) -> Unit = {},
    // Routes to the Synced Folders screen from the backup-delete warning. `null` (tests,
    // hosts with no navigator) hides that button rather than rendering one that silently
    // does nothing.
    onSyncedFoldersRequested: (() -> Unit)? = null,
    // Fired after a delete, in addition to `onActionCompleted`. Hosts that show
    // the single deleted file (the preview) pass `navigateUp` so the screen pops
    // back rather than lingering on a file that no longer exists; list hosts
    // leave it at the no-op default and simply stay on the list.
    onDeleteCompleted: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    FileActionsBottomSheetContent(
        uiState = uiState,
        onDownloadClicked = {
            viewModel.onDownloadClicked()
            onActionCompleted()
        },
        onFolderOfflineToggled = {
            viewModel.onFolderOfflineToggled()
            onActionCompleted()
        },
        onShareClicked = {
            // Mode-B folder share. On success collapse the sheet; on a storage-gate
            // block keep it composed so StorageGateDialog (hosted below) can render
            // in place — same rule as onLinkClicked.
            if (viewModel.onShareClicked()) onActionCompleted()
        },
        onManageAccessClicked = {
            // Navigates to the Manage access *screen* (drive-manage-access-ux.md
            // §10) — like *Details & Activity*, not an in-place sheet.
            onManageAccessRequested(uiState.file.path)
            onActionCompleted()
        },
        onLinkClicked = {
            // Don't dismiss the parent FileActions sheet immediately — the
            // FileActions VM flips `showCreateShareLinkForPath` and we render
            // the create-link sheet in-place. Dismissing the sheet would also
            // tear this composable down, leaving nowhere to host the modal.
            viewModel.onLinkClicked()
        },
        onMoveClicked = {
            onMoveRequested(uiState.file.path)
            onActionCompleted()
        },
        onRenameClicked = {
            onRenameRequested(uiState.file.path)
            onActionCompleted()
        },
        onDeleteClicked = {
            viewModel.onDeleteClicked()
            // Same rule as onLinkClicked: when the VM raises the backup warning instead of
            // deleting, this composable has to stay alive to host the dialog — collapsing
            // the sheet here would tear down the only place it can render.
            if (viewModel.uiState.value.backupDeleteWarning == null) {
                onActionCompleted()
                onDeleteCompleted()
            }
        },
        onInfoClicked = {
            onDetailsRequested(uiState.file.path)
            onActionCompleted()
        }
    )

    // The Synced-Folders delete warning (drive-synced-folders.md §3.9). Hosted here for
    // the same reason as the create-link sheet below: every host gets it for free, and the
    // VM owns the flag so the delete cannot run until it is confirmed.
    uiState.backupDeleteWarning?.let { destination ->
        BackupDeleteDialog(
            destination = destination,
            onConfirm = {
                viewModel.onBackupDeleteConfirmed()
                onActionCompleted()
                onDeleteCompleted()
            },
            onDismiss = { viewModel.onBackupDeleteDismissed() },
            onManageSyncedFolders = onSyncedFoldersRequested,
        )
    }

    // Mode-A *Share link* sheet (docs/file-sharing.md). Hosted inside
    // FileActionsBottomSheet so every call site (HomeScreen, FilesScreen,
    // FolderScreen, FilePreview entry, …) gets the create-link surface for
    // free — no extra wiring per host. The FileActions VM owns the
    // `showCreateShareLinkForPath` flag (FileActionsUiState); on dismiss we
    // also clear the flag AND complete the parent action so the FileActions
    // sheet collapses.
    uiState.showCreateShareLinkForPath?.let { pathForShare ->
        AppBottomSheet(
            visible = true,
            onDismissRequest = {
                viewModel.onCreateShareLinkDismissed()
                onActionCompleted()
            }
        ) {
            val shareLinkViewModel = koinViewModel<CreateShareLinkViewModel>(
                key = "create_share_link_$pathForShare",
                parameters = { parametersOf(pathForShare) }
            )
            CreateShareLinkSheet(
                viewModel = shareLinkViewModel,
                onDismiss = {
                    viewModel.onCreateShareLinkDismissed()
                    onActionCompleted()
                }
            )
        }
    }

    // Tier-1 storage gate (storage-capacity-gating.md §2): when *Link* is blocked
    // for want of storage, show the gate dialog instead of the create-link sheet.
    uiState.storageGateBlock?.let { decision ->
        StorageGateDialog(
            decision = decision,
            onDismiss = {
                viewModel.onStorageGateDismissed()
                onActionCompleted()
            },
        )
    }
}

@Composable
fun FileActionsBottomSheetContent(
    uiState: FileActionsUiState,
    onDownloadClicked: () -> Unit,
    onFolderOfflineToggled: () -> Unit = {},
    onShareClicked: () -> Unit,
    onManageAccessClicked: () -> Unit,
    onLinkClicked: () -> Unit,
    onMoveClicked: () -> Unit,
    onRenameClicked: () -> Unit,
    onDeleteClicked: () -> Unit,
    onInfoClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 48.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(imageVector = uiState.file.previewIcon.asImageVector(), contentDescription = "")
            Text(text = uiState.file.name, style = MaterialTheme.typography.labelMedium)
        }

        HorizontalDivider()

        if (uiState.isFolder) {
            // Folder-level sync-rule override (drive-offline-online.md §4.10 Phase 2b):
            // flips the folder's effective policy, covering files created under it
            // later. Label/icon track the folder's current effective policy.
            ContentRow(
                icon = if (uiState.folderKeptOffline) Icons.Default.CloudOff else Icons.Default.CloudDownload,
                text = stringResource(
                    if (uiState.folderKeptOffline) Res.string.file_actions_make_folder_online_only
                    else Res.string.file_actions_make_folder_offline
                ),
                onClick = onFolderOfflineToggled
            )
        } else {
            // Per-file offline toggle (drive-offline-online.md §2): label/icon flip
            // on the file's offline *intent*. Online-only → "Make available offline"
            // (pins + downloads); kept offline → "Remove download" (unpins + evicts).
            ContentRow(
                icon = if (uiState.isAvailableOffline) Icons.Default.CloudOff else Icons.Default.CloudDownload,
                text = stringResource(
                    if (uiState.isAvailableOffline) Res.string.file_actions_remove_download
                    else Res.string.file_actions_make_available_offline
                ),
                onClick = onDownloadClicked
            )
        }

        if (uiState.isFolder) {
            // Folder-only: Mode-B shares a *folder* into a shared realm
            // (drive-manage-access-ux.md §2.1). A file shares via *Link* below.
            ContentRow(
                icon = Icons.Default.Share,
                text = stringResource(Res.string.file_actions_share),
                onClick = onShareClicked
            )

            // Folder-only, matching the folder-only *Share* gate above.
            ContentRow(
                icon = Icons.Default.SupervisorAccount,
                text = stringResource(Res.string.file_actions_manage_access),
                onClick = onManageAccessClicked
            )
        }

        if (!uiState.isFolder) {
            // File-only: a Mode-A link re-encrypts *the file's bytes* under
            // `K_share` and uploads them. A folder has no bytes — offering *Link*
            // on one only ever produced "Cannot share '/x': file not found". A
            // folder is shared via *Share* / *Manage access* above (Mode-B).
            ContentRow(
                icon = Icons.Default.Link,
                text = stringResource(Res.string.file_actions_link),
                onClick = onLinkClicked
            )
        }

        HorizontalDivider()

        ContentRow(
            icon = Icons.AutoMirrored.Filled.DriveFileMove,
            text = stringResource(Res.string.file_actions_move),
            onClick = onMoveClicked
        )

        ContentRow(
            icon = Icons.Default.Edit,
            text = stringResource(Res.string.file_actions_rename),
            onClick = onRenameClicked
        )

        ContentRow(
            icon = Icons.Default.Delete,
            text = stringResource(Res.string.file_actions_delete),
            onClick = onDeleteClicked
        )

        ContentRow(
            icon = Icons.Default.Info,
            text = stringResource(Res.string.file_actions_details_activity),
            onClick = onInfoClicked
        )
    }
}

@Composable
private fun ContentRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(imageVector = icon, contentDescription = "")
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

@Preview
@Composable
fun FileDetailsBottomSheetContentPreview() {
    TenshuTheme {
        Surface {
            FileActionsBottomSheetContent(
                uiState = FileActionsUiState(
                    file = FileUiModel("/test.pdf", "test.pdf", "1.2 MB", FileIcon.GenericFile)
                ),
                onDownloadClicked = {},
                onShareClicked = {},
                onManageAccessClicked = {},
                onLinkClicked = {},
                onMoveClicked = {},
                onRenameClicked = {},
                onDeleteClicked = {},
                onInfoClicked = {}
            )
        }
    }
}
