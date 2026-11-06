package app.tenshu.control.ui.screens.settings.errors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.data.ErrorEventSummary
import app.tenshu.error.errorCopyKeyForId
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.error.developerLabel
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun ErrorsScreen(
    viewModel: ErrorsViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ErrorsScreenContent(
        uiState = uiState,
        onRowClicked = viewModel::onRowClicked,
        onSendClicked = viewModel::onSendClicked,
        onSendConfirmed = viewModel::onSendConfirmed,
        onSendDismissed = viewModel::onSendDismissed,
        onStatusDismissed = viewModel::onStatusDismissed,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorsScreenContent(
    uiState: ErrorsUiState,
    onRowClicked: (String) -> Unit,
    onSendClicked: () -> Unit = {},
    onSendConfirmed: () -> Unit = {},
    onSendDismissed: () -> Unit = {},
    onStatusDismissed: () -> Unit = {},
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.uploadStatus) {
        when (val status = uiState.uploadStatus) {
            is UploadStatus.Sent -> {
                snackbarHostState.showSnackbar("Sent ${status.count} events to Tenshu.")
                onStatusDismissed()
            }
            is UploadStatus.Failed -> {
                val msg = if (status.message.isNotBlank()) {
                    "Couldn't send error report: ${status.message}"
                } else {
                    "Couldn't send error report."
                }
                snackbarHostState.showSnackbar(msg)
                onStatusDismissed()
            }
            else -> Unit
        }
    }
    Scaffold(
        topBar = { AppTopAppBar(title = "Errors", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(modifier = Modifier.fillMaxSize().appContentWidth()) {
                if (uiState.events.isEmpty()) {
                    EmptyState()
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = true)) {
                        items(uiState.events, key = { it.eventId }) { event ->
                            ErrorEventRow(
                                event = event,
                                expanded = uiState.expandedEventId == event.eventId,
                                onClick = { onRowClicked(event.eventId) },
                            )
                        }
                    }
                }
                // Phase 6: "Send error report" CTA. Disabled when no Console URL
                // is wired (canUpload) or no events are captured yet.
                SendErrorReportButton(
                    enabled = uiState.canUpload && uiState.events.isNotEmpty(),
                    busy = uiState.uploadStatus == UploadStatus.Sending,
                    onClick = onSendClicked,
                )
                // Retention footer copy (v0.2 review notes on Settings → Errors).
                Text(
                    text = "Errors are stored on this device and synced to your other devices. " +
                        "We keep up to 500 entries and remove anything older than 30 days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (uiState.uploadStatus == UploadStatus.Confirming) {
        SendErrorReportDialog(
            eventCount = uiState.events.size,
            onConfirm = onSendConfirmed,
            onDismiss = onSendDismissed,
        )
    }
}

@Composable
private fun SendErrorReportButton(
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Button(
            onClick = onClick,
            enabled = enabled && !busy,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(if (busy) "Sending…" else "Send error report")
        }
    }
}

@Composable
private fun SendErrorReportDialog(
    eventCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send error report?") },
        text = {
            Text(
                "Send $eventCount diagnostic events to Tenshu? " +
                    "No identifying information (device id, account id, file paths) is included."
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Send") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Text(
            text = "No errors captured.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = "Recent errors will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorEventRow(
    event: ErrorEventSummary,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        AppListItem(
            headline = errorCopyKeyForId(event.errorTypeId).developerLabel(),
            supportingText = relativeTime(event.capturedAtMillis),
            leadingContent = {
                Icon(Icons.Default.ErrorOutline, contentDescription = null)
            },
            onClick = onClick,
        )
        if (expanded) {
            ErrorEventDetail(event = event)
        }
    }
}

@Composable
private fun ErrorEventDetail(event: ErrorEventSummary) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            DetailRow(label = "Error id", value = event.errorTypeId)
            if (event.operationId.isNotEmpty()) {
                DetailRow(label = "Operation", value = event.operationId)
            }
            DetailRow(label = "Captured at", value = formatAbsolute(event.capturedAtMillis))
            DetailRow(label = "App", value = "${event.appId} ${event.appVersion}")
            DetailRow(label = "OS", value = event.osLabel)
            DetailRow(label = "Stack depth", value = event.callstackDepth.toString())
            event.internalDetail?.let { detail ->
                DetailRow(label = "Detail (proxy-local)", value = detail)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun relativeTime(capturedAtMillis: Long): String {
    val deltaMs = (System.currentTimeMillis() - capturedAtMillis).coerceAtLeast(0L)
    val seconds = deltaMs / 1_000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days >= 1 -> "${days}d ago"
        hours >= 1 -> "${hours}h ago"
        minutes >= 1 -> "${minutes}m ago"
        else -> "just now"
    }
}

private fun formatAbsolute(capturedAtMillis: Long): String {
    // Coarse, locale-agnostic — proper formatting rides the i18n catalogue
    // when the localised label slot is built.
    return kotlin.time.Instant.fromEpochMilliseconds(capturedAtMillis).toString()
}

@Preview
@Composable
fun ErrorsScreenPreview() {
    Surface {
        ErrorsScreenContent(
            uiState = ErrorsUiState(
                events = listOf(
                    ErrorEventSummary(
                        eventId = "1",
                        errorTypeId = "tenshu/core/error/dispatch/missing-parameter",
                        operationId = "tenshu/sync/operation/createrealm",
                        callstackDepth = 1,
                        capturedAtMillis = System.currentTimeMillis() - 60_000,
                        deviceId = "device-1",
                        appId = "control",
                        appVersion = "1.0",
                        osLabel = "macOS 14",
                        internalDetail = null,
                    ),
                    ErrorEventSummary(
                        eventId = "2",
                        errorTypeId = "tenshu/core/error/internal",
                        operationId = "",
                        callstackDepth = 0,
                        capturedAtMillis = System.currentTimeMillis() - 3_600_000,
                        deviceId = "device-1",
                        appId = "control",
                        appVersion = "1.0",
                        osLabel = "macOS 14",
                        internalDetail = "java.lang.NullPointerException at SomeClass.foo:42",
                    ),
                ),
                expandedEventId = "2",
            ),
            onRowClicked = {},
            onBack = {},
        )
    }
}
