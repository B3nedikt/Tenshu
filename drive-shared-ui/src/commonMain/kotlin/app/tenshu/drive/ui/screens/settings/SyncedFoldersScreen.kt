package app.tenshu.drive.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.synced_folders_add_failed_body
import app.tenshu.drive.shared.resources.synced_folders_add_failed_title
import app.tenshu.drive.shared.resources.synced_folders_add_folder
import app.tenshu.drive.shared.resources.synced_folders_control_update_body
import app.tenshu.drive.shared.resources.synced_folders_control_update_dismiss
import app.tenshu.drive.shared.resources.synced_folders_control_update_title
import app.tenshu.drive.shared.resources.synced_folders_back_up_now
import app.tenshu.drive.shared.resources.synced_folders_override_both_body
import app.tenshu.drive.shared.resources.synced_folders_override_both_title
import app.tenshu.drive.shared.resources.synced_folders_override_charging_body
import app.tenshu.drive.shared.resources.synced_folders_override_charging_title
import app.tenshu.drive.shared.resources.synced_folders_override_confirm
import app.tenshu.drive.shared.resources.synced_folders_override_dismiss
import app.tenshu.drive.shared.resources.synced_folders_override_metered_body
import app.tenshu.drive.shared.resources.synced_folders_override_metered_title
import app.tenshu.drive.shared.resources.synced_folders_resume
import app.tenshu.drive.shared.resources.synced_folders_stop
import app.tenshu.drive.shared.resources.synced_folders_charging_body
import app.tenshu.drive.shared.resources.synced_folders_charging_title
import app.tenshu.drive.shared.resources.synced_folders_document_folders_header
import app.tenshu.drive.shared.resources.synced_folders_folders_header
import app.tenshu.drive.shared.resources.synced_folders_remove_folder
import app.tenshu.drive.shared.resources.synced_folders_item_count
import app.tenshu.drive.shared.resources.synced_folders_master_body
import app.tenshu.drive.shared.resources.synced_folders_master_title
import app.tenshu.drive.shared.resources.synced_folders_media_cta
import app.tenshu.drive.shared.resources.synced_folders_photo_location_cta
import app.tenshu.drive.shared.resources.synced_folders_photo_location_body
import app.tenshu.drive.shared.resources.synced_folders_photo_location_title
import app.tenshu.drive.shared.resources.synced_folders_media_denied_body
import app.tenshu.drive.shared.resources.synced_folders_media_denied_title
import app.tenshu.drive.shared.resources.synced_folders_media_partial_body
import app.tenshu.drive.shared.resources.synced_folders_media_partial_title
import app.tenshu.drive.shared.resources.synced_folders_new_folders_body
import app.tenshu.drive.shared.resources.synced_folders_new_folders_title
import app.tenshu.drive.shared.resources.synced_folders_no_folders
import app.tenshu.drive.shared.resources.synced_folders_status_backing_up
import app.tenshu.drive.shared.resources.synced_folders_status_finished
import app.tenshu.drive.shared.resources.synced_folders_status_finished_all_held
import app.tenshu.drive.shared.resources.synced_folders_status_finished_some_held
import app.tenshu.drive.shared.resources.synced_folders_status_finished_with_failures
import app.tenshu.drive.shared.resources.synced_folders_status_finished_could_not_check
import app.tenshu.drive.shared.resources.synced_folders_status_finished_with_reason
import app.tenshu.drive.shared.resources.synced_folders_status_paused_charging
import app.tenshu.drive.shared.resources.synced_folders_status_paused_metered
import app.tenshu.drive.shared.resources.synced_folders_status_paused_network
import app.tenshu.drive.shared.resources.synced_folders_status_paused_by_user
import app.tenshu.drive.shared.resources.synced_folders_status_paused_waiting
import app.tenshu.drive.shared.resources.synced_folders_status_paused_waiting_one
import app.tenshu.drive.shared.resources.synced_folders_status_paused_space
import app.tenshu.drive.shared.resources.synced_folders_status_paused_storage
import app.tenshu.drive.shared.resources.synced_folders_status_up_to_date
import app.tenshu.drive.shared.resources.synced_folders_storage_body
import app.tenshu.drive.shared.resources.synced_folders_storage_cta
import app.tenshu.drive.shared.resources.synced_folders_storage_title
import app.tenshu.drive.shared.resources.synced_folders_title
import app.tenshu.drive.shared.resources.synced_folders_videos_body
import app.tenshu.drive.shared.resources.synced_folders_videos_title
import app.tenshu.drive.shared.resources.synced_folders_wifi_body
import app.tenshu.drive.shared.resources.synced_folders_wifi_title
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.tenshu.drive.data.BackupFolderPickResult
import app.tenshu.ingest.backup.MediaAccess
import app.tenshu.drive.data.StorageSetupLauncher
import app.tenshu.ingest.backup.BackupConstraint
import app.tenshu.ingest.backup.BackupFailureKind
import app.tenshu.ingest.backup.BackupPauseReason
import app.tenshu.ingest.backup.BackupState
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.AppTopAppBar
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Synced Folders auto-backup settings (`docs/proposals/drive-synced-folders.md`
 * §5). Master toggle → storage gate banner → Wi-Fi/charging/video policy →
 * back-up-new-folders → per-folder list → status footer. Reached from the
 * "Synced folders" row on [SyncPreferencesScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncedFoldersScreen(
    onBack: () -> Unit,
    viewModel: SyncedFoldersViewModel = koinViewModel(),
    storageSetupLauncher: StorageSetupLauncher = koinInject(),
    // Launches the platform folder picker: Android uses SAF; desktop asks Control for a
    // security-scoped grant. `null` in previews/tests.
    onAddBackupFolder: (((BackupFolderPickResult) -> Unit) -> Unit)? = null,
    // Android-only: launches the media-permission dialog (§5). `null` on desktop,
    // which has no media grant — the access notice is then unreachable.
    onRequestMediaAccess: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    // The last pick that needs telling: Control too old for the picker, or the pick failed.
    var pickProblem by remember { mutableStateOf<BackupFolderPickResult?>(null) }

    pickProblem?.let { problem ->
        val updateRequired = problem is BackupFolderPickResult.Unavailable
        AlertDialog(
            onDismissRequest = { pickProblem = null },
            title = {
                Text(
                    stringResource(
                        if (updateRequired) Res.string.synced_folders_control_update_title
                        else Res.string.synced_folders_add_failed_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (updateRequired) Res.string.synced_folders_control_update_body
                        else Res.string.synced_folders_add_failed_body,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { pickProblem = null }) {
                    Text(stringResource(Res.string.synced_folders_control_update_dismiss))
                }
            },
        )
    }

    // The grant can change from system settings while Drive is backgrounded, and a
    // partial selection can expire on its own, so it is re-read on every entry
    // rather than trusted from construction time.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    uiState.backUpNowConfirmation?.let { ignore ->
        BackUpNowOverrideDialog(
            ignore = ignore,
            onConfirm = viewModel::confirmBackUpNowOverride,
            onDismiss = viewModel::dismissBackUpNowOverride,
        )
    }

    Scaffold(
        topBar = {
            AppTopAppBar(title = stringResource(Res.string.synced_folders_title), onBack = onBack)
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Master toggle — disabled when there is no storage to back up to (§3.6).
            AppListItem(
                headline = stringResource(Res.string.synced_folders_master_title),
                supportingText = stringResource(Res.string.synced_folders_master_body),
                trailingContent = {
                    Switch(
                        checked = uiState.enabled,
                        onCheckedChange = { checked ->
                            viewModel.setEnabled(checked)
                            // Turning backup on is the moment the grant is actually
                            // needed, so that is where the dialog belongs — not at
                            // app start, where it would be an unexplained prompt.
                            if (checked && uiState.mediaAccess != MediaAccess.FULL) {
                                onRequestMediaAccess?.invoke()
                            }
                        },
                        enabled = uiState.hasStorage,
                    )
                }
            )

            if (!uiState.hasStorage) {
                // Inert storage-gate card + CTA (§3.6) — never a live toggle that
                // silently does nothing.
                StorageGateBanner(onGetStorage = storageSetupLauncher::openStorageSetup)
                return@Column
            }

            // Media-access notice (§5). Advisory, not a gate: under PARTIAL the
            // selected photos genuinely do back up, so the screen explains what is
            // limited rather than blocking. Under DENIED nothing is enumerable, so
            // the folder list below is empty and this is the only explanation for it.
            if (uiState.enabled && uiState.mediaAccess != MediaAccess.FULL &&
                onRequestMediaAccess != null
            ) {
                MediaAccessNotice(
                    access = uiState.mediaAccess,
                    onFix = onRequestMediaAccess,
                )
            }

            // T-P4 (`timeline-photos.md` §11.1): a SEPARATE notice, deliberately.
            //
            // ⚠⚠ It cannot ride `mediaAccess`: the grant that matters here is
            // `ACCESS_MEDIA_LOCATION`, and the population that lacks it is precisely the one whose
            // library access is already FULL — so every branch keyed on `mediaAccess != FULL`
            // (including the request above) skips them forever. That is how the permission shipped
            // declared, bundled into a request array, and never actually asked for.
            //
            // ⚠ Not an error and not a gate: backup is working. What is missing is one thing the
            // photos could otherwise tell Timeline, so it reads as an offer rather than a fault.
            if (uiState.enabled && uiState.mediaAccess == MediaAccess.FULL &&
                !uiState.photoLocationGranted && onRequestMediaAccess != null
            ) {
                PhotoLocationNotice(onFix = onRequestMediaAccess)
            }

            if (uiState.enabled) {
                HorizontalDivider()
                AppListItem(
                    headline = stringResource(Res.string.synced_folders_wifi_title),
                    supportingText = stringResource(Res.string.synced_folders_wifi_body),
                    trailingContent = {
                        Switch(checked = uiState.wifiOnly, onCheckedChange = viewModel::setWifiOnly)
                    }
                )
                AppListItem(
                    headline = stringResource(Res.string.synced_folders_charging_title),
                    supportingText = stringResource(Res.string.synced_folders_charging_body),
                    trailingContent = {
                        Switch(
                            checked = uiState.chargingOnly,
                            onCheckedChange = viewModel::setChargingOnly,
                        )
                    }
                )
                AppListItem(
                    headline = stringResource(Res.string.synced_folders_videos_title),
                    supportingText = stringResource(Res.string.synced_folders_videos_body),
                    trailingContent = {
                        Switch(checked = uiState.videos, onCheckedChange = viewModel::setVideos)
                    }
                )
                AppListItem(
                    headline = stringResource(Res.string.synced_folders_new_folders_title),
                    supportingText = stringResource(Res.string.synced_folders_new_folders_body),
                    trailingContent = {
                        Switch(
                            checked = uiState.backupNewFolders,
                            onCheckedChange = viewModel::setBackupNewFolders,
                            // Without full access the prompt stands down (the
                            // controller gates it), so an active-looking switch
                            // would be promising something that cannot happen.
                            enabled = uiState.mediaAccess == MediaAccess.FULL,
                        )
                    }
                )

                HorizontalDivider()
                AppListItem(headline = stringResource(Res.string.synced_folders_folders_header))
                if (uiState.buckets.isEmpty()) {
                    AppListItem(
                        headline = stringResource(Res.string.synced_folders_no_folders),
                    )
                } else {
                    uiState.buckets.forEach { bucket ->
                        AppListItem(
                            headline = bucket.name,
                            supportingText = stringResource(
                                Res.string.synced_folders_item_count, bucket.itemCount,
                            ),
                            leadingContent = {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                            },
                            trailingContent = {
                                if (bucket.id in uiState.grantedBucketIds) {
                                    IconButton(onClick = { viewModel.removeGrantedBucket(bucket.id) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(
                                                Res.string.synced_folders_remove_folder,
                                            ),
                                        )
                                    }
                                } else {
                                    Switch(
                                        checked = bucket.selected,
                                        onCheckedChange = { checked ->
                                            viewModel.setBucketSelected(bucket.id, checked)
                                        },
                                    )
                                }
                            }
                        )
                    }
                }

                // User-added folders. Android rows are SAF URIs; desktop rows are rendered
                // in the gallery bucket list from opaque Control-owned grants.
                if (onAddBackupFolder != null) {
                    HorizontalDivider()
                    AppListItem(headline = stringResource(Res.string.synced_folders_document_folders_header))
                    uiState.documentFolders.forEach { folder ->
                        AppListItem(
                            headline = folder.name,
                            leadingContent = {
                                Icon(Icons.Default.Folder, contentDescription = null)
                            },
                            trailingContent = {
                                IconButton(onClick = { viewModel.removeBackupFolder(folder.uri) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(
                                            Res.string.synced_folders_remove_folder,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            onAddBackupFolder {
                                if (it is BackupFolderPickResult.Unavailable ||
                                    it is BackupFolderPickResult.Failed
                                ) {
                                    pickProblem = it
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(Res.string.synced_folders_add_folder))
                    }
                }

                HorizontalDivider()
                // Status + the manual nudge, on one row: the button starts a pass and
                // the line beside it is the answer, including "Paused — on mobile data"
                // when the user's own constraints say not now. Offered only where this
                // process owns the drain (desktop's runs inside Control).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = backupStatusText(uiState.backupState),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (uiState.canBackUpNow) {
                        // ST-2b. Three states, one slot: a running backup offers Stop, a
                        // user-stopped one offers Resume, and anything else offers Back up now.
                        // ⚠ One slot on purpose — Stop and Back up now are opposites, and showing
                        // both would ask the user to work out which applies.
                        when (uiState.backupState) {
                            is BackupState.BackingUp -> TextButton(onClick = viewModel::stopBackup) {
                                Text(stringResource(Res.string.synced_folders_stop))
                            }
                            BackupState.PausedByUser -> TextButton(onClick = viewModel::resumeBackup) {
                                Text(stringResource(Res.string.synced_folders_resume))
                            }
                            else -> TextButton(
                                onClick = viewModel::backUpNow,
                                // Backup being off is a switch away, not a state to nudge.
                                enabled = uiState.enabled,
                            ) {
                                Text(stringResource(Res.string.synced_folders_back_up_now))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Explains a non-[MediaAccess.FULL] grant (§5). Two genuinely different situations,
 * so two messages: [MediaAccess.PARTIAL] backs up *something* but can never see a
 * folder the user did not pick from, while [MediaAccess.DENIED] backs up nothing at
 * all and is the reason the folder list underneath is empty.
 */
/**
 * The photo-location offer (T-P4). Same shape as [MediaAccessNotice] and a different tone: that one
 * explains why backup is not doing what you asked, this one offers something backup could also do.
 *
 * Re-requesting the whole permission array is intentional — the system prompts only for what is
 * missing, so the caller needs no second launcher.
 */
@Composable
private fun PhotoLocationNotice(onFix: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = stringResource(Res.string.synced_folders_photo_location_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(Res.string.synced_folders_photo_location_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        FilledTonalButton(onClick = onFix) {
            Text(stringResource(Res.string.synced_folders_photo_location_cta))
        }
    }
}

@Composable
private fun MediaAccessNotice(access: MediaAccess, onFix: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = stringResource(
                if (access == MediaAccess.PARTIAL) {
                    Res.string.synced_folders_media_partial_title
                } else {
                    Res.string.synced_folders_media_denied_title
                }
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                if (access == MediaAccess.PARTIAL) {
                    Res.string.synced_folders_media_partial_body
                } else {
                    Res.string.synced_folders_media_denied_body
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        FilledTonalButton(onClick = onFix) {
            Text(stringResource(Res.string.synced_folders_media_cta))
        }
    }
}

@Composable
private fun StorageGateBanner(onGetStorage: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = stringResource(Res.string.synced_folders_storage_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(Res.string.synced_folders_storage_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        FilledTonalButton(onClick = onGetStorage) {
            Text(stringResource(Res.string.synced_folders_storage_cta))
        }
    }
}

@Composable
private fun backupStatusText(state: BackupState): String = when (state) {
    BackupState.Disabled,
    BackupState.UpToDate -> stringResource(Res.string.synced_folders_status_up_to_date)

    BackupState.PausedByUser -> stringResource(Res.string.synced_folders_status_paused_by_user)

    is BackupState.BackingUp -> stringResource(
        Res.string.synced_folders_status_backing_up, state.completed + 1, state.total,
    )

    // ⚠⚠ This rendered `state.uploaded` alone, so a drain in which every upload was rejected read
    // "Backed up 0 files" — indistinguishable from a device with nothing to do (§8c).
    is BackupState.JustFinished -> {
        // ⚠ A local: `failureReason` is another module's public property, so no smart cast.
        val reason = state.failureReason
        if (state.failed == 0) {
            // ⚠ A file skipped because the realm already holds it is counted, never dropped: a run
            // whose every file was already backed up otherwise read "Backed up 0 files".
            when {
                state.alreadyInRealm == 0 ->
                    stringResource(Res.string.synced_folders_status_finished, state.uploaded)
                state.uploaded == 0 ->
                    stringResource(Res.string.synced_folders_status_finished_all_held, state.alreadyInRealm)
                else -> stringResource(
                    Res.string.synced_folders_status_finished_some_held, state.uploaded, state.alreadyInRealm,
                )
            }
        } else {
            // A cause the engine knows is copy from this catalogue, never the engine's English (§40).
            // ⚠ A `when` with no `else`: a new kind must fail to compile here, not fall through.
            when (state.failureKind) {
                BackupFailureKind.COULD_NOT_CHECK_REALM -> stringResource(
                    Res.string.synced_folders_status_finished_could_not_check, state.uploaded, state.failed,
                )
                null -> if (reason != null) {
                    stringResource(
                        Res.string.synced_folders_status_finished_with_reason,
                        state.uploaded, state.failed, reason,
                    )
                } else {
                    stringResource(
                        Res.string.synced_folders_status_finished_with_failures, state.uploaded, state.failed,
                    )
                }
            }
        }
    }

    is BackupState.Paused -> {
        val reason = when (state.reason) {
            BackupPauseReason.NO_STORAGE -> stringResource(Res.string.synced_folders_status_paused_storage)
            BackupPauseReason.OUT_OF_SPACE -> stringResource(Res.string.synced_folders_status_paused_space)
            BackupPauseReason.METERED_NETWORK -> stringResource(Res.string.synced_folders_status_paused_metered)
            BackupPauseReason.NOT_CHARGING -> stringResource(Res.string.synced_folders_status_paused_charging)
            BackupPauseReason.NO_NETWORK -> stringResource(Res.string.synced_folders_status_paused_network)
        }
        when (state.waiting) {
            0 -> reason
            1 -> stringResource(Res.string.synced_folders_status_paused_waiting_one, reason)
            else -> stringResource(Res.string.synced_folders_status_paused_waiting, reason, state.waiting)
        }
    }
}

/**
 * *Back up now*'s one-time override (`drive-synced-folders-phase2.md` §4.1a) — "Use mobile data
 * this once?". Shown only when the user's own switch is the one thing in the way; agreeing runs
 * this backup past it and leaves the switch on.
 */
@Composable
private fun BackUpNowOverrideDialog(
    ignore: Set<BackupConstraint>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val metered = BackupConstraint.WIFI_ONLY in ignore
    val charging = BackupConstraint.CHARGING_ONLY in ignore
    val (title, body) = when {
        metered && charging ->
            Res.string.synced_folders_override_both_title to Res.string.synced_folders_override_both_body
        metered ->
            Res.string.synced_folders_override_metered_title to Res.string.synced_folders_override_metered_body
        else ->
            Res.string.synced_folders_override_charging_title to Res.string.synced_folders_override_charging_body
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.synced_folders_override_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.synced_folders_override_dismiss))
            }
        },
    )
}
