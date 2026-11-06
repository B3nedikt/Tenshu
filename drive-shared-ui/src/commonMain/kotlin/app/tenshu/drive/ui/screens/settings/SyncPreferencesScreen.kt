package app.tenshu.drive.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tenshu.drive.data.formatByteSize
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.sync_preferences_cache_limit_body
import app.tenshu.drive.shared.resources.sync_preferences_cache_limit_dialog_body
import app.tenshu.drive.shared.resources.sync_preferences_cache_limit_dialog_title
import app.tenshu.drive.shared.resources.sync_preferences_cache_limit_dismiss
import app.tenshu.drive.shared.resources.sync_preferences_cache_limit_none
import app.tenshu.drive.shared.resources.sync_preferences_cache_limit_title
import app.tenshu.drive.shared.resources.sync_preferences_keep_offline_body
import app.tenshu.drive.shared.resources.sync_preferences_keep_offline_title
import app.tenshu.drive.shared.resources.sync_preferences_offline_files_count
import app.tenshu.drive.shared.resources.sync_preferences_offline_files_none
import app.tenshu.drive.shared.resources.sync_preferences_offline_files_remove
import app.tenshu.drive.shared.resources.sync_preferences_offline_files_title
import app.tenshu.drive.shared.resources.sync_preferences_synced_folders_body
import app.tenshu.drive.shared.resources.sync_preferences_synced_folders_title
import app.tenshu.drive.shared.resources.sync_preferences_title
import app.tenshu.ui.legal.OpenSourceLicencesListItem
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.legal.AppPrivacyPolicyListItem
import app.tenshu.ui.chrome.AppTopAppBar
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncPreferencesScreen(
    onBack: () -> Unit,
    onSyncedFolders: () -> Unit = {},
    onLicences: () -> Unit = {},
    viewModel: SyncPreferencesViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCacheLimitDialog by remember { mutableStateOf(false) }

    if (showCacheLimitDialog) {
        CacheLimitDialog(
            selected = CacheCeilingChoices.nearest(uiState.maxLocalBytes),
            onSelect = {
                viewModel.setMaxLocalBytes(it)
                showCacheLimitDialog = false
            },
            onDismiss = { showCacheLimitDialog = false },
        )
    }

    Scaffold(
        topBar = {
            AppTopAppBar(title = stringResource(Res.string.sync_preferences_title), onBack = onBack)
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Scrolls so the rows after the offline-file list stay reachable however long it is.
                .verticalScroll(rememberScrollState())
        ) {
            // Entry to the Synced Folders auto-backup screen (its folder list is too
            // large to inline here — docs/proposals/drive-synced-folders.md §5).
            AppListItem(
                headline = stringResource(Res.string.sync_preferences_synced_folders_title),
                supportingText = stringResource(Res.string.sync_preferences_synced_folders_body),
                onClick = onSyncedFolders,
            )
            HorizontalDivider()
            AppListItem(
                headline = stringResource(Res.string.sync_preferences_keep_offline_title),
                supportingText = stringResource(Res.string.sync_preferences_keep_offline_body),
                trailingContent = {
                    Switch(
                        checked = uiState.keepOfflineByDefault,
                        onCheckedChange = viewModel::setKeepOfflineByDefault
                    )
                }
            )
            HorizontalDivider()
            // The local-cache ceiling (retention-quota-model.md Face B / D6). ⚠ The copy
            // says CACHE deliberately: pinned files are outside this limit, so a label
            // promising a total footprint would be a promise we do not enforce.
            AppListItem(
                headline = stringResource(Res.string.sync_preferences_cache_limit_title),
                supportingText = stringResource(Res.string.sync_preferences_cache_limit_body),
                onClick = { showCacheLimitDialog = true },
                trailingContent = { Text(cacheLimitLabel(uiState.maxLocalBytes)) },
            )
            HorizontalDivider()
            AppListItem(
                headline = stringResource(Res.string.sync_preferences_offline_files_title),
                supportingText = if (uiState.offlineFiles.isEmpty()) {
                    stringResource(Res.string.sync_preferences_offline_files_none)
                } else {
                    stringResource(
                        Res.string.sync_preferences_offline_files_count,
                        uiState.offlineFiles.size,
                    )
                }
            )
            uiState.offlineFiles.forEach { path ->
                AppListItem(
                    headline = path.substringAfterLast('/'),
                    supportingText = path,
                    trailingContent = {
                        IconButton(onClick = { viewModel.removeOfflineFile(path) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(
                                    Res.string.sync_preferences_offline_files_remove
                                )
                            )
                        }
                    }
                )
            }
            HorizontalDivider()
            // This screen is Drive's Settings (the home screen's settings action opens it), so the
            // legal entries live here: the in-app privacy policy (App Store 5.1.1(i), Play User Data
            // policy), then the open-source notices this binary ships (docs/oss-licences.md).
            AppPrivacyPolicyListItem()
            OpenSourceLicencesListItem(onClick = onLicences)
        }
    }
}

/**
 * The ceiling chooser. A fixed radio list rather than a slider or a text field: the
 * value only has to be roughly right, and a list is the one shape that cannot produce
 * a nonsense number.
 *
 * ⚠ The body copy must not promise immediate eviction — enforcement is the offline
 * keeper's periodic sweep, so lowering the limit takes effect on its next pass.
 */
@Composable
private fun CacheLimitDialog(
    selected: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sync_preferences_cache_limit_dialog_title)) },
        text = {
            Column {
                Text(stringResource(Res.string.sync_preferences_cache_limit_dialog_body))
                CacheCeilingChoices.values.forEach { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = choice == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(choice) },
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(cacheLimitLabel(choice))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.sync_preferences_cache_limit_dismiss))
            }
        },
    )
}

/** `0` reads as "No limit" — a real choice, not an unset state. */
@Composable
private fun cacheLimitLabel(bytes: Long): String =
    if (bytes <= CacheCeilingChoices.NO_LIMIT) {
        stringResource(Res.string.sync_preferences_cache_limit_none)
    } else {
        formatByteSize(bytes)
    }
