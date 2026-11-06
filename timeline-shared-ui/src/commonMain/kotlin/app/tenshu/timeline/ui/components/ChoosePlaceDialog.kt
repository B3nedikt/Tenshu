package app.tenshu.timeline.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_place_picker_hint
import app.tenshu.timeline.shared.resources.timeline_place_picker_loading
import app.tenshu.timeline.shared.resources.timeline_place_picker_nearby
import app.tenshu.timeline.shared.resources.timeline_place_picker_title
import app.tenshu.timeline.shared.resources.timeline_place_rename_cancel
import app.tenshu.timeline.shared.resources.timeline_place_rename_confirm
import org.jetbrains.compose.resources.stringResource
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * "Choose the right place" (`timeline-recognition-and-place-naming.md` §4) — a picker for correcting a
 * mis-named stay. A coarse indoor fix (±100 m) can name the clinic next door instead of the office you
 * were in, so this offers the nearest labelled candidates (proxy-mediated Photon) as tappable rows
 * plus a free-text field. Whatever is confirmed becomes the user-authored name — the enricher's
 * blank-only rule then never overwrites it. Shared by the place detail and the visit detail.
 *
 * [loadSuggestions] runs once when the dialog opens; a failure or empty result simply shows the
 * free-text field alone (the picker degrades to a plain rename, never blocks on the network).
 */
@Composable
fun ChoosePlaceDialog(
    current: String,
    loadSuggestions: suspend () -> List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    var suggestions by remember { mutableStateOf<List<String>?>(null) } // null = still loading

    LaunchedEffect(Unit) {
        suggestions = runCatchingCancellable { loadSuggestions() }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.timeline_place_picker_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (val s = suggestions) {
                    null -> Text(
                        stringResource(Res.string.timeline_place_picker_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> if (s.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.timeline_place_picker_nearby).uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Column(Modifier.heightIn(max = 220.dp)) {
                            s.forEach { candidate -> SuggestionRow(candidate, selected = candidate == value) { value = candidate } }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.timeline_place_picker_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(stringResource(Res.string.timeline_place_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.timeline_place_rename_cancel)) }
        },
    )
}

@Composable
private fun SuggestionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(PaddingValues(horizontal = 14.dp, vertical = 10.dp)),
        )
    }
}
