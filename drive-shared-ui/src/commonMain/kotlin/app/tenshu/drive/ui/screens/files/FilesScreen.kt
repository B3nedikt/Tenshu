package app.tenshu.drive.ui.screens.files

import app.tenshu.ui.feedback.SyncActivityCapsule
import app.tenshu.ui.feedback.rememberSyncActivity
import org.tenshu.sync.common.SyncActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import app.tenshu.drive.data.ChangeLostModel
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.FileViewMode
import app.tenshu.drive.domain.SyncBannerState
import app.tenshu.ui.chrome.appFabInset
import org.tenshu.core.file.sync.SyncRuleSet
import app.tenshu.drive.ui.common.SelectionTopAppBar
import app.tenshu.ui.image.decodePreviewBitmap
import app.tenshu.drive.ui.common.ChangeLostMessage
import app.tenshu.drive.ui.common.SyncStatusBanner
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsFloatingActionButton
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsUiState
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.ui.common.details.FileActionsBottomSheet
import app.tenshu.drive.ui.common.details.LocalFileActionsViewModelFactory
import app.tenshu.drive.ui.common.files.FilesListingToolbar
import app.tenshu.drive.ui.common.files.PagedFilesList
import app.tenshu.drive.ui.common.rename.RenameFileDialog
import app.tenshu.drive.ui.common.search.FileSearchBarUiState
import app.tenshu.drive.ui.common.search.FilesSearchBar
import app.tenshu.drive.ui.common.search.FilesSearchBarContent
import app.tenshu.drive.ui.common.uploadconflict.UploadFeedback
import app.tenshu.drive.ui.share.StorageGateDialog
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.ui.chrome.AppBottomSheet
import app.tenshu.ui.input.AppShortcuts
import app.tenshu.ui.input.keyboardShortcuts
import app.tenshu.ui.layout.appContentWidth
import app.tenshu.ui.theme.TenshuTheme
import app.tenshu.ui.theme.spacing
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
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
    onSettingsClicked: () -> Unit = {},
    onDeleteSelection: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedPaths by viewModel.selectedPaths.collectAsState()
    val syncRules by viewModel.syncRules.collectAsState()
    val syncBanner by viewModel.syncBanner.collectAsState()
    val syncActivity = rememberSyncActivity(viewModel.catchingUp)
    val changeLost by viewModel.changeLost.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val files = viewModel.pagedFiles.collectAsLazyPagingItems()

    val fabUiState by fabViewModel.uiState.collectAsState()

    val searchFocusRequester = remember { FocusRequester() }

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
            viewModel.hideFile()
            showFolder(showFolderWithPath)
        }
    }

    val showFileWithPath = uiState.showFileWithPath
    LaunchedEffect(key1 = showFileWithPath) {
        if (showFileWithPath != null) {
            viewModel.hideFile()
            showFile(showFileWithPath)
        }
    }

    FilesScreenContent(
        modifier = modifier.keyboardShortcuts(
            AppShortcuts.focusSearch {
                runCatching { searchFocusRequester.requestFocus() }
            },
            AppShortcuts.dismiss {
                if (selectedPaths.isNotEmpty()) viewModel.clearSelection()
            },
            AppShortcuts.deleteSelection {
                if (selectedPaths.isNotEmpty()) onDeleteSelection()
            }
        ),
        files = files,
        selectedPaths = selectedPaths,
        syncRules = syncRules,
        syncBanner = syncBanner,
        syncActivity = syncActivity,
        changeLost = changeLost,
        onDismissChangeLost = viewModel::dismissChangeLost,
        sort = sort,
        onSortChange = viewModel::setSort,
        viewMode = viewMode,
        onViewModeChange = viewModel::setViewMode,
        onSyncNow = viewModel::syncNowOverCellular,
        onStopBackup = viewModel::stopBackupNow,
        onClearSelection = viewModel::clearSelection,
        onFileClicked = viewModel::showFile,
        onToggleSelection = viewModel::toggleSelection,
        onMoreButtonOfFileClicked = viewModel::showFileActions,
        previewLoader = { path -> viewModel.loadPreviewBytes(path, width = 100, height = 100)?.decodePreviewBitmap() },
        localDataLoader = { path -> viewModel.loadLocalData(path) },
        folderPath = "/",
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
                inputFocusRequester = searchFocusRequester,
                syncRules = syncRules,
                localDataLoader = { path -> viewModel.loadLocalData(path) },
                // Reuse the same folder-vs-file navigation + actions handlers as
                // the main listing, so a search result behaves like a list row.
                onResultClicked = viewModel::showFile,
                onResultMoreClicked = viewModel::showFileActions,
            )
        }
    )
}

@Composable
fun FilesScreenContent(
    modifier: Modifier = Modifier,
    files: LazyPagingItems<FileUiModel>,
    selectedPaths: Set<String>,
    syncRules: SyncRuleSet = SyncRuleSet.Default,
    syncBanner: SyncBannerState? = null,
    syncActivity: SyncActivity = SyncActivity.Idle,
    changeLost: ChangeLostModel? = null,
    onDismissChangeLost: (String) -> Unit = {},
    sort: FileSort = FileSort.Default,
    onSortChange: (FileSort) -> Unit = {},
    viewMode: FileViewMode = FileViewMode.LIST,
    onViewModeChange: (FileViewMode) -> Unit = {},
    onSyncNow: () -> Unit = {},
    onStopBackup: () -> Unit = {},
    onClearSelection: () -> Unit,
    onFileClicked: (item: FileUiModel) -> Unit,
    onToggleSelection: (item: FileUiModel) -> Unit,
    onMoreButtonOfFileClicked: (item: FileUiModel) -> Unit,
    previewLoader: (suspend (path: String) -> androidx.compose.ui.graphics.ImageBitmap?)? = null,
    localDataLoader: (suspend (path: String) -> Boolean)? = null,
    folderPath: String,
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

            // Account-wide sync-status banner (ADR 0019 + connectivity signal).
            // Hidden during multi-select (the selection bar owns the top strip then).
            val bannerState = syncBanner
            if (selectedPaths.isEmpty() && bannerState != null) {
                SyncStatusBanner(
                    state = bannerState,
                    onSyncNow = onSyncNow,
                    onStopBackup = onStopBackup,
                )
            }

            // CR-2 B4 P3: a change lost to a file another device deleted (§2.4).
            val lost = changeLost
            if (selectedPaths.isEmpty() && lost != null) {
                ChangeLostMessage(notice = lost, onDismiss = onDismissChangeLost)
            }

            if (selectedPaths.isEmpty()) {
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
        // SS-2 (`suite-sync-status.md`): "Syncing…" while Control pulls. Uploads stay in the banner
        // above; any banner, or the selection bar, hides it.
        SyncActivityCapsule(
            activity = syncActivity,
            hidden = syncBanner != null || selectedPaths.isNotEmpty(),
        )
    }
}

@Preview
@Composable
fun FilesScreenContentPreview() {
    val files = flowOf(
        PagingData.from(
            listOf(
                FileUiModel(
                    "/folder1",
                    "Documents",
                    "Modified 2 days ago",
                    FileIcon.Folder,
                    isFolder = true
                ),
                FileUiModel("/file1.pdf", "resume.pdf", "1.2 MB", FileIcon.GenericFile)
            )
        )
    ).collectAsLazyPagingItems()
    TenshuTheme {
        Surface {
            FilesScreenContent(
                files = files,
                selectedPaths = emptySet(),
                onClearSelection = {},
                onFileClicked = {},
                onToggleSelection = {},
                onMoreButtonOfFileClicked = {},
                folderPath = "/",
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

@Preview
@Composable
fun FilesScreenContentSelectionModePreview() {
    val files = flowOf(
        PagingData.from(
            listOf(
                FileUiModel(
                    "/folder1",
                    "Documents",
                    "Modified 2 days ago",
                    FileIcon.Folder,
                    isFolder = true
                ),
                FileUiModel("/file1.pdf", "resume.pdf", "1.2 MB", FileIcon.GenericFile)
            )
        )
    ).collectAsLazyPagingItems()
    TenshuTheme {
        Surface {
            FilesScreenContent(
                files = files,
                selectedPaths = setOf("/folder1"),
                onClearSelection = {},
                onFileClicked = {},
                onToggleSelection = {},
                onMoreButtonOfFileClicked = {},
                folderPath = "/",
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
