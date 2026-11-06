package app.tenshu.drive.ui.screens.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.drive.data.SharedFolderEntry
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.shared_browse_close
import app.tenshu.drive.shared.resources.shared_browse_empty
import app.tenshu.drive.shared.resources.shared_browse_error
import app.tenshu.drive.shared.resources.shared_browse_opened_bytes
import app.tenshu.ui.chrome.AppBottomSheet
import app.tenshu.ui.chrome.AppTopAppBar
import org.jetbrains.compose.resources.stringResource

/**
 * Browse screen for a shared realm's folder tree
 * (`docs/proposals/drive-shared-realm-file-model.md` §6 Phase 3d.2b). Folder rows
 * drill in ([onOpenFolder] pushes a nested browse destination), file rows open
 * via the VM ([SharedBrowseViewModel.onFileClicked]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedBrowseScreen(
    viewModel: SharedBrowseViewModel,
    onBack: () -> Unit,
    onOpenFolder: (fileId: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { AppTopAppBar(title = uiState.title, onBack = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                uiState.error -> CenteredMessage(stringResource(Res.string.shared_browse_error))
                uiState.entries.isEmpty() -> CenteredMessage(stringResource(Res.string.shared_browse_empty))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.entries, key = { it.fileId }) { entry ->
                        SharedEntryRow(
                            entry = entry,
                            onClick = {
                                if (entry.isFolder) onOpenFolder(entry.fileId, entry.name)
                                else viewModel.onFileClicked(entry)
                            },
                        )
                    }
                }
            }
        }
    }

    uiState.openedFile?.let { opened ->
        AppBottomSheet(visible = true, onDismissRequest = viewModel::onOpenedFileDismissed) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(opened.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(Res.string.shared_browse_opened_bytes, opened.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = viewModel::onOpenedFileDismissed) {
                    Text(stringResource(Res.string.shared_browse_close))
                }
            }
        }
    }
}

@Composable
private fun SharedEntryRow(entry: SharedFolderEntry, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (entry.isFolder) Icons.Default.Folder
            else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
        )
        Text(entry.name, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
