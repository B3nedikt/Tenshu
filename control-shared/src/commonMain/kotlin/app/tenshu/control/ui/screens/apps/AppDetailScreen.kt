package app.tenshu.control.ui.screens.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.data.AppDetail
import app.tenshu.control.data.GrantState
import app.tenshu.control.data.PermissionRow
import app.tenshu.control.data.UninstallScope
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.app_detail_fallback_title
import app.tenshu.control.shared.resources.app_detail_installed_on_plural
import app.tenshu.control.shared.resources.app_detail_installed_on_singular
import app.tenshu.control.shared.resources.app_detail_last_used
import app.tenshu.control.shared.resources.app_detail_no_permissions
import app.tenshu.control.shared.resources.app_detail_not_installed
import app.tenshu.control.shared.resources.app_detail_permissions_heading
import app.tenshu.control.shared.resources.app_detail_permissions_declared_note
import app.tenshu.control.shared.resources.app_detail_services_heading
import app.tenshu.control.shared.resources.app_detail_unrecognised_note
import app.tenshu.control.shared.resources.app_detail_uninstall
import app.tenshu.control.shared.resources.app_detail_uninstall_all_devices
import app.tenshu.control.shared.resources.app_detail_uninstall_body
import app.tenshu.control.shared.resources.app_detail_uninstall_cancel
import app.tenshu.control.shared.resources.app_detail_uninstall_confirm
import app.tenshu.control.shared.resources.app_detail_uninstall_this_device
import app.tenshu.control.shared.resources.app_detail_uninstall_title
import app.tenshu.control.shared.resources.app_detail_version
import app.tenshu.ui.chrome.AppDialog
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * Per-app detail (`control-ia.md` §4.2): the permission list with a tri-state
 * grant control per row, the services the app consumes (cross-linking to the
 * Services section), install info, and the destructive Revoke-all / Uninstall
 * actions. Deferred per the track scope: Rei publication chain, per-app data
 * summary, activity log, billing.
 */
@Composable
fun AppDetailScreen(
    viewModel: AppDetailViewModel,
    onBack: () -> Unit,
    onServiceClick: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AppDetailScreenContent(
        detail = uiState.detail,
        loading = uiState.loading,
        onUninstall = viewModel::uninstall,
        onServiceClick = onServiceClick,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreenContent(
    detail: AppDetail?,
    loading: Boolean,
    onUninstall: (UninstallScope) -> Unit,
    onServiceClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val fallbackTitle = stringResource(Res.string.app_detail_fallback_title)
    Scaffold(
        topBar = { AppTopAppBar(title = detail?.app?.name ?: fallbackTitle, onBack = onBack) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                detail == null && loading -> Unit // first frame; nothing to show yet
                detail == null -> Text(
                    text = stringResource(Res.string.app_detail_not_installed),
                    modifier = Modifier.appContentWidth().padding(16.dp),
                )

                else -> AppDetailBody(
                    detail = detail,
                    onUninstall = onUninstall,
                    onServiceClick = onServiceClick,
                )
            }
        }
    }
}

@Composable
private fun AppDetailBody(
    detail: AppDetail,
    onUninstall: (UninstallScope) -> Unit,
    onServiceClick: (String) -> Unit,
) {
    var showUninstall by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .appContentWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppHeader(detail)

        HorizontalDivider()

        Text(
            stringResource(Res.string.app_detail_permissions_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        // ⚠ A declaration, not a control (control-app-catalogue.md C0 / D6). Grants are never
        // consulted on the dispatch path, so a switch here would promise a boundary that does
        // not exist. Per D6 a per-app permission control is a *third-party* feature — first-party
        // apps are realm co-owners — so for the apps we ship today this read-only list is the
        // accurate surface rather than a placeholder for one.
        Text(
            stringResource(Res.string.app_detail_permissions_declared_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (detail.permissions.isEmpty()) {
            Text(
                stringResource(Res.string.app_detail_no_permissions),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            detail.permissions.forEach { row ->
                PermissionRowItem(row = row)
            }
        }

        if (detail.services.isNotEmpty()) {
            HorizontalDivider()
            Text(
                stringResource(Res.string.app_detail_services_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            ServiceChips(services = detail.services, onServiceClick = onServiceClick)
        }

        HorizontalDivider()

        Button(
            onClick = { showUninstall = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Text(stringResource(Res.string.app_detail_uninstall))
        }
    }

    if (showUninstall) {
        UninstallDialog(
            appName = detail.app.name,
            onDismiss = { showUninstall = false },
            onConfirm = { scope ->
                showUninstall = false
                onUninstall(scope)
            },
        )
    }
}

@Composable
private fun AppHeader(detail: AppDetail) {
    val app = detail.app
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (app.shortDescription.isNotBlank()) {
            Text(app.shortDescription, style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (app.version.isNotBlank()) {
                Text(
                    stringResource(Res.string.app_detail_version, app.version),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!app.recognised) UnrecognisedBadge()
            if (!app.verified) UnverifiedBadge()
        }
        // The detail screen is where the bare id gets explained, rather than left to be read as
        // a naming bug (C3). Deliberately does not guess *which* cause applies — this Control
        // being older than the app is the reachable one today, an unbundled third-party app the
        // one that arrives with Rei — so the copy names the likely cause without asserting it.
        if (!app.recognised) {
            Text(
                stringResource(Res.string.app_detail_unrecognised_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Singular/plural twin strings (matches home_event_multiple_added pattern).
        Text(
            text = if (app.deviceCount == 1) {
                stringResource(
                    Res.string.app_detail_installed_on_singular,
                    formatDate(app.installedAt),
                )
            } else {
                stringResource(
                    Res.string.app_detail_installed_on_plural,
                    formatDate(app.installedAt),
                    app.deviceCount,
                )
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PermissionRowItem(row: PermissionRow) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(row.humanLabel, style = MaterialTheme.typography.bodyLarge)
        val lastUsedText = if (row.lastUsedAt > 0L) {
            stringResource(Res.string.app_detail_last_used, formatDate(row.lastUsedAt))
        } else {
            null
        }
        val caption = buildString {
            row.scopeLabel?.let { append(it) }
            if (lastUsedText != null) {
                if (isNotEmpty()) append(" · ")
                append(lastUsedText)
            }
        }
        if (caption.isNotEmpty()) {
            Text(caption, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceChips(services: List<String>, onServiceClick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        services.forEach { serviceId ->
            AssistChip(
                onClick = { onServiceClick(serviceId) },
                label = { Text(serviceLabel(serviceId)) },
            )
        }
    }
}

@Composable
private fun UninstallDialog(
    appName: String,
    onDismiss: () -> Unit,
    onConfirm: (UninstallScope) -> Unit,
) {
    var scope by remember { mutableStateOf(UninstallScope.ThisDevice) }
    AppDialog(
        title = stringResource(Res.string.app_detail_uninstall_title, appName),
        onDismissRequest = onDismiss,
        confirmLabel = stringResource(Res.string.app_detail_uninstall_confirm),
        onConfirm = { onConfirm(scope) },
        dismissLabel = stringResource(Res.string.app_detail_uninstall_cancel),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScopeOption(
                    label = stringResource(Res.string.app_detail_uninstall_this_device),
                    selected = scope == UninstallScope.ThisDevice,
                    onSelect = { scope = UninstallScope.ThisDevice },
                )
                ScopeOption(
                    label = stringResource(Res.string.app_detail_uninstall_all_devices),
                    selected = scope == UninstallScope.AllDevices,
                    onSelect = { scope = UninstallScope.AllDevices },
                )
                Text(
                    stringResource(Res.string.app_detail_uninstall_body),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun ScopeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Short, human-ish label for a service id (e.g. `tenshu/file/system` → "file/system"). */
private fun serviceLabel(serviceId: String): String =
    serviceId.removePrefix("tenshu/")

private fun formatDate(epochMillis: Long): String =
    Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()
