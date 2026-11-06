package app.tenshu.photos.ui.screens.duplicates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.photos.data.DuplicateGroup
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.components.PhotoTile
import app.tenshu.photos.ui.components.PreviewLoader

/**
 * Duplicates (`photos-app.md` S4 / PH-D2): sets of byte-identical files, and one tap per set to
 * keep one of them.
 *
 * ⚠⚠ A **review** surface. The feed still shows every copy — this screen never hides one — and
 * nothing on it resolves a set until the user picks the copy to keep. There is deliberately no
 * "clean up all": the industry converged on asking (Apple, Amazon, Synology, digiKam all do), and
 * the reason is that a silent merge and a data loss look identical from the outside.
 *
 * ⚠⚠ This file draws; it decides and words nothing (`photos-ios.md` §8.18, PIO-D2). Which copy may
 * be chosen, what the confirmation says, what stands in place of the list and every sentence are
 * `DuplicatesUiState` / `DuplicatesCopy` — the same ones the iOS screen draws.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    state: DuplicatesUiState,
    loadPreview: PreviewLoader,
    /** The user read [DuplicatesConfirmation] and confirmed it — nothing is acted on before that. */
    onKeepOnly: (DuplicatesConfirmation) -> Unit,
    onSetDeletePermanently: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The error screen's *Try Again*. */
    onRetry: () -> Unit = {},
    /** Clears [DuplicatesUiState.report] once its dialog is dismissed. */
    onDismissReport: () -> Unit = {},
) {
    var confirming: DuplicatesConfirmation? by remember { mutableStateOf(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(DuplicatesCopy.TITLE) },
                navigationIcon = {
                    // ⚠ Not while a write runs — leaving would cancel it on Android (see `App`'s entry).
                    IconButton(onClick = onBack, enabled = state.canLeave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val status = state.status
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }

            // ⚠⚠ "Could not look" is not "no duplicates" (`photos-app.md` §40): the shared status says
            // which, and only a read that failed offers a retry.
            status != null ->
                Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = status.text,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (status.canRetry) {
                            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                                Text(DuplicatesCopy.RETRY_ACTION)
                            }
                        }
                    }
                }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(text = state.summary, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = DuplicatesCopy.EXACT_ONLY,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Checkbox(
                                checked = state.deletePermanently,
                                onCheckedChange = onSetDeletePermanently,
                                enabled = state.canSetDeletePermanently,
                            )
                            Column {
                                Text(DuplicatesCopy.PERMANENT_SWITCH, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = DuplicatesCopy.modeDescription(state.deletePermanently),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }

                items(state.groups, key = { it.contentHash }) { group ->
                    DuplicateGroupRow(
                        state = state,
                        group = group,
                        loadPreview = loadPreview,
                        // ⚠ `null` when the copy may not be chosen: a tap then opens nothing.
                        onKeep = { photo -> state.confirmation(group, photo)?.let { confirming = it } },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    confirming?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(confirmation.title) },
            text = {
                Column {
                    Text(confirmation.keeping)
                    Text(
                        text = confirmation.consequence,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onKeepOnly(confirmation)
                    confirming = null
                }) { Text(confirmation.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(confirmation.cancel) }
            },
        )
    }

    // ⚠⚠ Rendering this is not optional: every result here used to be dropped (`photos-ios.md` §8.18).
    state.report?.let { report ->
        AlertDialog(
            onDismissRequest = onDismissReport,
            title = { Text(report.title) },
            text = { Text(report.message) },
            confirmButton = { TextButton(onClick = onDismissReport) { Text("OK") } },
        )
    }
}

/**
 * One set. ⚠ Tapping a tile means *"keep this one"*, never *"delete this one"* — the destructive
 * reading of a tap is the one mistake this screen cannot afford, so the label says so above the
 * row and the dialog names the survivor rather than the casualties.
 */
@Composable
private fun DuplicateGroupRow(
    state: DuplicatesUiState,
    group: DuplicateGroup,
    loadPreview: PreviewLoader,
    onKeep: (Photo) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text = state.heading(group), style = MaterialTheme.typography.titleSmall)
        Text(
            text = DuplicatesCopy.caption(group),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            group.photos.forEach { photo ->
                Box(Modifier.size(96.dp)) {
                    PhotoTile(
                        photo = photo,
                        loadPreview = loadPreview,
                        // ⚠ Not while a write runs, and never a locked copy (`DuplicatesUiState.canKeep`).
                        onClick = { if (state.canKeep(group, photo)) onKeep(photo) },
                    )
                }
            }
        }
    }
}
