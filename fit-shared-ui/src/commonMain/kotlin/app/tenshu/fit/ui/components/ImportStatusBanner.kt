package app.tenshu.fit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_import_failed_body
import app.tenshu.fit.shared.resources.fit_import_waiting_body
import app.tenshu.fit.shared.resources.fit_import_waiting_title
import app.tenshu.fit.shared.resources.fit_import_failed_title
import app.tenshu.fit.shared.resources.fit_import_no_data_body
import app.tenshu.fit.shared.resources.fit_import_no_data_title
import app.tenshu.fit.shared.resources.fit_import_permission_body
import app.tenshu.fit.shared.resources.fit_import_permission_title
import app.tenshu.fit.shared.resources.fit_import_retry
import app.tenshu.fit.shared.resources.fit_import_running
import app.tenshu.fit.shared.resources.fit_import_running_count
import app.tenshu.fit.shared.resources.fit_import_sample_body
import app.tenshu.fit.shared.resources.fit_import_sample_title
import app.tenshu.fit.shared.resources.fit_import_unavailable_body
import app.tenshu.fit.shared.resources.fit_import_unavailable_title
import org.jetbrains.compose.resources.stringResource

/**
 * Tells the user what the last import actually did, when that is something they would want to know.
 *
 * Every state below used to render as the same blank screen — which is how "the Google Fit import
 * does not work" stayed unactionable: an unauthorised source, an empty one, a missing platform, and
 * an outright failure all looked identical from the outside, and none of them said a word.
 *
 * [ImportStatus.Sample] is the state that earns this component its keep. When no real source is
 * reachable the app falls back to a fixture, so the screens fill with convincing, invented numbers.
 * Rendering those unlabelled is worse than rendering nothing, because the user believes them.
 *
 * Silent for [ImportStatus.Idle], [ImportStatus.Running] and a successful [ImportStatus.Imported] —
 * a working import needs no announcement. `ImportStatus.needsAttention` draws that line.
 */
@Composable
fun ImportStatusBanner(
    status: ImportStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A running import shows a live progress row — a Takeout import writes tens of thousands of
    // records over minutes, and a screen that just sits there reads as a hang. Not an "attention"
    // state (nothing is wrong), so it is handled here rather than through needsAttention.
    if (status is ImportStatus.Running) {
        ImportProgressRow(status.imported, modifier)
        return
    }
    if (!status.needsAttention) return

    val title = when (status) {
        is ImportStatus.NoData -> Res.string.fit_import_no_data_title
        is ImportStatus.PermissionsMissing -> Res.string.fit_import_permission_title
        ImportStatus.SourceUnavailable -> Res.string.fit_import_unavailable_title
        is ImportStatus.Sample -> Res.string.fit_import_sample_title
        is ImportStatus.Failed -> Res.string.fit_import_failed_title
        is ImportStatus.WaitingForControl -> Res.string.fit_import_waiting_title
        else -> return
    }
    val body = when (status) {
        is ImportStatus.NoData -> Res.string.fit_import_no_data_body
        is ImportStatus.PermissionsMissing -> Res.string.fit_import_permission_body
        ImportStatus.SourceUnavailable -> Res.string.fit_import_unavailable_body
        is ImportStatus.Sample -> Res.string.fit_import_sample_body
        is ImportStatus.Failed -> Res.string.fit_import_failed_body
        is ImportStatus.WaitingForControl -> Res.string.fit_import_waiting_body
        else -> return
    }

    // A failed import is an error. The rest are advisory — including sample data, where the app is
    // working exactly as built; it is simply the state the user most needs to not mistake for real.
    val isError = status is ImportStatus.Failed
    val container =
        if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val onContainer =
        if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface

    Surface(shape = RoundedCornerShape(16.dp), color = container, modifier = modifier) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)) {
            Icon(
                imageVector = if (isError) FitIcons.Warning else FitIcons.Info,
                contentDescription = null,
                tint = onContainer.copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleSmall,
                    color = onContainer,
                )
                Text(
                    text = stringResource(body),
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.75f),
                )
                // Retry suits every attention state: the source may have gained data, the grant may
                // have been given in Health Connect since, and a failure may simply be transient.
                TextButton(onClick = onRetry, contentPadding = PaddingValues(vertical = 4.dp)) {
                    Text(
                        text = stringResource(Res.string.fit_import_retry),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isError) onContainer else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** A live "Importing… N so far" row while an import runs — the count ticks so a long job reads as alive. */
@Composable
private fun ImportProgressRow(imported: Int, modifier: Modifier) {
    val onContainer = MaterialTheme.colorScheme.onSurface
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (imported > 0) {
                    stringResource(Res.string.fit_import_running_count, imported)
                } else {
                    stringResource(Res.string.fit_import_running)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = onContainer,
            )
        }
    }
}
