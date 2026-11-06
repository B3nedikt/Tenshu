package app.tenshu.drive.ui.screens.movepicker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.move_picker_already_exists
import app.tenshu.drive.shared.resources.move_picker_cancel
import app.tenshu.drive.shared.resources.move_picker_confirm
import app.tenshu.drive.shared.resources.move_picker_failed
import app.tenshu.drive.shared.resources.move_picker_new_folder
import app.tenshu.drive.shared.resources.move_picker_root_label
import app.tenshu.drive.shared.resources.move_picker_still_syncing
import app.tenshu.drive.shared.resources.move_picker_move_needs_local_bytes
import app.tenshu.drive.shared.resources.move_picker_title
import app.tenshu.drive.shared.resources.move_picker_title_multi
import app.tenshu.drive.ui.common.createfolder.CreateFolderDialog
import app.tenshu.drive.ui.common.files.PagedFilesList
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import app.tenshu.ui.theme.TenshuTheme
import app.tenshu.ui.theme.spacing
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * "Move to" destination picker (single item). Reuses [PagedFilesList] to browse
 * folders; tapping a folder drills in (the host pushes a deeper picker level),
 * and the bottom bar's "Move here" confirms the move into the currently-browsed
 * folder. See `MovePickerViewModel`.
 */
@Composable
fun MovePickerScreen(
    modifier: Modifier = Modifier,
    viewModel: MovePickerViewModel,
    onNavigateUp: () -> Unit,
    onOpenFolder: (path: String) -> Unit,
    onFinish: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val folders = viewModel.pagedFolders.collectAsLazyPagingItems()

    LaunchedEffect(uiState.moved) {
        if (uiState.moved) onFinish()
    }

    if (uiState.showNewFolder) {
        CreateFolderDialog(
            onDismissRequest = { viewModel.hideNewFolder() },
            folderPath = viewModel.folderPath,
        )
    }

    MovePickerContent(
        modifier = modifier,
        uiState = uiState,
        folders = folders,
        onNavigateUp = onNavigateUp,
        onOpenFolder = { item -> onOpenFolder(item.path) },
        onNewFolder = viewModel::showNewFolder,
        onMoveHere = viewModel::move,
        onCancel = onFinish,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovePickerContent(
    modifier: Modifier = Modifier,
    uiState: MovePickerUiState,
    folders: LazyPagingItems<FileUiModel>,
    onNavigateUp: () -> Unit,
    onOpenFolder: (item: FileUiModel) -> Unit,
    onNewFolder: () -> Unit,
    onMoveHere: () -> Unit,
    onCancel: () -> Unit,
) {
    val rootLabel = stringResource(Res.string.move_picker_root_label)
    val currentLabel = uiState.currentFolderName.ifEmpty { rootLabel }

    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .appContentWidth(),
        ) {
            AppTopAppBar(
                title = if (uiState.sourceCount > 1) {
                    stringResource(Res.string.move_picker_title_multi, uiState.sourceCount)
                } else {
                    stringResource(Res.string.move_picker_title, uiState.sourceName)
                },
                onBack = onNavigateUp,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacing.l,
                        vertical = MaterialTheme.spacing.s,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
            ) {
                Icon(imageVector = Icons.Default.Folder, contentDescription = null)
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onNewFolder) {
                    Icon(
                        imageVector = Icons.Default.CreateNewFolder,
                        contentDescription = null,
                    )
                    Text(
                        text = stringResource(Res.string.move_picker_new_folder),
                        modifier = Modifier.padding(start = MaterialTheme.spacing.s),
                    )
                }
            }

            PagedFilesList(
                modifier = Modifier.weight(1f),
                files = folders,
                showMoreButton = false,
                onFileClicked = onOpenFolder,
            )

            MovePickerBottomBar(
                uiState = uiState,
                onMoveHere = onMoveHere,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun MovePickerBottomBar(
    uiState: MovePickerUiState,
    onMoveHere: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(tonalElevation = MaterialTheme.spacing.xs) {
        Column(modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.l)) {
            uiState.errorMessage?.let { key ->
                val text = when (key) {
                    MovePickerViewModel.ERROR_ALREADY_EXISTS ->
                        stringResource(Res.string.move_picker_already_exists)

                    MovePickerViewModel.ERROR_STILL_SYNCING ->
                        stringResource(Res.string.move_picker_still_syncing)

                    MovePickerViewModel.ERROR_MOVE_NEEDS_LOCAL_BYTES ->
                        stringResource(Res.string.move_picker_move_needs_local_bytes)

                    else -> stringResource(Res.string.move_picker_failed)
                }
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = MaterialTheme.spacing.s),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m),
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isMoving,
                ) {
                    Text(text = stringResource(Res.string.move_picker_cancel))
                }
                Button(
                    onClick = onMoveHere,
                    modifier = Modifier.weight(1f),
                    enabled = uiState.canMoveHere && !uiState.isMoving,
                ) {
                    Text(text = stringResource(Res.string.move_picker_confirm))
                }
            }
        }
    }
}

@Preview
@Composable
fun MovePickerContentPreview() {
    val folders = flowOf(
        PagingData.from(
            listOf(
                FileUiModel("/Documents", "Documents", "", FileIcon.Folder, isFolder = true),
                FileUiModel("/Photos", "Photos", "", FileIcon.Folder, isFolder = true),
            )
        )
    ).collectAsLazyPagingItems()
    TenshuTheme {
        Surface {
            MovePickerContent(
                uiState = MovePickerUiState(sourceName = "report.pdf", currentFolderName = ""),
                folders = folders,
                onNavigateUp = {},
                onOpenFolder = {},
                onNewFolder = {},
                onMoveHere = {},
                onCancel = {},
            )
        }
    }
}
