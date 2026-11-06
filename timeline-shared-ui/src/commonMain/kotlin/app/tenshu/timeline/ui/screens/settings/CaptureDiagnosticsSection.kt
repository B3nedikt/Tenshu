package app.tenshu.timeline.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.timeline.data.capture.RawFixJournal
import app.tenshu.timeline.data.capture.RawFixJournalControl
import app.tenshu.timeline.data.capture.SegmentationReplaySweep
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_settings_rawfix
import app.tenshu.timeline.shared.resources.timeline_settings_rawfix_capped
import app.tenshu.timeline.shared.resources.timeline_settings_rawfix_count
import app.tenshu.timeline.shared.resources.timeline_settings_rawfix_delete
import app.tenshu.timeline.shared.resources.timeline_settings_rawfix_desc
import app.tenshu.timeline.shared.resources.timeline_settings_replay_hint
import app.tenshu.timeline.shared.resources.timeline_settings_replay_run
import app.tenshu.timeline.shared.resources.timeline_settings_replay_running
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.tenshu.core.common.util.bestEffort

/**
 * **Capture diagnostics** (`timeline-movement-capture.md` §18.9) — the raw-fix corpus toggle and the
 * replay trigger.
 *
 * ⚠⚠ Rendered only where a [RawFixJournalControl] is bound, which on Android is **debug builds
 * only**. That is the same bound-only-shows idiom the three capture opt-ins use, and here it is
 * load-bearing rather than merely tidy: the corpus is a verbatim location log (threat-model A35),
 * and a release build must not offer it at all.
 *
 * The results go to [DiagnosticsSection]'s log rather than into a bespoke report view — that surface
 * already exists, already has copy-to-clipboard, and a second rendering of the same data would be
 * one more thing to keep honest.
 */
@Composable
fun CaptureDiagnosticsSection(
    control: RawFixJournalControl,
    journal: RawFixJournal,
    sweep: SegmentationReplaySweep?,
) {
    val enabled by control.enabled.collectAsState()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    // Re-read after every action rather than held reactively: the count changes with each captured
    // fix, and a live counter here would be a per-fix recomposition for a debug screen.
    var status by remember { mutableStateOf(journal.status()) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(Res.string.timeline_settings_rawfix),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.timeline_settings_rawfix_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = {
                control.setEnabled(it)
                status = journal.status()
            },
        )
    }

    // ⚠ Shown whenever a corpus exists, NOT only while recording is on — withdrawing consent must
    // not hide data that is still on disk, or the one control that can delete it would claim there
    // is nothing to delete.
    if (status.fixCount > 0) {
        Text(
            text = stringResource(Res.string.timeline_settings_rawfix_count, status.fixCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status.capped) {
            Text(
                text = stringResource(Res.string.timeline_settings_rawfix_capped),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (sweep != null) {
            TextButton(
                enabled = !running,
                onClick = {
                    running = true
                    scope.launch {
                        bestEffort("CaptureDiagnostics -> the rail-refinement sweep") { sweep.run() }
                        status = journal.status()
                        running = false
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (running) Res.string.timeline_settings_replay_running
                        else Res.string.timeline_settings_replay_run,
                    ),
                )
            }
        }
        if (status.fixCount > 0) {
            TextButton(onClick = {
                journal.clear()
                status = journal.status()
            }) {
                Text(stringResource(Res.string.timeline_settings_rawfix_delete))
            }
        }
    }

    Text(
        text = stringResource(Res.string.timeline_settings_replay_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}
