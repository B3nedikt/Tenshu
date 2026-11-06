package app.tenshu.drive.ui.screens.folder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.FileViewMode
import app.tenshu.drive.ui.common.SelectionTopAppBar
import app.tenshu.ui.image.decodePreviewBitmap
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsFloatingActionButton
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsUiState
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.ui.common.details.FileActionsBottomSheet
import app.tenshu.drive.ui.common.details.LocalFileActionsViewModelFactory
import app.tenshu.drive.ui.common.files.FilesListingToolbar
import app.tenshu.drive.ui.common.files.PagedFilesList
import app.tenshu.drive.ui.common.rename.RenameFileDialog
import app.tenshu.drive.ui.common.uploadconflict.UploadFeedback
import app.tenshu.drive.ui.share.StorageGateDialog
import app.tenshu.ui.chrome.appFabInset
import org.tenshu.core.file.sync.SyncRuleSet
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.drive.ui.screens.files.FilesScreenViewModel
import app.tenshu.ui.chrome.AppBottomSheet
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    modifier: Modifier = Modifier,
    folderPath: String,
    navigateUp: () -> Unit,
    showFolder: (path: String) -> Unit,
    showFile: (path: String) -> Unit,
    showFileDetails: (path: String) -> Unit,
    showMovePicker: (path: String) -> Unit,
    showManageAccess: (path: String) -> Unit,
    showSyncedFolders: () -> Unit = {},
    viewModel: FilesScreenViewModel,
    fabViewModel: AddFileActionsViewModel,
    onUploadFile: () -> Unit,
    onUploadFolder: () -> Unit,
    onUploadImages: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedPaths by viewModel.selectedPaths.collectAsState()
    val syncRules by viewModel.syncRules.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val files = viewModel.pagedFiles.collectAsLazyPagingItems()

    val fabUiState by fabViewModel.uiState.collectAsState()

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

    val showFolderWithPath = uiState.showFolderWithPath
    LaunchedEffect(key1 = showFolderWithPath) {
        if (showFolderWithPath != null) {
            showFolder(showFolderWithPath)
            viewModel.hideFile()
        }
    }

    val showFileWithPath = uiState.showFileWithPath
    LaunchedEffect(key1 = showFileWithPath) {
        if (showFileWithPath != null) {
            showFile(showFileWithPath)
            viewModel.hideFile()
        }
    }

    FolderScreenContent(
        modifier = modifier,
        folderPath = folderPath,
        files = files,
        selectedPaths = selectedPaths,
        syncRules = syncRules,
        sort = sort,
        onSortChange = viewModel::setSort,
        viewMode = viewMode,
        onViewModeChange = viewModel::setViewMode,
        onNavigateUp = navigateUp,
        onClearSelection = viewModel::clearSelection,
        onFileClicked = viewModel::showFile,
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
        searchBar = { /* No search bar in folders by design currently or generic placeholder */ }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreenContent(
    modifier: Modifier = Modifier,
    folderPath: String,
    files: LazyPagingItems<FileUiModel>,
    selectedPaths: Set<String>,
    syncRules: SyncRuleSet = SyncRuleSet.Default,
    sort: FileSort = FileSort.Default,
    onSortChange: (FileSort) -> Unit = {},
    viewMode: FileViewMode = FileViewMode.LIST,
    onViewModeChange: (FileViewMode) -> Unit = {},
    onNavigateUp: () -> Unit,
    onClearSelection: () -> Unit,
    onFileClicked: (item: FileUiModel) -> Unit,
    onToggleSelection: (item: FileUiModel) -> Unit,
    onMoreButtonOfFileClicked: (item: FileUiModel) -> Unit,
    previewLoader: (suspend (path: String) -> androidx.compose.ui.graphics.ImageBitmap?)? = null,
    localDataLoader: (suspend (path: String) -> Boolean)? = null,
    fabUiState: AddFileActionsUiState,
    onShowAddFileActions: () -> Unit,
    onHideAddFileActions: () -> Unit,
    onHideCreateFolder: () -> Unit,
    onShowCreateFolder: () -> Unit,
    onUploadFile: () -> Unit,
    onUploadFolder: () -> Unit,
    onUploadImages: () -> Unit,
    searchBar: @Composable () -> Unit = {}
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
                AppTopAppBar(title = folderPath, onBack = onNavigateUp)
                searchBar()
                FilesListingToolbar(
                    sort = sort,
                    onSortChange = onSortChange,
                    viewMode = viewMode,
                    onViewModeChange = onViewModeChange,
                )
            }

            PagedFilesList(
                modifier = Modifier.weight(1f),
                files = files,
                isSelectionMode = selectedPaths.isNotEmpty(),
                selectedPaths = selectedPaths,
                syncRules = syncRules,
                viewMode = viewMode,
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
                folderPath = folderPath,
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
fun FolderScreenContentPreview() {
    val files = flowOf(
        PagingData.from(
            listOf(
                FileUiModel("/folder1", "Work", "Modified 1 day ago", FileIcon.Folder, isFolder = true),
                FileUiModel("/file1.pdf", "report.pdf", "500 KB", FileIcon.GenericFile)
            )
        )
    ).collectAsLazyPagingItems()
    TenshuTheme {
        Surface {
            FolderScreenContent(
                folderPath = "/Documents",
                files = files,
                selectedPaths = emptySet(),
                onNavigateUp = {},
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
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Search Placeholder")
                    }
                }
            )
        }
    }
}
