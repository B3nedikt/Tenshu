package app.tenshu.drive.ui

import app.tenshu.drive.data.BackupFolderPickResult
import app.tenshu.drive.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.UnreachableReason
import app.tenshu.ui.feedback.AppControlUnavailableWall
import app.tenshu.ui.feedback.AppControlCheckingGate
import app.tenshu.ui.feedback.controlBannerIcon
import app.tenshu.ui.feedback.rememberControlBannerAction
import app.tenshu.ui.storage.ControlInstallRoute
import app.tenshu.ui.storage.rememberControlInstallRoute
import app.tenshu.drive.shared.resources.drive_control_get_action
import app.tenshu.drive.shared.resources.drive_control_not_set_up
import app.tenshu.drive.shared.resources.drive_control_open_action
import app.tenshu.drive.shared.resources.drive_control_not_installed
import app.tenshu.drive.shared.resources.drive_control_not_installed_other_channel
import app.tenshu.drive.shared.resources.drive_control_not_installed_unpublished
import app.tenshu.drive.shared.resources.drive_control_wall_title
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberBottomSheetScaffoldState
import app.tenshu.ui.launch.AnnounceAtLaunch
import org.koin.core.context.GlobalContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.common.TenshuClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tenshu.drive.ui.common.MultiSelectBottomSheet
import app.tenshu.drive.ui.common.isImagePreviewSupported
import app.tenshu.drive.ui.common.isVideoPlaybackSupported
import app.tenshu.drive.ui.common.folderupload.FolderUploadConflictDialog
import app.tenshu.drive.ui.common.folderupload.FolderUploadPreflightDialog
import app.tenshu.drive.ui.common.folderupload.FolderUploadStatusBar
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.ui.common.details.FileActionsBottomSheet
import app.tenshu.drive.ui.common.details.LocalFileActionsViewModelFactory
import app.tenshu.drive.ui.common.preview.ImagePreviewScreen
import app.tenshu.drive.ui.common.preview.LocalImagePreviewViewModelFactory
import app.tenshu.drive.ui.common.preview.LocalOpenExternallyViewModelFactory
import app.tenshu.drive.ui.common.preview.LocalPdfPreviewViewModelFactory
import app.tenshu.drive.ui.common.preview.LocalVideoPreviewViewModelFactory
import app.tenshu.drive.ui.common.preview.PdfPreviewScreen
import app.tenshu.drive.ui.common.preview.UnsupportedPreviewScreen
import app.tenshu.drive.ui.common.preview.VideoPreviewScreen
import app.tenshu.drive.ui.common.rename.RenameFileDialog
import app.tenshu.drive.ui.contacts.InviteBadgeViewModel
import app.tenshu.drive.ui.contacts.RedeemInviteScreen
import app.tenshu.drive.ui.contacts.RedeemInviteViewModel
import app.tenshu.drive.ui.navigation.Destination
import app.tenshu.drive.ui.screens.filedetails.FileDetailsScreen
import app.tenshu.drive.ui.screens.files.FilesScreen
import app.tenshu.drive.ui.screens.files.FilesScreenViewModel
import app.tenshu.drive.ui.screens.folder.FolderScreen
import app.tenshu.drive.ui.screens.home.HomeScreen
import app.tenshu.drive.ui.screens.home.HomeScreenViewModel
import app.tenshu.drive.ui.screens.movepicker.MovePickerScreen
import app.tenshu.drive.ui.screens.settings.SyncPreferencesScreen
import app.tenshu.drive.ui.screens.settings.SyncedFoldersScreen
import app.tenshu.drive.ui.screens.shared.SharedBrowseScreen
import app.tenshu.drive.ui.screens.shared.SharedScreen
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.isPdf
import org.tenshu.core.file.shared.SharedFile
import app.tenshu.drive.ui.access.ManageAccessScreen
import app.tenshu.drive.ui.access.ManageAccessViewModel
import app.tenshu.drive.ui.selection.SelectionViewModel
import app.tenshu.drive.ui.share.CreateShareLinkSheet
import app.tenshu.drive.ui.share.CreateShareLinkViewModel
import app.tenshu.drive.ui.share.StorageGateDialog
import app.tenshu.ui.legal.OpenSourceLicencesScreen
import app.tenshu.ui.chrome.AppBottomSheet
import app.tenshu.ui.layout.AdaptiveNavItem
import app.tenshu.ui.layout.AdaptiveNavScaffold
import app.tenshu.ui.navigation.enterFromAnotherApp
import app.tenshu.ui.navigation.popOrLeaveApp
import app.tenshu.ui.navigation.rememberAppExit
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    selectionViewModel: SelectionViewModel = koinViewModel(),
    fabViewModel: AddFileActionsViewModel = koinViewModel(),
    inviteBadgeViewModel: InviteBadgeViewModel = koinViewModel(),
    onUploadFile: (parentPath: String) -> Unit = { _ -> },
    onUploadFolder: (parentPath: String) -> Unit = { _ -> },
    onUploadImages: (parentPath: String) -> Unit = { _ -> },
    // Platform folder picker for Synced Folders: Android uses SAF; desktop asks Control
    // to create a security-scoped grant. `null` in previews/tests.
    onAddBackupFolder: (((BackupFolderPickResult) -> Unit) -> Unit)? = null,
    // Android-only: launches the media-permission dialog for gallery backup
    // (drive-synced-folders-new-folder-prompt.md §5). `null` on desktop.
    onRequestMediaAccess: (() -> Unit)? = null,
    // Android deep link (tenshu-invite: / https://tenshu.app/i) — routes into
    // the redeem screen on first composition (drive-manage-access-ux.md §3.1).
    inviteLinkToRedeem: String? = null,
    /**
     * Rows another app asked Drive to show — `control-cross-app-open.md` TT-2a's receiving half.
     *
     * ⚠⚠ A `Flow`, not a nullable value: a cold-start target arrives in the launch `Intent`, read
     * before this composition exists, so a plain parameter would drop the case the mechanism was
     * built for. ⚠ Empty by default, so desktop and tests are unchanged.
     */
    openTargets: Flow<DataId> = emptyFlow(),
    // Android-only: the new-folder backup prompt's notification body was tapped, so
    // open Synced Folders on first composition
    // (drive-synced-folders-new-folder-prompt.md).
    openSyncedFolders: Boolean = false,
) {
    // Registration must not wait for a screen to issue an operation (agent.md §7): resolving the
    // client opens the session, and the client module announces on the scope it was wired with.
    AnnounceAtLaunch { GlobalContext.get().get<TenshuClient>() }

    // ⚠⚠ **The wall replaces the app, it does not sit on top of it** (ADR 0025 §Decision 1b).
    // Drive without Control has no realm and therefore no files: the listing is empty, every action
    // fails, and the banner that used to describe this said "Control is unreachable — informational,
    // no action". ⚠ It is also the one place the banner could not have said it anyway: the banner
    // renders on FilesScreen alone, so Home and Shared showed nothing at all.
    //
    // ⚠ `NotInstalled` and `NotSetUp` only: a transport outage is transient and self-recovering,
    // and Drive already reports it as `SyncBannerState.Disconnected`.
    val reachability = koinInject<ControlReachability>()
    val controlReason: UnreachableReason? by reachability.reason.collectAsState()
    // ⚠⚠ **Installed, answering, and no realm** (ADR 0025 §Decision 8). Nothing is unreachable —
    // the proxy serves every read, empty — so without this the shell rendered its empty states
    // over a library that does not exist. Permanent until the user acts, so it walls like
    // `NotInstalled`; the offer is to *open* Control, never to get it.
    // ⚠⚠ ADR 0025 §8.6 (Codex P1 on 5f82133bc): Control is answering, and whether this device has a
    // realm is not known yet. Non-interactive — no shell, nothing that writes — until the realm
    // read answers (normally ~1 s); blank for 300 ms, a spinner, a retry wall past 20 s.
    if (controlReason == UnreachableReason.Checking) {
        AppControlCheckingGate(onRetry = reachability::recheck)
        return
    }
    if (controlReason == UnreachableReason.NotSetUp) {
        val wallAction = rememberControlBannerAction(controlReason)
        AppControlUnavailableWall(
            title = stringResource(Res.string.drive_control_wall_title),
            body = stringResource(Res.string.drive_control_not_set_up),
            icon = controlBannerIcon(controlReason),
            actionLabel = wallAction?.let { stringResource(Res.string.drive_control_open_action) },
            onAction = wallAction?.onClick,
        )
        return
    }
    if (controlReason == UnreachableReason.NotInstalled) {
        val wallRoute = rememberControlInstallRoute()
        val wallAction = rememberControlBannerAction(controlReason, wallRoute)
        AppControlUnavailableWall(
            title = stringResource(Res.string.drive_control_wall_title),
            body = when (wallRoute) {
                is ControlInstallRoute.Store -> stringResource(Res.string.drive_control_not_installed)
                ControlInstallRoute.NotPublishedYet ->
                    stringResource(Res.string.drive_control_not_installed_unpublished)
                ControlInstallRoute.SameChannelAsThisApp ->
                    stringResource(Res.string.drive_control_not_installed_other_channel)
            },
            actionLabel = wallAction?.let { stringResource(Res.string.drive_control_get_action) },
            onAction = wallAction?.onClick,
        )
        return
    }

    val backStack = remember { mutableStateListOf<Destination>(Destination.Home) }

    // Every back arrow and every nav-host back press, in one place.
    //
    // ⚠⚠ **A pop must never empty the stack** — `NavDisplay` requires a non-empty back stack and
    // throws, so an unguarded `removeAt(size - 1)` on a single entry is a crash rather than a no-op.
    // That case exists now: a cross-app open leaves the target as the ONLY entry (CO-D11), and its
    // back arrow is the first affordance that can reach the bottom of the stack.
    //
    // ⚠ Leaving the app is the right answer there, not doing nothing: the user arrived from another
    // app, and back means *take me back to it*. [rememberAppExit] is `null` where the platform has
    // no such move, and then this really is a no-op.
    val exitApp = rememberAppExit()
    val goBack: () -> Unit = { backStack.popOrLeaveApp(exitApp) }

    val showFileDetails: (String) -> Unit = { path -> backStack.add(Destination.FileDetails(path)) }
    // Manage access screen (drive-manage-access-ux.md §10) — folder-only entry
    // from the file-actions sheet.
    val showManageAccess: (String) -> Unit = { path -> backStack.add(Destination.ManageAccess(path)) }
    // Where the backup-delete warning sends a user who wants the switch rather than the
    // delete: the selection it names lives on the source device, so this is a pointer, not
    // an action (`drive-synced-folders.md` §3.9).
    val showSyncedFolders: () -> Unit = { backStack.add(Destination.SyncedFolders) }
    // Open the "Move to" picker rooted at the item's current parent folder.
    val showMovePicker: (String) -> Unit = { path ->
        val parent = path.substringBeforeLast("/").ifEmpty { "/" }
        backStack.add(Destination.MovePicker(sourcePaths = listOf(path), folderPath = parent))
    }
    val selectedPaths by selectionViewModel.selectedPaths.collectAsState()
    // Multi-select *Move*: open the picker seeded with the whole selection,
    // rooted at their shared parent (root when the selection spans folders).
    val showMovePickerForSelection: () -> Unit = {
        val paths = selectedPaths.toList()
        if (paths.isNotEmpty()) {
            val sharedParent = paths
                .map { it.substringBeforeLast("/").ifEmpty { "/" } }
                .distinct()
                .singleOrNull() ?: "/"
            backStack.add(Destination.MovePicker(sourcePaths = paths, folderPath = sharedParent))
            selectionViewModel.clearSelection()
        }
    }
    val offlinePaths by selectionViewModel.offlinePaths.collectAsState()
    val canShareSelection by selectionViewModel.canShareSelection.collectAsState()
    val storageGateBlock by selectionViewModel.storageGateBlock.collectAsState()
    val scaffoldState = rememberBottomSheetScaffoldState()
    val fabUiState by fabViewModel.uiState.collectAsState()

    // Mode-A *Share link* (docs/file-sharing.md): the create-link sheet
    // is a single top-level modal opened from either the multi-select sheet or
    // the per-file actions sheet. Lifted to the App composable so both entry
    // points hand the same path in.
    var createShareLinkForPath by remember { mutableStateOf<String?>(null) }

    val showInviteBadge by inviteBadgeViewModel.showBadge.collectAsState()

    // Deep-linked invite (drive-manage-access-ux.md §3.1): land on the redeem
    // screen once per link.
    LaunchedEffect(inviteLinkToRedeem) {
        if (inviteLinkToRedeem != null) {
            backStack.add(Destination.RedeemInvite(inviteLinkToRedeem))
        }
    }

    LaunchedEffect(openSyncedFolders) {
        if (openSyncedFolders) backStack.add(Destination.SyncedFolders)
    }

    // Cross-app open (`control-cross-app-open.md` TT-2a): Timeline's photo tap, or a Mia file
    // citation, naming a row Drive owns.
    //
    // ⭐ **`timeline-photos.md` §10.5 said this needed "a new cross-app navigation contract — an
    // intent filter in Drive, a launcher in Timeline, a path→viewer route, and a decision about
    // what happens when Drive is not installed".** Three of those four now exist generically:
    // Control mints the launch, `NOT_INSTALLED_HERE` is the decision, and no intent filter is
    // involved because the component is explicit. What is left is this one line — the route was
    // already here.
    //
    // ⚠ A target whose type is not a file is ignored rather than guessed at. Control routes by
    // type (CO-D2), so receiving one is a table bug, and opening the wrong screen would hide it.
    //
    // ⚠⚠ **`enterFromAnotherApp` REPLACES the stack, it does not push onto it** (CO-D11). Pushing
    // left `Home` underneath the file, so back went *into* Drive rather than back to the app that
    // sent the user — see the helper for the rule and what it costs.
    LaunchedEffect(openTargets) {
        openTargets.collect { target ->
            val path = filePathFromTarget(target) ?: return@collect
            backStack.enterFromAnotherApp(Destination.FilePreview(path))
        }
    }

    BackHandler(enabled = backStack.size > 1) {
        backStack.removeLastOrNull()
        selectionViewModel.clearSelection()
    }

    val topLevelItems = remember {
        listOf(
            Destination.Home,
            Destination.Files,
            Destination.Shared,
        )
    }

    val navItems = topLevelItems.map { item ->
        val isSelected = when (item) {
            is Destination.Home -> backStack.lastOrNull() is Destination.Home
            is Destination.Files -> backStack.any { it is Destination.Files || it is Destination.Folder }
            is Destination.Shared -> backStack.lastOrNull() is Destination.Shared
            else -> false
        }
        AdaptiveNavItem(
            key = item,
            icon = item.icon,
            label = item.label,
            selected = isSelected,
            // §2.3 attention dot: an invite awaits confirmation (synced flag).
            showBadge = item is Destination.Shared && showInviteBadge,
            onClick = {
                if (backStack.lastOrNull() != item) {
                    backStack.clear()
                    backStack.add(item)
                    selectionViewModel.clearSelection()
                }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = if (selectedPaths.isNotEmpty()) 140.dp else 0.dp,
        sheetDragHandle = null,
        sheetContainerColor = Color.Transparent,
        sheetShadowElevation = 0.dp,
        sheetContent = {
            if (selectedPaths.isNotEmpty()) {
                MultiSelectBottomSheet(
                    selectedPaths = selectedPaths,
                    isOffline = selectedPaths.isNotEmpty() && selectedPaths.all { it in offlinePaths },
                    shareEnabled = canShareSelection,
                    onShare = selectionViewModel::onShareClicked,
                    // Mode-A *Share link* — v1 supports single-file selection
                    // only (proposal §6.3). When exactly one file is selected,
                    // open the create-link sheet for it; otherwise no-op.
                    // (The card is rendered enabled in v1; if it gets pressed
                    // for a multi-selection, we currently silently no-op.
                    // A future polish pass adds a disabled state on the card
                    // when `selectedPaths.size != 1`.)
                    onShareLink = {
                        selectedPaths.singleOrNull()?.let { path ->
                            // Tier-1 gate (storage-capacity-gating.md §2): only open
                            // the create-link sheet when storage allows it; otherwise
                            // the VM raises the gate dialog and returns null.
                            selectionViewModel.onShareLinkRequested(path)?.let { allowedPath ->
                                createShareLinkForPath = allowedPath
                                selectionViewModel.clearSelection()
                            }
                        }
                    },
                    onDelete = selectionViewModel::onDeleteClicked,
                    onMove = showMovePickerForSelection,
                    onOfflineToggled = selectionViewModel::onOfflineToggled,
                    onClose = selectionViewModel::clearSelection
                )
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
    ) { _ ->
        AdaptiveNavScaffold(navItems = navItems) {
            KmpNavHost(
                backStack = backStack,
                modifier = Modifier,
                onBack = goBack,
                entryProvider = kmpEntryProvider {
                    entry<Destination.Home> { _ ->
                        val viewModel = koinViewModel<HomeScreenViewModel>()
                        HomeScreen(
                            viewModel = viewModel,
                            fabViewModel = fabViewModel,
                            showFile = { path -> backStack.add(Destination.FilePreview(path)) },
                            showFolder = { path -> backStack.add(Destination.Folder(path)) },
                            showFileDetails = showFileDetails,
                            showMovePicker = showMovePicker,
                            showManageAccess = showManageAccess,
                        showSyncedFolders = showSyncedFolders,
                            onUploadFile = { onUploadFile("/") },
                            onUploadFolder = { onUploadFolder("/") },
                            onUploadImages = { onUploadImages("/") },
                            onSettingsClicked = { backStack.add(Destination.Settings) }
                        )
                    }

                    entry<Destination.Files> { _ ->
                        val viewModel = koinViewModel<FilesScreenViewModel>(
                            key = "files_root",
                            parameters = { parametersOf("/") }
                        )
                        FilesScreen(
                            showFolder = { path -> backStack.add(Destination.Folder(path)) },
                            showFile = { path -> backStack.add(Destination.FilePreview(path)) },
                            showFileDetails = showFileDetails,
                            showMovePicker = showMovePicker,
                            showManageAccess = showManageAccess,
                        showSyncedFolders = showSyncedFolders,
                            viewModel = viewModel,
                            fabViewModel = fabViewModel,
                            onUploadFile = { onUploadFile("/") },
                            onUploadFolder = { onUploadFolder("/") },
                            onUploadImages = { onUploadImages("/") },
                            onSettingsClicked = { backStack.add(Destination.Settings) },
                            onDeleteSelection = selectionViewModel::onDeleteClicked
                        )
                    }

                    entry<Destination.Shared> { _ ->
                        SharedScreen(
                            onOpenSharedFolder = { realmId, name ->
                                backStack.add(
                                    Destination.SharedBrowse(
                                        realmId = realmId,
                                        parentFileId = SharedFile.ROOT_PARENT,
                                        title = name,
                                    )
                                )
                            },
                            onRedeemInvite = {
                                backStack.add(Destination.RedeemInvite())
                            },
                        )
                    }

                    entry<Destination.RedeemInvite> { destination ->
                        RedeemInviteScreen(
                            viewModel = koinViewModel<RedeemInviteViewModel>(
                                key = "redeem_invite_${destination.link ?: "manual"}",
                                parameters = { parametersOf(destination.link) }
                            ),
                            onBack = goBack,
                        )
                    }

                    entry<Destination.ManageAccess> { destination ->
                        ManageAccessScreen(
                            viewModel = koinViewModel<ManageAccessViewModel>(
                                key = "manage_access_${destination.path}",
                                parameters = { parametersOf(destination.path) }
                            ),
                            onBack = goBack,
                        )
                    }

                    entry<Destination.SharedBrowse> { destination ->
                        SharedBrowseScreen(
                            viewModel = koinViewModel(
                                key = "shared_browse_${destination.realmId}_${destination.parentFileId}",
                                parameters = {
                                    parametersOf(
                                        destination.realmId,
                                        destination.parentFileId,
                                        destination.title,
                                    )
                                },
                            ),
                            onBack = goBack,
                            onOpenFolder = { fileId, name ->
                                backStack.add(
                                    Destination.SharedBrowse(
                                        realmId = destination.realmId,
                                        parentFileId = fileId,
                                        title = name,
                                    )
                                )
                            },
                        )
                    }

                    entry<Destination.Folder> { destination ->
                        val viewModel = koinViewModel<FilesScreenViewModel>(
                            key = "folder_${destination.path}",
                            parameters = { parametersOf(destination.path) }
                        )
                        FolderScreen(
                            folderPath = destination.path,
                            navigateUp = goBack,
                            showFolder = { path -> backStack.add(Destination.Folder(path)) },
                            showFile = { path -> backStack.add(Destination.FilePreview(path)) },
                            showFileDetails = showFileDetails,
                            showMovePicker = showMovePicker,
                            showManageAccess = showManageAccess,
                        showSyncedFolders = showSyncedFolders,
                            viewModel = viewModel,
                            fabViewModel = fabViewModel,
                            onUploadFile = { onUploadFile(destination.path) },
                            onUploadFolder = { onUploadFolder(destination.path) },
                            onUploadImages = { onUploadImages(destination.path) }
                        )
                    }

                    entry<Destination.FilePreview> { destination ->
                        // The preview hosts the same actions sheet as the list
                        // rows (proposal §2.1). Its show/rename state is local
                        // to this entry rather than lifted into a VM — the
                        // preview has no other use for it (proposal §7.2).
                        var actionsForPath by remember { mutableStateOf<String?>(null) }
                        var renameForPath by remember { mutableStateOf<String?>(null) }

                        // Route to the viewer for the file type (derived from the
                        // name's extension). Images use the zoomable bitmap viewer,
                        // PDFs the paginated viewer (drive-content-preview.md §4);
                        // everything else gets the honest fallback until its per-type
                        // viewer lands (video — §5). All branches share the actions
                        // sheet hosted below.
                        //
                        // The image branch also asks `isImagePreviewSupported`: being
                        // an image isn't enough, the platform has to be able to draw
                        // it. HEIC on a non-Mac desktop can't be (Skia has no HEIF
                        // codec), so it takes the fallback and its open-in-another-app
                        // hand-off instead of failing inside the viewer.
                        //
                        // ⚠ The video branch asks the same question for the same reason.
                        // Desktop has no bundled codec, and a viewer whose whole content
                        // is "playback isn't available" is a dead end — the fallback at
                        // least hands the file to the OS's own player, which every
                        // desktop has. Keeping the test here rather than inside the
                        // viewer is what leaves `VideoPreviewScreen` with no unsupported
                        // state to render.
                        val navigateUp: () -> Unit = goBack
                        val showActions: () -> Unit = { actionsForPath = destination.path }
                        val fileName = destination.path.substringAfterLast('/')
                        when {
                            fileCategoryForName(fileName) == FileCategory.Image &&
                                isImagePreviewSupported(fileName) -> ImagePreviewScreen(
                                filePath = destination.path,
                                navigateUp = navigateUp,
                                showFileActions = showActions,
                                viewModel = LocalImagePreviewViewModelFactory.current(
                                    destination.path
                                )
                            )

                            isPdf(fileName) -> PdfPreviewScreen(
                                filePath = destination.path,
                                navigateUp = navigateUp,
                                showFileActions = showActions,
                                viewModel = LocalPdfPreviewViewModelFactory.current(
                                    destination.path
                                )
                            )

                            fileCategoryForName(fileName) == FileCategory.Video &&
                                isVideoPlaybackSupported() -> VideoPreviewScreen(
                                filePath = destination.path,
                                navigateUp = navigateUp,
                                showFileActions = showActions,
                                viewModel = LocalVideoPreviewViewModelFactory.current(
                                    destination.path
                                )
                            )

                            else -> UnsupportedPreviewScreen(
                                filePath = destination.path,
                                navigateUp = navigateUp,
                                showFileActions = showActions,
                                viewModel = LocalOpenExternallyViewModelFactory.current(
                                    destination.path
                                )
                            )
                        }

                        AppBottomSheet(
                            visible = actionsForPath != null,
                            onDismissRequest = { actionsForPath = null }
                        ) {
                            FileActionsBottomSheet(
                                // The preview always hosts a file (never a folder),
                                // so no folder-level override row.
                                viewModel = LocalFileActionsViewModelFactory.current(actionsForPath!!, false),
                                onActionCompleted = { actionsForPath = null },
                                onMoveRequested = showMovePicker,
                                onRenameRequested = { path -> renameForPath = path },
                                onDetailsRequested = showFileDetails,
                                // The preview shows exactly the deleted file, so
                                // pop back to the list once it's gone.
                                onDeleteCompleted = navigateUp,
                            )
                        }

                        if (renameForPath != null) {
                            RenameFileDialog(
                                onDismissRequest = { renameForPath = null },
                                filePath = renameForPath!!
                            )
                        }
                    }

                    entry<Destination.FileDetails> { destination ->
                        FileDetailsScreen(
                            viewModel = koinViewModel(
                                key = "file_details_${destination.path}",
                                parameters = { parametersOf(destination.path) }
                            ),
                            onBack = goBack
                        )
                    }

                    entry<Destination.MovePicker> { destination ->
                        // Drilling pushes a deeper MovePicker (same source, child
                        // folder); the back chevron pops one level. A completed or
                        // cancelled move pops the whole picker stack back to origin.
                        val popPicker = {
                            while (backStack.lastOrNull() is Destination.MovePicker) {
                                backStack.removeAt(backStack.size - 1)
                            }
                        }
                        MovePickerScreen(
                            viewModel = koinViewModel(
                                key = "move_picker_${destination.sourcePaths.joinToString(",")}_${destination.folderPath}",
                                parameters = {
                                    parametersOf(destination.sourcePaths, destination.folderPath)
                                },
                            ),
                            onNavigateUp = goBack,
                            onOpenFolder = { path ->
                                backStack.add(
                                    Destination.MovePicker(
                                        sourcePaths = destination.sourcePaths,
                                        folderPath = path,
                                    )
                                )
                            },
                            onFinish = popPicker,
                        )
                    }

                    entry<Destination.Settings> { _ ->
                        SyncPreferencesScreen(
                            onBack = goBack,
                            onSyncedFolders = { backStack.add(Destination.SyncedFolders) },
                            onLicences = { backStack.add(Destination.Licences) },
                        )
                    }

                    // Drive draws no map and shows no third-party data: libraries only.
                    entry<Destination.Licences> { _ ->
                        OpenSourceLicencesScreen(onBack = goBack)
                    }

                    entry<Destination.SyncedFolders> { _ ->
                        SyncedFoldersScreen(
                            onBack = goBack,
                            onAddBackupFolder = onAddBackupFolder,
                            onRequestMediaAccess = onRequestMediaAccess,
                        )
                    }
                }
            )
        }
    }

        fabUiState.folderUpload?.let { folderUpload ->
            if (folderUpload.conflict == null && folderUpload.cloudPreflight == null) {
                FolderUploadStatusBar(
                    state = folderUpload,
                    onCancel = fabViewModel::cancelFolderUpload,
                    onDismiss = fabViewModel::dismissFolderUpload,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
        }
    }

    fabUiState.folderUpload?.conflict?.let { conflict ->
        FolderUploadConflictDialog(
            conflict = conflict,
            onResolve = fabViewModel::resolveFolderConflict,
        )
    }

    fabUiState.folderUpload?.cloudPreflight?.let { preflight ->
        FolderUploadPreflightDialog(
            preflight = preflight,
            onDecision = fabViewModel::resolveFolderCloudPreflight,
        )
    }

    // Mode-A create-share-link sheet — multi-select entry point
    // (docs/file-sharing.md). The per-file FileActions sheet hosts its
    // own copy inside `FileActionsBottomSheet` to keep host wiring zero; the
    // multi-select sheet uses this top-level instance because there's no VM
    // owning the lifted state.
    createShareLinkForPath?.let { path ->
        AppBottomSheet(
            visible = true,
            onDismissRequest = { createShareLinkForPath = null }
        ) {
            val shareLinkViewModel = koinViewModel<CreateShareLinkViewModel>(
                key = "create_share_link_multi_$path",
                parameters = { parametersOf(path) }
            )
            CreateShareLinkSheet(
                viewModel = shareLinkViewModel,
                onDismiss = { createShareLinkForPath = null }
            )
        }
    }

    // Tier-1 storage gate (storage-capacity-gating.md §2): a share / share-link
    // blocked for want of storage raises this dialog instead of the sheet.
    storageGateBlock?.let { decision ->
        StorageGateDialog(
            decision = decision,
            onDismiss = selectionViewModel::onStorageGateDismissed,
        )
    }
}
