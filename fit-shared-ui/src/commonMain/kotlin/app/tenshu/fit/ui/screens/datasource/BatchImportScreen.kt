package app.tenshu.fit.ui.screens.datasource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_batch_import_again
import app.tenshu.fit.shared.resources.fit_batch_import_empty
import app.tenshu.fit.shared.resources.fit_batch_import_hint
import app.tenshu.fit.shared.resources.fit_import_running
import app.tenshu.fit.shared.resources.fit_import_running_count
import app.tenshu.fit.shared.resources.fit_source_detail_history
import app.tenshu.fit.ui.components.FitCard
import app.tenshu.fit.ui.components.FitTopBar
import app.tenshu.fit.ui.components.SectionHeader
import app.tenshu.fit.ui.components.batchImportName
import app.tenshu.fit.ui.components.importRunDetail
import app.tenshu.fit.ui.components.importRunHeadline
import org.jetbrains.compose.resources.stringResource

/**
 * The batch-import history (`docs/proposals/fit-data-sources-restructure.md` §4) — every Google
 * Takeout import, and the button to run another.
 *
 * ⚠ There is no auto-import switch here and no "connected" state, because Takeout is an **act**
 * rather than a standing connection: it has no `HealthSource` row, and everything on this screen
 * derives from `HealthImportRun(kind = TAKEOUT)` (D11).
 *
 * ⚠ A pushed screen: `Column { FitTopBar(onBack); … }`, not a `Scaffold` (fit-app.md §18.15).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchImportScreen(
    viewModel: BatchImportViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val status = state.importStatus

    Column(Modifier.fillMaxSize()) {
        FitTopBar(title = batchImportName(), onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(Res.string.fit_batch_import_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ⚠⚠ The one screen where the LIVE status genuinely belongs on top: a Takeout import
            // runs for minutes and writes tens of thousands of rows, and a running count is the
            // only thing that distinguishes "working" from "hung". Everywhere else the durable
            // row is the honest source (§4.1).
            if (status is ImportStatus.Running) {
                Text(
                    if (status.imported > 0) {
                        stringResource(Res.string.fit_import_running_count, status.imported)
                    } else {
                        stringResource(Res.string.fit_import_running)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (state.canImportFiles) {
                Button(
                    onClick = viewModel::importFromFile,
                    enabled = status !is ImportStatus.Running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.fit_batch_import_again))
                }
            }

            SectionHeader(stringResource(Res.string.fit_source_detail_history))
            FitCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp)) {
                Column {
                    if (!state.hasHistory) {
                        // ⚠ "No imports yet", never "no data" — the user has imported nothing,
                        // which is a fact about their actions rather than about their health.
                        Text(
                            stringResource(Res.string.fit_batch_import_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    state.history.forEachIndexed { index, entry ->
                        ImportRunRow(
                            headline = importRunHeadline(entry),
                            detail = importRunDetail(entry),
                        )
                        if (index != state.history.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}
