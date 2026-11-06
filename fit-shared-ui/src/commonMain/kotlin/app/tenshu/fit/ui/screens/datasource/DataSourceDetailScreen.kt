package app.tenshu.fit.ui.screens.datasource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.model.DataSourceState
import app.tenshu.fit.model.HistoryAccess
import app.tenshu.fit.shared.resources.fit_profile_auto_detect
import app.tenshu.fit.shared.resources.fit_profile_auto_detect_hint
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_import_full_history_granted
import app.tenshu.fit.shared.resources.fit_import_full_history_hint
import app.tenshu.fit.shared.resources.fit_import_full_history_title
import app.tenshu.fit.shared.resources.fit_sharing_row_hint
import app.tenshu.fit.shared.resources.fit_sharing_row_title
import app.tenshu.fit.shared.resources.fit_source_detail_auto_hint
import app.tenshu.fit.shared.resources.fit_source_detail_auto_hint_remote
import app.tenshu.fit.shared.resources.fit_source_detail_auto_title
import app.tenshu.fit.shared.resources.fit_source_detail_history
import app.tenshu.fit.shared.resources.fit_source_detail_history_empty
import app.tenshu.fit.shared.resources.fit_source_detail_import_now
import app.tenshu.fit.shared.resources.fit_source_detail_remote_hint
import app.tenshu.fit.shared.resources.fit_source_detail_tenshu_body
import app.tenshu.fit.ui.components.FitCard
import app.tenshu.fit.ui.components.FitTopBar
import app.tenshu.fit.ui.components.SectionHeader
import app.tenshu.fit.ui.components.dataSourceHint
import app.tenshu.fit.ui.components.dataSourceName
import app.tenshu.fit.ui.components.dataSourceStatus
import app.tenshu.fit.ui.components.importRunDetail
import app.tenshu.fit.ui.components.importRunHeadline
import app.tenshu.fit.ui.screens.datasource.DataSourceDetailViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * One data source, and everything that belongs to it
 * (`docs/proposals/fit-data-sources-restructure.md` §4).
 *
 * This is where three former top-level Profile rows went — *Import your full history*, *Check
 * sharing setup*, and the Google Fit notice's remedy. They only ever sat beside the source
 * because the source row had no destination to open into.
 *
 * ⚠ A pushed screen, so `Column { FitTopBar(onBack); … }` and **not** a `Scaffold` (fit-app.md
 * §18.15): the shell already supplies the bottom inset and the bar owns the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSourceDetailScreen(
    viewModel: DataSourceDetailViewModel,
    onBack: () -> Unit,
    /** The sharing wizard, re-parented here from Profile — it is Health Connect's diagnostic. */
    onOpenSharingWizard: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()

    LifecycleResumeEffect(Unit) {
        // Returning from Health Connect lands here, and nothing else can tell us what changed.
        viewModel.refreshOnResume()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        FitTopBar(title = dataSourceName(state.kind), onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val row = state.row
            // ⚠ Deliberately NOT a card. A screen header is not a list item, and `FitCard`'s
            // `surfaceContainerLow` sits close enough to the background in dark that the card
            // read as floating text — inset by 32 dp, because FitCard's own 16 dp padding was
            // being doubled by a nested Column that padded again.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    row?.deviceLabel ?: dataSourceHint(state.kind),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // ⚠ Only once the row has loaded. `row == null` is "still reading the registry",
                // not "this source is gone", and a status line rendered from a default would be
                // a claim about the user's realm we have not read yet.
                if (row != null) {
                    Text(
                        dataSourceStatus(row.state),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            when (state.kind) {
                // Fit's own recordings: nothing to import, nothing to switch off. Saying so
                // beats an empty screen that looks like a source that failed to load.
                DataSourceKind.TenshuHealth -> {
                    Text(
                        stringResource(Res.string.fit_source_detail_tenshu_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Q1: auto-detection is this source's own setting, so it lives here rather
                    // than in a separate *Automatic* section on Profile. ⚠ Driven off the row's
                    // state, not off a second read — `Recording` carries exactly this.
                    (row?.state as? DataSourceState.Recording)?.let { recording ->
                        FitCard(Modifier.fillMaxWidth()) {
                            AutoImportRow(
                                title = stringResource(Res.string.fit_profile_auto_detect),
                                checked = recording.autoDetect,
                                supporting = stringResource(Res.string.fit_profile_auto_detect_hint),
                                onCheckedChange = viewModel::setAutoImport,
                            )
                        }
                    }
                }

                DataSourceKind.HealthConnect, DataSourceKind.AppleHealth -> {
                    if (state.canImportNow) {
                        Button(onClick = viewModel::importNow, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(Res.string.fit_source_detail_import_now))
                        }
                    } else if (row?.deviceLabel?.isNotBlank() == true) {
                        // ⚠⚠ D4: we cannot make another device read its health store, so the
                        // button is replaced by where the user would have to go — not by a
                        // disabled control that silently does nothing when tapped.
                        Text(
                            stringResource(Res.string.fit_source_detail_remote_hint, row.deviceLabel.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (row != null) {
                        FitCard(Modifier.fillMaxWidth()) {
                            AutoImportRow(
                                title = stringResource(Res.string.fit_source_detail_auto_title),
                                checked = row.autoImport,
                                // The switch reaches another device, but that device applies it
                                // when it next runs — worth saying, or the delay reads as a bug.
                                supporting = row.deviceLabel
                                    ?.let { stringResource(Res.string.fit_source_detail_auto_hint_remote, it) }
                                    ?: stringResource(Res.string.fit_source_detail_auto_hint),
                                onCheckedChange = viewModel::setAutoImport,
                            )
                        }
                    }

                    if (state.canAskForFullHistory) {
                        // ⚠ Button and explanation are ONE child, not two. As siblings of the
                        // 16.dp-spaced column they read as unrelated — on a real phone the
                        // sentence looked orphaned, belonging to nothing in particular.
                        ExplainedAction(
                            label = stringResource(Res.string.fit_import_full_history_title),
                            explanation = stringResource(
                                if (state.historyAccess == HistoryAccess.Granted) {
                                    Res.string.fit_import_full_history_granted
                                } else {
                                    Res.string.fit_import_full_history_hint
                                }
                            ),
                            onClick = viewModel::importFullHistory,
                        )
                    }

                    // The wizard, permanently (§15.6.5) — its value is that it *checks*, so it
                    // must be reachable before the user has a symptom to report.
                    if (state.canOpenHealthSettings) {
                        ExplainedAction(
                            label = stringResource(Res.string.fit_sharing_row_title),
                            explanation = stringResource(Res.string.fit_sharing_row_hint),
                            onClick = onOpenSharingWizard,
                        )
                    }
                }
            }

            if (state.kind != DataSourceKind.TenshuHealth) {
                SectionHeader(stringResource(Res.string.fit_source_detail_history))
                FitCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp)) {
                    Column {
                        if (state.history.isEmpty()) {
                            Text(
                                stringResource(Res.string.fit_source_detail_history_empty),
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
}

/**
 * An outlined action with the sentence that explains it, as **one** unit.
 *
 * ⚠ The pair used to be two siblings of a `spacedBy(16.dp)` column, which put the same gap
 * between a button and its own explanation as between unrelated sections — so the sentence read
 * as belonging to nothing. Grouping is the whole fix; 6.dp inside, the section gap outside.
 */
@Composable
private fun ExplainedAction(label: String, explanation: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
        Text(
            explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AutoImportRow(
    title: String,
    checked: Boolean,
    supporting: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        // No padding: `FitCard` supplies it. Padding here too is what made every card in this
        // screen sit on a 32 dp inset.
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** One run in a history list. Shared by this screen and the batch-import one. */
@Composable
internal fun ImportRunRow(headline: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            headline,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
