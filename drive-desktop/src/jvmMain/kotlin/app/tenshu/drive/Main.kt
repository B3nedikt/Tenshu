package app.tenshu.drive

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import app.tenshu.drive.di.domainModule
import app.tenshu.drive.di.filesRepositoryModule
import app.tenshu.drive.di.platformClientModule
import app.tenshu.drive.di.tenshuClientModule
import app.tenshu.drive.di.viewModelsModule
import app.tenshu.drive.ui.App
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.data.BackupFolderGrants
import app.tenshu.drive.data.BackupFolderPickResult
import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.ingest.backup.SyncedFolderController
import app.tenshu.ui.layout.LocalNavRailPreference
import app.tenshu.ui.layout.filePersistedNavRailPreference
import app.tenshu.ui.layout.rememberPersistedWindowState
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.setMain
import org.koin.core.context.GlobalContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.startKoin
import org.tenshu.core.common.app.OpenTargetInbox
import org.tenshu.core.common.storage.DesktopAppPaths
import org.tenshu.core.common.util.DesktopSavedStateRegistryOwner
import org.tenshu.core.common.util.DesktopViewModelStoreOwner
import java.awt.FileDialog
import kotlin.system.exitProcess

private const val REMOVE_FINDER_DOMAIN_FLAG = "--remove-finder-domain"

@OptIn(ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) {
    // A local reset's way in (scripts/reset-local-device-state.sh): the domain can only be
    // removed by a process this bundle is responsible for, so the script cannot do it itself.
    if (REMOVE_FINDER_DOMAIN_FLAG in args) {
        exitProcess(if (FinderIntegration.removeDomains()) 0 else 1)
    }
    Dispatchers.setMain(Dispatchers.Swing)
    val koin = startKoin {
        modules(
            platformClientModule,
            tenshuClientModule,
            domainModule,
            filesRepositoryModule(),
            viewModelsModule()
        )
    }.koin

    // Synced Folders on desktop: the resident Control daemon hosts the ingest
    // watcher itself (drive-synced-folders-phase2.md §4.2), so Drive-desktop only
    // *registers* its source (so Control knows which directories to watch) — it
    // must NOT run its own in-process drain, or it would double-scan alongside
    // Control's `FolderWatcher`. (Android keeps the full `start()`.)
    koin.get<SyncedFolderController>().startRegistrationOnly()

    FinderIntegration.registerDomain()

    application {
        val windowState = rememberPersistedWindowState(
            key = "drive",
            storageDir = DesktopAppPaths.root()
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "Tenshu Drive",
            state = windowState
        ) {
            val viewModelStoreOwner = remember { DesktopViewModelStoreOwner() }
            val savedStateRegistryOwner = remember { DesktopSavedStateRegistryOwner() }
            val scope = rememberCoroutineScope()
            // ⚠ `remember`ed: AdaptiveNavScaffold reads the stored value keyed on this
            // instance, so a new one per recomposition would re-read the file every frame.
            val navRailPreference = remember {
                filePersistedNavRailPreference(key = "drive", storageDir = DesktopAppPaths.root())
            }

            CompositionLocalProvider(
                LocalViewModelStoreOwner provides viewModelStoreOwner,
                LocalSavedStateRegistryOwner provides savedStateRegistryOwner,
                LocalLifecycleOwner provides savedStateRegistryOwner,
                LocalNavRailPreference provides navRailPreference
            ) {
                val addFileActionsViewModel: AddFileActionsViewModel = koinViewModel()

                TenshuTheme {
                    App(
                        openTargets = GlobalContext.get().get<OpenTargetInbox>().targets,
                        fabViewModel = addFileActionsViewModel,
                        onUploadFile = { parentPath ->
                            val dialog =
                                FileDialog(window, "Select File to Upload", FileDialog.LOAD)
                            dialog.isVisible = true
                            val file = dialog.file
                            val directory = dialog.directory
                            if (file != null && directory != null) {
                                val fullPath = java.io.File(directory, file).absolutePath
                                scope.launch {
                                    addFileActionsViewModel.uploadFile(fullPath, parentPath)
                                }
                            }
                        },
                        onUploadFolder = { parentPath ->
                            System.setProperty("apple.awt.fileDialogForDirectories", "true")
                            val dialog =
                                FileDialog(window, "Select Folder to Upload", FileDialog.LOAD)
                            dialog.isVisible = true
                            val folder = dialog.file
                            val directory = dialog.directory
                            System.setProperty("apple.awt.fileDialogForDirectories", "false")

                            if (folder != null && directory != null) {
                                val fullPath = java.io.File(directory, folder).absolutePath
                                scope.launch {
                                    addFileActionsViewModel.uploadFolder(fullPath, parentPath)
                                }
                            }
                        },
                        onAddBackupFolder = { report ->
                            scope.launch {
                                when (val result = koin.get<BackupFolderGrants>().pick()) {
                                    is BackupFolderPickResult.Added -> {
                                        koin.get<BackupPreferences>().addGrantedBucketAndSelect(result.id)
                                        report(result)
                                    }
                                    BackupFolderPickResult.Cancelled,
                                    BackupFolderPickResult.Unavailable,
                                    BackupFolderPickResult.Failed -> report(result)
                                }
                            }
                        },
                        onUploadImages = { parentPath ->
                            val dialog =
                                FileDialog(window, "Select Images to Upload", FileDialog.LOAD)
                            dialog.setFilenameFilter { _, name ->
                                val lower = name.lowercase()
                                lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(
                                    ".png"
                                ) || lower.endsWith(
                                    ".webp"
                                ) || lower.endsWith(".heic")
                            }
                            dialog.isVisible = true
                            val file = dialog.file
                            val directory = dialog.directory
                            if (file != null && directory != null) {
                                val fullPath = java.io.File(directory, file).absolutePath
                                scope.launch {
                                    addFileActionsViewModel.uploadFile(fullPath, parentPath)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
