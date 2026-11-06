package app.tenshu.timeline.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.timeline.data.TimelineImportControl
import app.tenshu.timeline.data.TimelineImportStatus
import app.tenshu.timeline.data.summary
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_empty_body
import app.tenshu.timeline.shared.resources.timeline_empty_capture_hint
import app.tenshu.timeline.shared.resources.timeline_empty_title
import app.tenshu.timeline.shared.resources.timeline_settings_import
import app.tenshu.timeline.shared.resources.timeline_settings_import_running
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin

/**
 * The Timeline-only user's first screen (B3, TB-D6).
 *
 * ⚠⚠ **The empty state is not an apology; it is the primary call to action.** Timeline's whole
 * premise for someone who has not enabled capture — or cannot, on desktop — is *bring your
 * history*, so the import lives here rather than only behind a Settings row nobody opens on a first
 * launch. Before B3 this screen was a blank `LazyColumn`: no explanation, and no route to the
 * importer that existed on another platform.
 *
 * ⚠ It must not read as "capture is broken". Capture is off by default (threat-model A35) and
 * staying off is a legitimate way to use the app, so it is mentioned second and as an alternative,
 * never as a fault to correct.
 *
 * ⚠ The button is absent where nothing can pick a file — the same bound-only-shows idiom the
 * Settings rows use, so a platform with no picker shows the explanation and no dead control.
 */
@Composable
fun TimelineEmptyState(modifier: Modifier = Modifier) {
    val koin = getKoin()
    val control = remember(koin) { koin.getOrNull<TimelineImportControl>() }
    val status = control?.status?.collectAsState()?.value ?: TimelineImportStatus.Idle

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.timeline_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.timeline_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        when {
            control == null -> Unit

            status is TimelineImportStatus.Running -> {
                CircularProgressIndicator()
                Text(
                    text = stringResource(Res.string.timeline_settings_import_running, status.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> Button(onClick = control::pick) {
                Text(stringResource(Res.string.timeline_settings_import))
            }
        }

        // ⚠⚠ The outcome is shown *here* too, not only in Settings. An import that found nothing
        // leaves this screen empty, and without the reason on it the user is looking at the same
        // blank page they started from — which is exactly how "it did nothing" gets reported when
        // what happened was "that archive held no timeline".
        when (status) {
            is TimelineImportStatus.Done -> EmptyStateNote(status.result.summary(status.name))
            is TimelineImportStatus.Failed -> EmptyStateNote(status.message)
            else -> Unit
        }

        Text(
            text = stringResource(Res.string.timeline_empty_capture_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A tab that has nothing *yet* — deliberately a different, quieter thing from [TimelineEmptyState].
 *
 * ⚠⚠ A user with ten years of imported history and no named places has plenty of data; telling
 * them "your timeline starts here" on the Places tab would be false, and offering them an import
 * they already did would be worse.
 */
@Composable
fun TimelineTabEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
    )
}

@Composable
private fun EmptyStateNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
