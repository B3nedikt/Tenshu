package app.tenshu.photos.ui.screens.trash

import androidx.compose.foundation.layout.Arrangement
import app.tenshu.photos.ui.components.WidthScaledGridCells
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import app.tenshu.photos.data.TrashedItem
import app.tenshu.photos.ui.components.PhotoTile
import app.tenshu.photos.ui.components.PreviewLoader

/**
 * The trash (`photos-app.md` S8): what is waiting, with restore and delete-for-good.
 *
 * ⚠⚠ **Nothing here deletes without a confirmation the user has to read.** This is the only screen
 * in the app that can destroy a photograph, and there is no undo behind it — so the dialog names
 * what goes and says it cannot be undone, rather than asking "Are you sure?".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    state: TrashUiState,
    loadPreview: PreviewLoader,
    onRestore: (String) -> Unit,
    onDeleteForever: (String) -> Unit,
    onEmptyTrash: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Clears [TrashUiState.report] once its dialog is dismissed. */
    onDismissReport: () -> Unit = {},
    /** The column count on a phone-width grid; a wider one multiplies it (`WidthScaledGridCells`). */
    columns: Int = 3,
) {
    var confirming: TrashedItem? by remember { mutableStateOf(null) }
    var confirmingEmpty by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(TrashCopy.TITLE) },
                navigationIcon = {
                    // ⚠ Not while a write runs — leaving would cancel it on Android (see `App`'s Trash entry).
                    IconButton(onClick = onBack, enabled = !state.writing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // ⚠ Shown whenever there is something to empty; disabled while a write runs.
                    if (state.items.isNotEmpty()) {
                        TextButton(onClick = { confirmingEmpty = true }, enabled = state.canEmpty) {
                            Text(TrashCopy.EMPTY_ACTION)
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }

            // ⚠ The sentence is the shared `TrashUiState.statusText`; the iOS screen says the same.
            state.statusText != null ->
                Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), Alignment.Center) {
                    Text(
                        text = state.statusText!!,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

            else -> LazyVerticalGrid(
                columns = WidthScaledGridCells(columns),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.items, key = { it.contentId }) { item ->
                    PhotoTile(
                        photo = item.photo,
                        loadPreview = loadPreview,
                        // Tap restores — the safe action, and the one a user in the trash most
                        // often wants. Destroying takes the long press *and* a dialog.
                        // ⚠ Neither while a write runs: the writes are single-flight, and a tap the view
                        // model then ignores is a restore that silently never happened.
                        onClick = { if (state.canAct(item.contentId)) onRestore(item.contentId) },
                        onLongClick = if (state.canAct(item.contentId)) ({ confirming = item }) else null,
                    )
                }
            }
        }
    }

    confirming?.let { item ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(TrashCopy.DELETE_ONE_TITLE) },
            text = {
                Column {
                    Text(item.photo.name)
                    Text(
                        // ⚠ Says what is lost, not "are you sure": the user cannot get this back,
                        // and the sentence is the last chance to say so.
                        text = TrashCopy.DELETE_ONE_MESSAGE,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteForever(item.contentId)
                    confirming = null
                }) { Text(TrashCopy.DELETE_ONE_CONFIRM) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(TrashCopy.DELETE_ONE_CANCEL) }
            },
        )
    }

    if (confirmingEmpty) {
        AlertDialog(
            onDismissRequest = { confirmingEmpty = false },
            title = { Text(TrashCopy.EMPTY_TITLE) },
            text = { Text(TrashCopy.emptyMessage(state.emptyCount)) },
            confirmButton = {
                TextButton(onClick = {
                    onEmptyTrash()
                    confirmingEmpty = false
                }) { Text(TrashCopy.EMPTY_CONFIRM) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingEmpty = false }) { Text(TrashCopy.EMPTY_CANCEL) }
            },
        )
    }

    // ⚠⚠ Rendering this is not optional: every result here used to be dropped (`photos-app.md` §22).
    state.report?.let { report ->
        AlertDialog(
            onDismissRequest = onDismissReport,
            title = { Text(report.title) },
            text = { Text(report.message) },
            confirmButton = { TextButton(onClick = onDismissReport) { Text("OK") } },
        )
    }
}
