package app.tenshu.drive.di

import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.ingest.backup.MediaSourceProvider
import app.tenshu.drive.domain.BackupProgress
import app.tenshu.drive.domain.BackupWaitCause
import app.tenshu.drive.domain.BackupWaiting
import app.tenshu.ingest.backup.BackupPauseReason
import app.tenshu.ingest.backup.BackupState
import app.tenshu.ingest.backup.SyncedFolderController
import app.tenshu.reachability.MutableSchemaSkewSource
import app.tenshu.drive.ui.common.folderupload.FolderUploadController
import kotlinx.coroutines.flow.map
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.ui.common.createfolder.CreateFolderDialogViewModel
import app.tenshu.drive.ui.common.details.FileActionsViewModel
import app.tenshu.drive.ui.common.preview.ImagePreviewViewModel
import app.tenshu.drive.ui.common.preview.OpenExternallyViewModel
import app.tenshu.drive.ui.common.preview.PdfPreviewViewModel
import app.tenshu.drive.ui.common.preview.VideoPreviewViewModel
import app.tenshu.drive.ui.common.rename.RenameFileDialogViewModel
import app.tenshu.drive.ui.common.search.FilesSearchBarViewModel
import app.tenshu.drive.ui.access.InviteLinkViewModel
import app.tenshu.drive.ui.access.ManageAccessViewModel
import app.tenshu.drive.ui.contacts.ContactPromptViewModel
import app.tenshu.drive.ui.contacts.InviteBadgeViewModel
import app.tenshu.drive.ui.contacts.RedeemInviteViewModel
import app.tenshu.drive.ui.screens.filedetails.FileDetailsViewModel
import app.tenshu.drive.ui.screens.files.FilesScreenViewModel
import app.tenshu.drive.ui.screens.home.HomeScreenViewModel
import app.tenshu.drive.ui.screens.movepicker.MovePickerViewModel
import app.tenshu.drive.ui.screens.settings.SyncPreferencesViewModel
import app.tenshu.ingest.backup.FullMediaAccessProvider
import app.tenshu.ingest.backup.MediaAccessProvider
import app.tenshu.drive.ui.screens.settings.SyncedFoldersViewModel
import app.tenshu.drive.ui.screens.shared.SharedBrowseViewModel
import app.tenshu.drive.ui.screens.shared.SharedScreenViewModel
import app.tenshu.drive.ui.selection.SelectionViewModel
import app.tenshu.drive.ui.share.CreateShareLinkViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

fun viewModelsModule() = module {
    single { SelectionViewModel(get(), get(), get()) }
    // (FilesRepository, ContactsRepository, MembershipRepository)
    viewModel { SharedScreenViewModel(get(), get(), get()) }
    // Manage access / contact handshake (drive-manage-access-ux.md §8)
    // (FilesRepository, ContactsRepository, MembershipRepository, folderPath)
    viewModel { parameters -> ManageAccessViewModel(get(), get(), get(), parameters.get()) }
    viewModel { parameters -> InviteLinkViewModel(get(), parameters.get()) }
    // (ContactsRepository, FilesRepository, MembershipRepository, inviteId)
    viewModel { parameters -> ContactPromptViewModel(get(), parameters.get()) }
    viewModel { parameters -> RedeemInviteViewModel(get(), parameters.getOrNull()) }
    viewModel { InviteBadgeViewModel(get()) }
    viewModel { (realmId: String, parentFileId: String, title: String) ->
        SharedBrowseViewModel(realmId, parentFileId, title, get())
    }
    viewModel { parameters -> CreateShareLinkViewModel(get(), parameters.get()) }
    viewModel { HomeScreenViewModel(get(), get()) }
    viewModel { parameters ->
        FilesScreenViewModel(
            get(),
            get(),
            parameters.get(),
            get(),
            // P6: the banner stands down while Drive's folder-upload bar is narrating the same
            // operation. `state` is the app-scoped controller's, so it outlives this ViewModel and
            // a screen re-entered mid-upload still suppresses correctly.
            folderUploadActive = get<FolderUploadController>().state.map { it != null },
            // While an auto-backup drain runs, its figures — not the upload queue's — are the
            // banner's "N of M". Same app-scoped-controller reasoning as above; see
            // [app.tenshu.drive.domain.BackupProgress] for why the queue's denominator grows
            // under a backup and the drain's does not.
            backupProgress = get<SyncedFolderController>().state.map { state ->
                (state as? BackupState.BackingUp)?.let {
                    // ⚠ `remainingBytes` rides along, and is half the banner's "N left" — the
                    // proxy's journal is the other half (§14.4). Passing only the counts would
                    // leave the byte figure reporting the file in flight, which is the defect.
                    BackupProgress(it.completed, it.total, it.remainingBytes)
                }
            },
            // ST-2b: the banner's Stop. Same app-scoped controller, one verb.
            stopBackup = { get<SyncedFolderController>().stop() },
            // SK-1: the announce refusal, the one Control state Drive's banner never had.
            schemaSkew = get<MutableSchemaSkewSource>().flow,
            // Only the two POLICY pauses. Storage and no-connection pauses already have banner
            // states of their own, derived from the proxy's figures rather than the drain's.
            backupWaiting = get<SyncedFolderController>().state.map { state ->
                (state as? BackupState.Paused)?.let { paused ->
                    when (paused.reason) {
                        BackupPauseReason.METERED_NETWORK -> BackupWaitCause.WIFI
                        BackupPauseReason.NOT_CHARGING -> BackupWaitCause.CHARGING
                        BackupPauseReason.NO_STORAGE,
                        BackupPauseReason.OUT_OF_SPACE,
                        BackupPauseReason.NO_NETWORK -> null
                    }?.let { cause -> BackupWaiting(cause, paused.waiting) }
                }
            },
        )
    }
    viewModel { (sourcePaths: List<String>, folderPath: String) ->
        MovePickerViewModel(get(), sourcePaths, folderPath)
    }
    viewModel { parameters -> FileActionsViewModel(get(), get(), get(), parameters.get(), parameters.get()) }
    viewModel { parameters -> FileDetailsViewModel(parameters.get(), get()) }
    viewModel { parameters -> CreateFolderDialogViewModel(get(), parameters.get()) }
    viewModel { parameters -> RenameFileDialogViewModel(get(), parameters.get()) }
    viewModel { AddFileActionsViewModel(get(), get()) }
    viewModel { parameters -> ImagePreviewViewModel(parameters.get(), get(), get()) }
    viewModel { parameters -> PdfPreviewViewModel(parameters.get(), get()) }
    viewModel { parameters -> VideoPreviewViewModel(parameters.get(), get()) }
    viewModel { parameters -> OpenExternallyViewModel(parameters.get(), get()) }
    viewModel { FilesSearchBarViewModel(get()) }
    viewModel { SyncPreferencesViewModel(get(), get()) }
    // (controller, BackupPreferences, MediaSourceProvider, FilesRepository, BackupFolderGrants)
    // MediaAccessProvider is Android-only; elsewhere there is no partial-access
    // concept, so the shared FullMediaAccessProvider stands in.
    viewModel {
        SyncedFoldersViewModel(
            get(), get(), get(), get(), get(),
            getOrNull<MediaAccessProvider>() ?: FullMediaAccessProvider,
        )
    }
}
