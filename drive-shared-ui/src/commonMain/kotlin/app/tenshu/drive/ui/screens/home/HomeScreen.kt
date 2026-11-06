package app.tenshu.drive.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.home_recent_files
import app.tenshu.ui.chrome.appFabInset
import org.tenshu.core.file.sync.SyncRuleSet
import app.tenshu.drive.ui.common.SelectionTopAppBar
import app.tenshu.ui.image.decodePreviewBitmap
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsFloatingActionButton
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsUiState
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.ui.common.details.FileActionsBottomSheet
import app.tenshu.drive.ui.common.details.LocalFileActionsViewModelFactory
import app.tenshu.drive.ui.common.files.FilesList
import app.tenshu.drive.ui.common.rename.RenameFileDialog
import app.tenshu.drive.ui.common.search.FileSearchBarUiState
import app.tenshu.drive.ui.common.search.FilesSearchBar
import app.tenshu.drive.ui.common.search.FilesSearchBarContent
import app.tenshu.drive.ui.common.uploadconflict.UploadFeedback
import app.tenshu.drive.ui.share.StorageGateDialog
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.ui.chrome.AppBottomSheet
import app.tenshu.ui.layout.appContentWidth
import app.tenshu.ui.theme.TenshuTheme
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeScreenViewModel,
    fabViewModel: AddFileActionsViewModel,
    showFile: (path: String) -> Unit,
    showFolder: (path: String) -> Unit,
    showFileDetails: (path: String) -> Unit,
    showMovePicker: (path: String) -> Unit,
    showManageAccess: (path: String) -> Unit,
    showSyncedFolders: () -> Unit = {},
    onUploadFile: () -> Unit,
    onUploadFolder: () -> Unit,
    onUploadImages: () -> Unit,
    onSettingsClicked: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedPaths by viewModel.selectedPaths.collectAsState()
    val syncRules by viewModel.syncRules.collectAsState()
    val fabUiState by fabViewModel.uiState.collectAsState()

    // A tapped recent-files row sets `showFile`; navigate folder-vs-file from
    // here (mirrors `FilesScreen`). Without this seam the row click set state
    // that nothing observed, so Recent Files were unclickable.
    val clickedFile = uiState.showFile
    LaunchedEffect(key1 = clickedFile) {
        if (clickedFile != null) {
            viewModel.hideFile()
            if (clickedFile.isFolder) showFolder(clickedFile.path)
            else showFile(clickedFile.path)
        }
    }

    AppBottomSheet(
        visible = uiState.showFileActionsWithPath != null,
        onDismissRequest = { viewModel.hideFileActions() }
    ) {
        FileActionsBottomSheet(
            viewModel = LocalFileActionsViewModelFactory.current(
                uiState.showFileActionsWithPath!!,
                uiState.showFileActionsForFolder,
            ),
            onActionCompleted = { viewModel.hideFileActions() },
            onMoveRequested = showMovePicker,
            onRenameRequested = { path ->
                viewModel.showRenameFile(path)
            },
            onDetailsRequested = showFileDetails,
            onManageAccessRequested = showManageAccess,
            onSyncedFoldersRequested = showSyncedFolders,
        )
    }

    if (uiState.showRenameFileWithPath != null) {
        RenameFileDialog(
            onDismissRequest = { viewModel.hideRenameFile() },
            filePath = uiState.showRenameFileWithPath!!
        )
    }

    UploadFeedback(
        uiState = fabUiState,
        onKeepBoth = fabViewModel::resolveConflictKeepBoth,
        onCancelConflict = fabViewModel::cancelConflict,
        onDismissStorageGate = fabViewModel::dismissStorageGate,
        onDismissUploadError = fabViewModel::dismissUploadError,
    )

    HomeScreenContent(
        modifier = modifier,
        uiState = uiState,
        selectedPaths = selectedPaths,
        syncRules = syncRules,
        onClearSelection = viewModel::clearSelection,
        onFileClicked = viewModel::onFileInResentFilesClicked,
        onToggleSelection = viewModel::toggleSelection,
        onMoreButtonOfFileClicked = viewModel::showFileActions,
        previewLoader = { path -> viewModel.loadPreviewBytes(path, width = 100, height = 100)?.decodePreviewBitmap() },
        localDataLoader = { path -> viewModel.loadLocalData(path) },
        fabUiState = fabUiState,
        onShowAddFileActions = fabViewModel::showAddFileActions,
        onHideAddFileActions = fabViewModel::hideAddFileActions,
        onHideCreateFolder = fabViewModel::hideCreateFolder,
        onShowCreateFolder = fabViewModel::showCreateFolder,
        onUploadFile = onUploadFile,
        onUploadFolder = onUploadFolder,
        onUploadImages = onUploadImages,
        searchBar = {
            FilesSearchBar(
                enabled = fabUiState.showAddFolder.not() &&
                        fabUiState.showAddFileActions.not() &&
                        uiState.showFileActionsWithPath == null &&
                        uiState.showRenameFileWithPath == null,
                onSettingsClicked = onSettingsClicked,
                syncRules = syncRules,
                localDataLoader = { path -> viewModel.loadLocalData(path) },
                // Navigate folder-vs-file with the same nav lambdas the recent-files
                // list uses; overflow opens the host's file-actions sheet.
                onResultClicked = { file ->
                    if (file.isFolder) showFolder(file.path) else showFile(file.path)
                },
                onResultMoreClicked = viewModel::showFileActionsForFile,
            )
        }
    )
}

@Composable
fun HomeScreenContent(
    modifier: Modifier = Modifier,
    uiState: HomeScreenUiState,
    selectedPaths: Set<String>,
    syncRules: SyncRuleSet = SyncRuleSet.Default,
    onClearSelection: () -> Unit,
    onFileClicked: (index: Int) -> Unit,
    onToggleSelection: (index: Int) -> Unit,
    onMoreButtonOfFileClicked: (index: Int) -> Unit,
    previewLoader: (suspend (path: String) -> ImageBitmap?)? = null,
    localDataLoader: (suspend (path: String) -> Boolean)? = null,
    fabUiState: AddFileActionsUiState,
    onShowAddFileActions: () -> Unit,
    onHideAddFileActions: () -> Unit,
    onHideCreateFolder: () -> Unit,
    onShowCreateFolder: () -> Unit,
    onUploadFile: () -> Unit,
    onUploadFolder: () -> Unit,
    onUploadImages: () -> Unit,
    searchBar: @Composable () -> Unit
) {
    Box(
        modifier
            .fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .appContentWidth()
        ) {
            if (selectedPaths.isNotEmpty()) {
                SelectionTopAppBar(
                    selectedCount = selectedPaths.size,
                    onClearSelection = onClearSelection
                )
            } else {
                searchBar()
            }

            Text(
                modifier = Modifier.padding(16.dp),
                text = stringResource(Res.string.home_recent_files),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium
            )

            FilesList(
                modifier = Modifier.weight(1f),
                files = uiState.recentFiles,
                load = uiState.recentFilesLoad,
                isSelectionMode = selectedPaths.isNotEmpty(),
                selectedPaths = selectedPaths,
                syncRules = syncRules,
                previewLoader = previewLoader,
                localDataLoader = localDataLoader,
                onFileClicked = onFileClicked,
                onItemLongClicked = onToggleSelection,
                onToggleSelection = onToggleSelection,
                onMoreButtonOfFileClicked = onMoreButtonOfFileClicked
            )

            if (selectedPaths.isNotEmpty()) {
                Spacer(modifier = Modifier.height(140.dp))
            }
        }

        if (selectedPaths.isEmpty()) {
            AddFileActionsFloatingActionButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .appFabInset(),
                uiState = fabUiState,
                folderPath = "/",
                onShowAddFileActions = onShowAddFileActions,
                onHideAddFileActions = onHideAddFileActions,
                onHideCreateFolder = onHideCreateFolder,
                onShowCreateFolder = onShowCreateFolder,
                onUploadFile = onUploadFile,
                onUploadFolder = onUploadFolder,
                onUploadImages = onUploadImages
            )
        }
    }
}

@Preview
@Composable
fun HomeScreenContentPreview() {
    TenshuTheme {
        Surface {
            HomeScreenContent(
                uiState = HomeScreenUiState(
                    recentFiles = listOf(
                        FileUiModel(
                            "/folder1",
                            "Documents",
                            "Modified 2 days ago",
                            FileIcon.Folder
                        ),
                        FileUiModel("/file1.pdf", "resume.pdf", "1.2 MB", FileIcon.GenericFile)
                    ),
                    searchBarFiles = emptyList()
                ),
                selectedPaths = emptySet(),
                onClearSelection = {},
                onFileClicked = {},
                onToggleSelection = {},
                onMoreButtonOfFileClicked = {},
                fabUiState = AddFileActionsUiState(),
                onShowAddFileActions = {},
                onHideAddFileActions = {},
                onHideCreateFolder = {},
                onShowCreateFolder = {},
                onUploadFile = {},
                onUploadFolder = {},
                onUploadImages = {},
                searchBar = {
                    FilesSearchBarContent(
                        uiState = FileSearchBarUiState(),
                        onQueryChanged = {},
                        onActiveChanged = {},
                        onSearchClicked = {},
                    )
                }
            )
        }
    }
}
