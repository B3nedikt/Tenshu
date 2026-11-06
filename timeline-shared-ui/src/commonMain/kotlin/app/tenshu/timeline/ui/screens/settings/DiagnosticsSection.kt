package app.tenshu.timeline.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.tenshu.diagnostics.Diagnostics
import app.tenshu.diagnostics.LogItem
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_settings_diagnostics_clear
import app.tenshu.timeline.shared.resources.timeline_settings_diagnostics_copy
import app.tenshu.timeline.shared.resources.timeline_settings_diagnostics_copied
import app.tenshu.timeline.shared.resources.timeline_settings_diagnostics_desc
import app.tenshu.timeline.shared.resources.timeline_settings_diagnostics_empty
import app.tenshu.timeline.shared.resources.timeline_settings_diagnostics_hide
import app.tenshu.timeline.shared.resources.timeline_settings_diagnostics_show
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * The in-app view of the device-local diagnostic log (the single `LogItem` stream). Collapsed by
 * default — expanding reads `Diagnostics.recent()` and shows the tail newest-last, with a Copy button so a
 * user (or the developer, over the shoulder) can pull the last hours of capture activity **without a
 * cable**. This is the "see tomorrow" answer done right: a bespoke `adb`-only store became something
 * readable in Settings.
 *
 * Only rendered when diagnostics are actually recorded on this platform (Android installs a real
 * sink; desktop/tests leave the global no-op, so `recent()` is empty and this stays hidden).
 */
@Composable
fun DiagnosticsSection() {
    // Snapshot on each (re)expansion — cheap, and diagnostics don't need live streaming here.
    var expanded by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<LogItem>>(emptyList()) }
    var copied by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    Text(
        text = stringResource(Res.string.timeline_settings_diagnostics_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = {
            expanded = !expanded
            if (expanded) { items = Diagnostics.recent(); copied = false }
        }) {
            Text(stringResource(if (expanded) Res.string.timeline_settings_diagnostics_hide else Res.string.timeline_settings_diagnostics_show))
        }
        if (expanded && items.isNotEmpty()) {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(items.joinToString("\n", transform = ::renderLine)))
                copied = true
            }) {
                Text(stringResource(if (copied) Res.string.timeline_settings_diagnostics_copied else Res.string.timeline_settings_diagnostics_copy))
            }
            TextButton(onClick = { Diagnostics.clear(); items = emptyList() }) {
                Text(stringResource(Res.string.timeline_settings_diagnostics_clear))
            }
        }
    }

    if (expanded) {
        Spacer(Modifier.height(4.dp))
        if (items.isEmpty()) {
            Text(
                stringResource(Res.string.timeline_settings_diagnostics_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Newest last matches a log's natural reading order; the scroll box caps the height.
                items.forEach { item ->
                    Text(
                        text = renderLine(item),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** `14:03:57 W watchdog: re-armed capture (watchdog): opt-in on but service not running`. */
private fun renderLine(item: LogItem): String {
    val t = Instant.fromEpochMilliseconds(item.timestampMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = t.hour.toString().padStart(2, '0')
    val mm = t.minute.toString().padStart(2, '0')
    val ss = t.second.toString().padStart(2, '0')
    return "$hh:$mm:$ss ${item.level.name.first()} ${item.tag}: ${item.message}"
}
