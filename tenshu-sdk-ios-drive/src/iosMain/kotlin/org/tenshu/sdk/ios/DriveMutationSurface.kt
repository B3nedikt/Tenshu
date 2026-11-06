package org.tenshu.sdk.ios

import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.ui.common.createfolder.CreateFolderDialogViewModel
import app.tenshu.drive.ui.common.rename.RenameFileDialogViewModel
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.drive.ui.screens.movepicker.MovePickerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.paging.TenshuPagingPresenter
import kotlin.coroutines.CoroutineContext

/**
 * Wires the file-**mutation** view models (`:drive-shared-logic`) for the SwiftUI client — create
 * folder, rename, move — over the same injected [FilesRepository] as the list/detail surfaces
 * ([DriveReadSurface.installedRepository]; default [FakeFilesRepository], production's real repo via
 * [DriveReadSurface.installRealClient]). Full-parity step B.
 */
object DriveMutationSurface {

    /** Create-folder dialog for [folderPath]. Writes run on the view model's scope; watch `uiState`. */
    fun createFolder(folderPath: String): CreateFolderDialogViewModel =
        createFolder(DriveReadSurface.installedRepository(), folderPath)

    fun createFolder(repository: FilesRepository, folderPath: String): CreateFolderDialogViewModel =
        CreateFolderDialogViewModel(repository, folderPath)

    /** Rename dialog for [filePath]. */
    fun rename(filePath: String): RenameFileDialogViewModel =
        rename(DriveReadSurface.installedRepository(), filePath)

    fun rename(repository: FilesRepository, filePath: String): RenameFileDialogViewModel =
        RenameFileDialogViewModel(repository, filePath)

    /** A move-picker level: its view model + a presenter collecting the folders-only paged listing. */
    class MoveBound internal constructor(
        val viewModel: MovePickerViewModel,
        val presenter: TenshuPagingPresenter<FileUiModel>,
    )

    /**
     * Move-picker for moving [sourcePath] while browsing [folderPath] (the host pushes a fresh level
     * per drill-in, mirroring the Files list). SwiftUI entry uses the main dispatcher.
     */
    fun movePicker(sourcePath: String, folderPath: String): MoveBound =
        movePicker(DriveReadSurface.installedRepository(), sourcePath, folderPath, CoroutineScope(Dispatchers.Main), Dispatchers.Main)

    fun movePicker(
        repository: FilesRepository,
        sourcePath: String,
        folderPath: String,
        scope: CoroutineScope,
        presenterContext: CoroutineContext,
    ): MoveBound {
        val viewModel = MovePickerViewModel(repository, sourcePath, folderPath)
        val presenter = TenshuPagingPresenter<FileUiModel>(scope, presenterContext)
        presenter.collect(viewModel.pagedFolders)
        return MoveBound(viewModel, presenter)
    }
}
