package app.tenshu.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tenshu.reachability.SuiteSyncProblem
import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.evaluateSuiteSyncProblem
import app.tenshu.reachability.withOfflineDelay
import kotlinx.coroutines.flow.Flow
import app.tenshu.ui.resources.Res
import app.tenshu.ui.resources.suite_sync_device_unrecognized
import app.tenshu.ui.resources.suite_sync_locked
import app.tenshu.ui.resources.suite_sync_locked_action
import app.tenshu.ui.resources.suite_sync_manage_storage
import app.tenshu.ui.resources.suite_sync_needs_attention
import app.tenshu.ui.resources.suite_sync_offline
import app.tenshu.ui.resources.suite_sync_open_control
import app.tenshu.ui.resources.suite_sync_storage_full
import app.tenshu.ui.storage.rememberControlServicesLauncher
import org.jetbrains.compose.resources.stringResource
import org.tenshu.storage.common.RealmStorageState

/**
 * The one sync strip an app shows at the top (`suite-sync-status.md` SS-1) — the surface half of
 * [SuiteSyncProblem].
 *
 * ⚠⚠ **One at a time.** Apps used to stack their Control banner above their storage banner; this
 * draws exactly the strip [problem] names and nothing for [SuiteSyncProblem.None].
 *
 * ⚠ The Control and storage strips stay **the app's own** ([controlBanner], [storageBanner]): their
 * copy names the app and its install and update routes, and moving nine apps' wording is its own
 * step. What is new is the health half — locked, device not recognised, needs attention, offline —
 * which until now only Drive could say, so a phone the relay had stopped accepting showed Photos as
 * a grid of grey tiles with nothing on screen saying why.
 *
 * ⚠ Actions open Control where acting there fixes it, and are omitted where no route exists (ADR
 * 0025: a button that does nothing is worse than none). [SuiteSyncProblem.Offline] has none: it
 * recovers by itself.
 */
@Composable
fun SuiteSyncBanner(
    problem: SuiteSyncProblem,
    controlBanner: @Composable (UnreachableReason) -> Unit,
    storageBanner: @Composable (RealmStorageState) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (problem) {
        SuiteSyncProblem.None -> Unit
        is SuiteSyncProblem.Control -> controlBanner(problem.reason)
        SuiteSyncProblem.NoPlan -> storageBanner(RealmStorageState.NoPlan)
        is SuiteSyncProblem.StorageFull -> {
            val figures = problem.state
            if (figures != null) {
                storageBanner(figures)
            } else {
                // The relay's own `507` before the storage row caught up: no figures to show.
                HealthStrip(
                    text = stringResource(Res.string.suite_sync_storage_full),
                    icon = Icons.Default.Warning,
                    error = true,
                    actionLabel = stringResource(Res.string.suite_sync_manage_storage),
                    onAction = rememberControlServicesLauncher(),
                    modifier = modifier,
                )
            }
        }
        SuiteSyncProblem.Locked -> HealthStrip(
            text = stringResource(Res.string.suite_sync_locked),
            icon = Icons.Default.Lock,
            error = false,
            actionLabel = stringResource(Res.string.suite_sync_locked_action),
            onAction = rememberControlServicesLauncher(),
            modifier = modifier,
        )
        SuiteSyncProblem.DeviceUnrecognized -> HealthStrip(
            text = stringResource(Res.string.suite_sync_device_unrecognized),
            icon = Icons.Default.Warning,
            error = true,
            actionLabel = stringResource(Res.string.suite_sync_open_control),
            onAction = rememberControlServicesLauncher(),
            modifier = modifier,
        )
        SuiteSyncProblem.NeedsAttention -> HealthStrip(
            text = stringResource(Res.string.suite_sync_needs_attention),
            icon = Icons.Default.Warning,
            error = true,
            actionLabel = stringResource(Res.string.suite_sync_open_control),
            onAction = rememberControlServicesLauncher(),
            modifier = modifier,
        )
        SuiteSyncProblem.Offline -> HealthStrip(
            text = stringResource(Res.string.suite_sync_offline),
            icon = Icons.Default.CloudOff,
            error = false,
            actionLabel = null,
            onAction = null,
            modifier = modifier,
        )
    }
}

/**
 * The strip [SuiteSyncBanner] should show, from the app's Control verdict, its storage state and
 * Control's sync-health `connectivity` stream.
 *
 * ⚠ The stream is the app's to build (`client.syncService.syncHealth().map { it?.connectivity?.get() }`)
 * because `:ui-core` holds no service client. Pass the same instance across recompositions —
 * `remember` it — or the offline delay restarts on every one.
 */
@Composable
fun rememberSuiteSyncProblem(
    reason: UnreachableReason?,
    storage: RealmStorageState,
    connectivity: Flow<String?>,
): SuiteSyncProblem {
    val delayed = remember(connectivity) { connectivity.withOfflineDelay() }
    val current by delayed.collectAsState(initial = null)
    return evaluateSuiteSyncProblem(reason, current, storage)
}

/**
 * ⚠ `errorContainer` only where nothing syncs and waiting will not fix it (device not recognised,
 * storage full, needs attention), `secondaryContainer` where the user's next unlock or the network
 * coming back does — `ErrorBanner`'s own distinction.
 */
@Composable
private fun HealthStrip(
    text: String,
    icon: ImageVector,
    error: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier,
) {
    if (error) {
        ErrorBanner(text = text, modifier = modifier, icon = icon, actionLabel = actionLabel, onAction = onAction)
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Text(text = text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
