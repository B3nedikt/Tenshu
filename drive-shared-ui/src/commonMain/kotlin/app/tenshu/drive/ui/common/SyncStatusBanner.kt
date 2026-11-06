package app.tenshu.drive.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tenshu.ui.feedback.ControlBannerAction
import app.tenshu.ui.feedback.rememberControlBannerAction
import app.tenshu.drive.data.StorageSetupLauncher
import app.tenshu.drive.data.formatByteSize
import app.tenshu.drive.domain.SyncBannerState
import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.UpdateTarget
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.domain.BackupWaitCause
import app.tenshu.drive.shared.resources.sync_banner_backup_waiting_charging
import app.tenshu.drive.shared.resources.sync_banner_backup_waiting_charging_one
import app.tenshu.drive.shared.resources.sync_banner_backup_waiting_wifi
import app.tenshu.drive.shared.resources.sync_banner_backup_waiting_wifi_one
import app.tenshu.drive.shared.resources.sync_banner_deferred_action
import app.tenshu.drive.shared.resources.sync_banner_device_unrecognized_action
import app.tenshu.drive.shared.resources.sync_banner_device_unrecognized_text
import app.tenshu.drive.shared.resources.sync_banner_disconnected_text
import app.tenshu.drive.shared.resources.sync_banner_deferred_text
import app.tenshu.drive.shared.resources.sync_banner_deferred_text_one
import app.tenshu.drive.shared.resources.sync_banner_local_only_action
import app.tenshu.drive.shared.resources.sync_banner_storage_full_action
import app.tenshu.drive.shared.resources.sync_banner_storage_full_text
import app.tenshu.drive.shared.resources.sync_banner_local_only_text
import app.tenshu.drive.shared.resources.sync_banner_locked_action
import app.tenshu.drive.shared.resources.sync_banner_locked_text
import app.tenshu.drive.shared.resources.sync_banner_needs_attention_text
import app.tenshu.drive.shared.resources.sync_banner_needs_app_update_action
import app.tenshu.drive.shared.resources.sync_banner_needs_app_update_both
import app.tenshu.drive.shared.resources.sync_banner_needs_app_update_control
import app.tenshu.drive.shared.resources.sync_banner_needs_app_update_this_app
import app.tenshu.drive.shared.resources.sync_banner_needs_update_text
import app.tenshu.drive.shared.resources.sync_banner_offline_text
import app.tenshu.drive.shared.resources.sync_banner_stop_backup_action
import app.tenshu.drive.shared.resources.sync_banner_uploading_retrying_text
import app.tenshu.drive.shared.resources.sync_banner_uploading_text
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Persistent, account-wide sync-status banner (local-first reframe, ADR 0019;
 * connectivity signal `docs/proposals/drive-connectivity-service.md`). Rendered
 * above the file list when [app.tenshu.drive.domain.SyncBanner.evaluate] returns
 * a non-null [SyncBannerState]:
 *
 *  - [SyncBannerState.Locked] — Control's realm is sealed; the action opens Control
 *    (where the unlock screen lives) via the platform [StorageSetupLauncher], whose
 *    Android implementation is exactly "launch Control". ⚠ The desktop implementation
 *    is a v1 no-op, so there the button is inert and the *text* carries the
 *    instruction — the same limitation [SyncBannerState.LocalOnly] already has.
 *  - [SyncBannerState.LocalOnly] — no storage subscription; the action routes to
 *    storage setup via the platform [StorageSetupLauncher].
 *  - [SyncBannerState.NeedsUpdate] — this device's schema is behind the realm, so the
 *    proxy has paused pulling (`core-schema-evolution.md` §7.3). Informational: the fix
 *    is an app-store update, which no in-app button can perform, so the text carries
 *    the whole message — including the reassurance that local files are safe and still
 *    upload (push stays allowed while pulls are fenced).
 *  - [SyncBannerState.Disconnected] — Control itself is unreachable; informational, no action.
 *  - [SyncBannerState.DeviceUnrecognized] — the relay holds no registration for this device;
 *    the action opens Control (where pairing lives) via the same [StorageSetupLauncher].
 *  - [SyncBannerState.Offline] — relay unreachable; informational, no action.
 *  - [SyncBannerState.Syncing] — an outbound backlog is uploading; informational.
 *
 * Kept to the global-banner form rather than a per-row badge: each reason is
 * account-wide, so a badge on every row would be pure noise.
 */
@Composable
fun SyncStatusBanner(
    state: SyncBannerState,
    modifier: Modifier = Modifier,
    onSyncNow: (() -> Unit)? = null,
    /**
     * Stop the auto-backup drain (ST-2b). Offered **only** when the banner's figures come from a
     * drain ([SyncBannerState.Syncing.stoppableBackup]) — a hand-started upload has no pause and
     * no queue to resume, so the same word there would mean something else.
     */
    onStopBackup: (() -> Unit)? = null,
    launcher: StorageSetupLauncher = koinInject(),
) {
    val icon: ImageVector = when (state) {
        SyncBannerState.LocalOnly,
        SyncBannerState.Offline,
        SyncBannerState.Disconnected,
        is SyncBannerState.Deferred,
        is SyncBannerState.BackupWaiting -> Icons.Default.CloudOff
        SyncBannerState.Locked -> Icons.Default.Lock
        SyncBannerState.NeedsAttention -> Icons.Default.Warning
        is SyncBannerState.StorageFull -> Icons.Default.Warning
        SyncBannerState.DeviceUnrecognized -> Icons.Default.Warning
        is SyncBannerState.NeedsUpdate,
        is SyncBannerState.NeedsAppUpdate -> Icons.Default.SystemUpdateAlt
        is SyncBannerState.Syncing -> Icons.Default.CloudSync
    }
    // Deferred carries a count, so it can't be a plain StringResource like the others — it
    // resolves the parameterized "Waiting for Wi-Fi. N files" here. The count is FILES held by
    // the metered content gate (`sync-metered-plane-split.md` §8), which is why the copy can also
    // promise "everything else is up to date": since the plane split, metadata really does keep
    // syncing on cellular, so the file list the user is looking at is current.
    val text: String = when (state) {
        SyncBannerState.Locked -> stringResource(Res.string.sync_banner_locked_text)
        SyncBannerState.LocalOnly -> stringResource(Res.string.sync_banner_local_only_text)
        SyncBannerState.NeedsAttention -> stringResource(Res.string.sync_banner_needs_attention_text)
        // ⚠ Interpolates the real figures, unlike every state above it. "Storage full" alone is
        // indistinguishable from a bug to a user who believes they have space — the numbers are
        // what make it checkable, and they are already on the model.
        is SyncBannerState.StorageFull -> stringResource(
            Res.string.sync_banner_storage_full_text,
            formatByteSize(state.usedBytes),
            formatByteSize(state.limitBytes),
        )
        // Names the cause ("removed from your account") and the fix ("pair it again"), because
        // neither is guessable and the state is otherwise indistinguishable from being offline.
        SyncBannerState.DeviceUnrecognized ->
            stringResource(Res.string.sync_banner_device_unrecognized_text)
        // The offending service ids ride on the state for diagnostics but are deliberately
        // not interpolated: they are internal `tenshu/…/service` paths with no app-name
        // mapping yet (§7.3's "name the app(s)" half waits on one).
        is SyncBannerState.NeedsUpdate -> stringResource(Res.string.sync_banner_needs_update_text)
        // ⚠⚠ The app to name is frequently NOT Drive: on Android and desktop one Control-owned
        // proxy serves every app, so CONTROL is the common answer and telling the user to update
        // Drive would leave them stuck after doing exactly what the banner asked.
        is SyncBannerState.NeedsAppUpdate -> when (state.update) {
            UpdateTarget.CONTROL -> stringResource(Res.string.sync_banner_needs_app_update_control)
            UpdateTarget.THIS_APP -> stringResource(Res.string.sync_banner_needs_app_update_this_app)
            UpdateTarget.BOTH -> stringResource(Res.string.sync_banner_needs_app_update_both)
        }
        SyncBannerState.Offline -> stringResource(Res.string.sync_banner_offline_text)
        // Informational, no action: the transport rebinds and the subscriptions retry on their
        // own (`core-client-resubscription.md` R1/R1b). The copy says "your files are safe on this
        // device" because that is the user's actual question, and it is true — local reads and
        // writes are unaffected by the channel being down.
        SyncBannerState.Disconnected ->
            stringResource(Res.string.sync_banner_disconnected_text)
        is SyncBannerState.Syncing -> stringResource(
            // The relay is failing uploads and they wait before retrying (§5.7): the reason
            // replaces the byte figure, which is not moving.
            if (state.relayRetrying) Res.string.sync_banner_uploading_retrying_text else Res.string.sync_banner_uploading_text,
            // 1-based: "Uploading 1 of 3" while the first file is in flight reads correctly,
            // where "0 of 3" reads as though nothing has started. Capped so the last file cannot
            // render as "4 of 3" on the final callback.
            (state.completed + 1).coerceAtMost(state.total.coerceAtLeast(1)),
            state.total.coerceAtLeast(1),
            formatByteSize(state.bytesLeft),
        )
        is SyncBannerState.Deferred ->
            if (state.pendingCount == 1) {
                stringResource(
                    Res.string.sync_banner_deferred_text_one,
                    formatByteSize(state.bytesLeft),
                )
            } else {
                stringResource(
                    Res.string.sync_banner_deferred_text,
                    state.pendingCount,
                    formatByteSize(state.bytesLeft),
                )
            }
        is SyncBannerState.BackupWaiting -> {
            val one = state.pendingCount == 1
            when (state.cause) {
                BackupWaitCause.WIFI ->
                    if (one) stringResource(Res.string.sync_banner_backup_waiting_wifi_one)
                    else stringResource(Res.string.sync_banner_backup_waiting_wifi, state.pendingCount)
                BackupWaitCause.CHARGING ->
                    if (one) stringResource(Res.string.sync_banner_backup_waiting_charging_one)
                    else stringResource(Res.string.sync_banner_backup_waiting_charging, state.pendingCount)
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            // Action buttons: LocalOnly routes to storage setup; Deferred offers the
            // "Sync now over cellular" one-shot override (when a handler is wired). The
            // other states (Offline/Syncing/NeedsAttention) are transient/self-resolving.
            when (state) {
                is SyncBannerState.Locked ->
                    TextButton(onClick = { launcher.openStorageSetup() }) {
                        Text(stringResource(Res.string.sync_banner_locked_action))
                    }
                is SyncBannerState.LocalOnly ->
                    TextButton(onClick = { launcher.openStorageSetup() }) {
                        Text(stringResource(Res.string.sync_banner_local_only_action))
                    }
                // Same destination as [LocalOnly] — Control's Services screen, which is the only
                // surface that shows the plan alongside the real fill level. ⚠ Deliberately NOT an
                // inline "upgrade" CTA: there is no purchase rail on iOS (IAP is unbuilt, and the
                // commerce targets are Android + Desktop), and the user who is out of space may
                // want to delete rather than buy. Services lets them see the position and choose.
                is SyncBannerState.StorageFull ->
                    TextButton(onClick = { launcher.openStorageSetup() }) {
                        Text(stringResource(Res.string.sync_banner_storage_full_action))
                    }
                // Re-pairing lives in Control, so this reuses the same launcher [Locked] does —
                // "launch Control" on Android. ⚠ Inert on desktop (v1 no-op), which is why the
                // text above carries the whole instruction rather than relying on the button.
                is SyncBannerState.DeviceUnrecognized ->
                    TextButton(onClick = { launcher.openStorageSetup() }) {
                        Text(stringResource(Res.string.sync_banner_device_unrecognized_action))
                    }
                is SyncBannerState.Deferred ->
                    if (onSyncNow != null) {
                        TextButton(onClick = onSyncNow) {
                            Text(stringResource(Res.string.sync_banner_deferred_action))
                        }
                    }
                is SyncBannerState.Syncing ->
                    if (state.stoppableBackup && onStopBackup != null) {
                        TextButton(onClick = onStopBackup) {
                            Text(stringResource(Res.string.sync_banner_stop_backup_action))
                        }
                    }
                // ⚠ The shared posture decides, so Drive offers *Update* exactly where every other app
                // does, linking the store or download page of the binary the verdict names
                // (`core-schema-skew-surface.md` §8.5), and only where that page exists.
                is SyncBannerState.NeedsAppUpdate -> {
                    val update = rememberControlBannerAction(UnreachableReason.Skew(state.update))
                    if (update?.kind == ControlBannerAction.Kind.UPDATE) {
                        TextButton(onClick = update.onClick) {
                            Text(stringResource(Res.string.sync_banner_needs_app_update_action))
                        }
                    }
                }
                else -> {}
            }
        }
    }
}
