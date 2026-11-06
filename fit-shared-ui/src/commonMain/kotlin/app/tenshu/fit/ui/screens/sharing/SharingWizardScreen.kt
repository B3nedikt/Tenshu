package app.tenshu.fit.ui.screens.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_sharing_allow_fit_action
import app.tenshu.fit.shared.resources.fit_sharing_allow_fit_body
import app.tenshu.fit.shared.resources.fit_sharing_allow_fit_title
import app.tenshu.fit.shared.resources.fit_sharing_allow_history_action
import app.tenshu.fit.shared.resources.fit_sharing_allow_history_body
import app.tenshu.fit.shared.resources.fit_sharing_allow_history_title
import app.tenshu.fit.shared.resources.fit_sharing_allow_reading_action
import app.tenshu.fit.shared.resources.fit_sharing_allow_reading_body
import app.tenshu.fit.shared.resources.fit_sharing_allow_reading_title
import app.tenshu.fit.shared.resources.fit_sharing_confirm_action
import app.tenshu.fit.shared.resources.fit_sharing_confirm_body
import app.tenshu.fit.shared.resources.fit_sharing_confirm_title
import app.tenshu.fit.shared.resources.fit_sharing_done_body
import app.tenshu.fit.shared.resources.fit_sharing_done_title
import app.tenshu.fit.shared.resources.fit_sharing_needs_export_action
import app.tenshu.fit.shared.resources.fit_sharing_needs_export_body
import app.tenshu.fit.shared.resources.fit_sharing_needs_export_title
import app.tenshu.fit.shared.resources.fit_sharing_no_store_body
import app.tenshu.fit.shared.resources.fit_sharing_no_store_title
import app.tenshu.fit.shared.resources.fit_sharing_title
import app.tenshu.fit.ui.components.FitTopBar
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The sharing wizard (`fit-app.md` §15.6) — one screen showing **one** thing to fix.
 *
 * ⚠⚠ [LifecycleResumeEffect] is the load-bearing line. Every other step the user takes happens in
 * Health Connect, where we cannot watch and get no callback; re-probing on resume is how the wizard
 * learns what they did. It is also why there is no click-by-click copy anywhere below: instructions
 * describing Google's UI would go stale, could not be localised honestly against Health Connect's
 * own strings, and could not be tested — the probe has none of those problems.
 *
 * A single step at a time rather than a checklist: the steps are ordered by dependency, so a list
 * would show the user problems they cannot yet act on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingWizardScreen(
    viewModel: SharingWizardViewModel,
    onBack: () -> Unit,
    /** Profile owns the file picker, so the export is offered by going back to it, not from here. */
    onOpenImport: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()

    LifecycleResumeEffect(Unit) {
        // Returning from Health Connect lands here. Nothing else can tell us what changed.
        viewModel.refreshOnResume()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        FitTopBar(title = stringResource(Res.string.fit_sharing_title), onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val copy = copyFor(state.step)
            Text(
                stringResource(copy.title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(copy.body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.busy) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                }
            }
            // The action is offered only when this shell can actually perform it — see
            // `SharingWizardUiState.actionable`. Every other state is honestly informational.
            val action = copy.action
            if (action != null && state.actionable && !state.busy) {
                Button(onClick = viewModel::act) { Text(stringResource(action)) }
            }
            if (state.step == SharingStep.NeedsExport) {
                // The one remedy that lives on another screen: Profile owns the picker.
                Button(onClick = onOpenImport) { Text(stringResource(Res.string.fit_sharing_needs_export_action)) }
            }
        }
    }
}

private data class StepCopy(
    val title: StringResource,
    val body: StringResource,
    val action: StringResource?,
)

private fun copyFor(step: SharingStep): StepCopy = when (step) {
    SharingStep.InstallStore -> StepCopy(
        Res.string.fit_sharing_no_store_title,
        Res.string.fit_sharing_no_store_body,
        // No action: on Android 14+ Health Connect is a system module with no store page, and on
        // older devices the target differs — a button right on some phones and dead on others.
        action = null,
    )
    SharingStep.AllowReading -> StepCopy(
        Res.string.fit_sharing_allow_reading_title,
        Res.string.fit_sharing_allow_reading_body,
        Res.string.fit_sharing_allow_reading_action,
    )
    SharingStep.AllowHistory -> StepCopy(
        Res.string.fit_sharing_allow_history_title,
        Res.string.fit_sharing_allow_history_body,
        Res.string.fit_sharing_allow_history_action,
    )
    SharingStep.AllowGoogleFit -> StepCopy(
        Res.string.fit_sharing_allow_fit_title,
        Res.string.fit_sharing_allow_fit_body,
        Res.string.fit_sharing_allow_fit_action,
    )
    SharingStep.ConfirmSharing -> StepCopy(
        Res.string.fit_sharing_confirm_title,
        Res.string.fit_sharing_confirm_body,
        Res.string.fit_sharing_confirm_action,
    )
    SharingStep.NeedsExport -> StepCopy(
        Res.string.fit_sharing_needs_export_title,
        Res.string.fit_sharing_needs_export_body,
        action = null, // rendered separately — it navigates rather than acting here
    )
    SharingStep.Done -> StepCopy(
        Res.string.fit_sharing_done_title,
        Res.string.fit_sharing_done_body,
        action = null,
    )
}
