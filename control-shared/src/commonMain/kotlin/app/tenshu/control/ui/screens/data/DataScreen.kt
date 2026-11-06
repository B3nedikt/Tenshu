package app.tenshu.control.ui.screens.data

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.data.RealmDataResetOutcome
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.data_delete_all_action
import app.tenshu.control.shared.resources.data_delete_dialog_body
import app.tenshu.control.shared.resources.data_delete_dialog_cancel
import app.tenshu.control.shared.resources.data_delete_dialog_confirm
import app.tenshu.control.shared.resources.data_delete_dialog_title
import app.tenshu.control.shared.resources.data_delete_error_failed
import app.tenshu.control.shared.resources.data_delete_error_unsupported
import app.tenshu.control.shared.resources.data_delete_result_many
import app.tenshu.control.shared.resources.data_delete_result_none
import app.tenshu.control.shared.resources.data_delete_result_one
import app.tenshu.control.shared.resources.data_delete_result_stopped
import app.tenshu.control.shared.resources.data_delete_stop_action
import app.tenshu.control.shared.resources.data_empty
import app.tenshu.control.shared.resources.data_shares_subtitle
import app.tenshu.control.shared.resources.data_shares_title
import app.tenshu.control.shared.resources.data_title
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource

/**
 * Data section (`control-ia.md` §5 / `docs/proposals/core-realm-data.md` Phase 4). Renders the
 * realm's data inventory as a **generic walker**: one card per realm-data type
 * (`DataViewModel` enumerates them via the `RealmData` marker — no hard-coded list), followed
 * by the cross-cutting **Shares** row (§5.3). Type cards are a meta-view, not a browser; v1
 * has no in-Control drill-in (browsing a type's items routes into its owning app — follow-up).
 *
 * Each card carries the destructive **Delete all…** verb
 * (`docs/proposals/control-data-reset.md` §8) — the one place in the product where realm data
 * can be reset, because the unit of reset is a data *type*, never an app (D1: Timeline's
 * importer writes the same `Workout`s Fit records, so "clear Fit's data" would delete another
 * app's work).
 */
@Composable
fun DataScreen(
    viewModel: DataViewModel,
    onOpenShares: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Re-check which types hold data on every visit and every return to the app: a card should
    // appear once another app has stored something, without restarting Control.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    DataScreenContent(
        uiState = uiState,
        onOpenShares = onOpenShares,
        onDeleteAllClicked = viewModel::onDeleteAllClicked,
        onDeleteAllConfirmed = viewModel::onDeleteAllConfirmed,
        onDeleteAllDismissed = viewModel::onDeleteAllDismissed,
        onStopResetClicked = viewModel::onStopResetClicked,
        onResetResultShown = viewModel::onResetResultShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataScreenContent(
    uiState: DataUiState,
    onOpenShares: () -> Unit = {},
    onDeleteAllClicked: (String) -> Unit = {},
    onDeleteAllConfirmed: () -> Unit = {},
    onDeleteAllDismissed: () -> Unit = {},
    onStopResetClicked: () -> Unit = {},
    onResetResultShown: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val resultMessage = uiState.resetResult?.let { resetResultMessage(it) }

    Scaffold(
        topBar = { AppTopAppBar(title = stringResource(Res.string.data_title)) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .appContentWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!uiState.loading && uiState.typeIds.isEmpty()) {
                    Text(
                        stringResource(Res.string.data_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                uiState.typeIds.forEach { typeId ->
                    val presentation = realmDataPresentation(typeId)
                    DataTypeCard(
                        icon = presentation.icon,
                        title = presentation.pluralDisplayName,
                        // No item count on this card: the presence check counts only the
                        // arrays it can reach (not every File), and "Delete all 3,002" reads
                        // very differently from "Delete all" — a wrong number would be worse
                        // than none. The op reports what it actually did.
                        onDeleteAll = if (typeId in uiState.resettableTypeIds) {
                            { onDeleteAllClicked(typeId) }
                        } else {
                            null
                        },
                        deleting = uiState.resettingTypeId == typeId,
                        onStopDeleting = onStopResetClicked,
                    )
                }

                DataRow(
                    icon = Icons.Default.Share,
                    title = stringResource(Res.string.data_shares_title),
                    subtitle = stringResource(Res.string.data_shares_subtitle),
                    onClick = onOpenShares,
                )
            }
        }
    }

    uiState.pendingResetTypeId?.let { typeId ->
        DeleteAllConfirmDialog(
            typeName = realmDataPresentation(typeId).pluralDisplayName,
            onConfirm = onDeleteAllConfirmed,
            onDismiss = onDeleteAllDismissed,
        )
    }

    // The outcome is *always* surfaced, success or failure — a dropped failure would leave
    // the user believing data is gone when it is not (control-data-reset.md §5).
    LaunchedEffect(uiState.resetResult, resultMessage) {
        if (resultMessage != null) {
            snackbarHostState.showSnackbar(resultMessage)
            onResetResultShown()
        }
    }
}

/**
 * A realm-data type card — icon + plural name, plus the destructive reset when the type is
 * resettable. Otherwise non-interactive (a meta-view, not a browser): drilling into a type's
 * items routes into its owning app, which is cross-app navigation glue out of this phase's
 * scope.
 *
 * ⚠ [onDeleteAll] is `null` for a type the generic reset cannot reach — `File`, whose rows
 * live in the parent-folder tree rather than a service-data array, so `DeleteRealmData` would
 * delete zero rows and report success. Rendering the action there would be the same mistake
 * §6 rejects for the per-device option: a control that silently does nothing. Files are
 * deleted in Drive.
 */
@Composable
private fun DataTypeCard(
    icon: ImageVector,
    title: String,
    onDeleteAll: (() -> Unit)?,
    deleting: Boolean,
    onStopDeleting: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 16.dp),
            )
            if (deleting) {
                // A whole-type reset over a large store is minutes of work, so it gets a
                // progress affordance *and* a way out — leaving only a disabled button is
                // what made the pre-batching reset read as a frozen app.
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                TextButton(onClick = onStopDeleting) {
                    Text(stringResource(Res.string.data_delete_stop_action))
                }
            } else if (onDeleteAll != null) {
                TextButton(onClick = onDeleteAll) {
                    Text(
                        text = stringResource(Res.string.data_delete_all_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * The destructive confirmation. ⚠ Its copy carries two decisions that are not stylistic:
 * the reset is **realm-wide with no per-device option** (§6 D3 — a device-local delete would
 * be undone by the next pull), and it promises removal *from all your devices*, never
 * "everywhere" (§5 — relay block history survives until compaction, and unreferenced content
 * blobs are not per-file reclaimable). There is no undo (§10 Q2): this dialog is the guard.
 */
@Composable
private fun DeleteAllConfirmDialog(
    typeName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.data_delete_dialog_title, typeName)) },
        text = { Text(stringResource(Res.string.data_delete_dialog_body, typeName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(Res.string.data_delete_dialog_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.data_delete_dialog_cancel))
            }
        },
    )
}

/**
 * The message for a finished reset. Plural branches are resolved in Kotlin against the English
 * catalogue (ADR 0016 at n=1 — the `SharesScreen` member-count precedent) until an ICU
 * MessageFormat helper lands.
 */
@Composable
internal fun resetResultMessage(result: DataResetResult): String {
    val typeName = realmDataPresentation(result.typeId).pluralDisplayName
    return when (val outcome = result.outcome) {
        is RealmDataResetOutcome.Deleted -> when (outcome.count) {
            0 -> stringResource(Res.string.data_delete_result_none)
            1 -> stringResource(Res.string.data_delete_result_one)
            else -> stringResource(Res.string.data_delete_result_many, outcome.count)
        }

        RealmDataResetOutcome.NotResettable ->
            stringResource(Res.string.data_delete_error_unsupported, typeName)

        RealmDataResetOutcome.Failed ->
            stringResource(Res.string.data_delete_error_failed, typeName)

        // ⚠ Deliberately not phrased as a failure: the deleted part is really gone, on
        // every device. Saying "nothing was removed" here would be a lie.
        RealmDataResetOutcome.Stopped ->
            stringResource(Res.string.data_delete_result_stopped, typeName)
    }
}

@Composable
private fun DataRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
