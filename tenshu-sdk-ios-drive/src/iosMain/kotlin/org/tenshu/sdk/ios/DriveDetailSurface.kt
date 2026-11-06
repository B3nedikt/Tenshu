package org.tenshu.sdk.ios

import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.NoopSyncRulesPreferences
import app.tenshu.drive.ui.common.details.FileActionsViewModel
import app.tenshu.drive.ui.screens.filedetails.FileDetailsViewModel

/**
 * Wires the file **detail + actions** view models (`:drive-shared-logic`) for one file so a SwiftUI
 * detail screen can observe their `uiState` and invoke actions — the full-parity counterpart of
 * [DriveReadSurface] for the list. Binds the same injected [FilesRepository] (default
 * [FakeFilesRepository]; production's real `TenshuFilesRepository` via [DriveReadSurface.installRealClient]),
 * so the detail screen is drivable + testable without a proxy.
 *
 * [FileDetailsViewModel] streams the header + metadata ([FilesRepository.fileDetails]) and history
 * ([FilesRepository.fileActivity]); [FileActionsViewModel] backs the offline pin (download/evict),
 * delete, and the share-link trigger. Both run their reactive work on `viewModelScope` (the iOS main
 * run loop), so they come alive while the app is running — the seed state is synchronous.
 */
object DriveDetailSurface {

    /** The detail + actions view models for one file. */
    class Bound internal constructor(
        val details: FileDetailsViewModel,
        val actions: FileActionsViewModel,
    )

    /** SwiftUI entry point — binds the installed repository for [path]. */
    fun create(path: String, isFolder: Boolean): Bound =
        create(DriveReadSurface.installedRepository(), path, isFolder)

    /** Canonical builder — binds [repository] explicitly (tests/previews inject a configured fake). */
    fun create(repository: FilesRepository, path: String, isFolder: Boolean): Bound {
        val details = FileDetailsViewModel(path, repository)
        val actions = actions(repository, path, isFolder)
        return Bound(details, actions)
    }

    /**
     * The **actions-only** view model for one file — no [FileDetailsViewModel], so no header/activity
     * streaming is started. Lets a row context menu (SwiftUI Files list) fire a one-shot delete /
     * offline toggle without paying for the detail surface it never shows.
     */
    fun actions(path: String, isFolder: Boolean): FileActionsViewModel =
        actions(DriveReadSurface.installedRepository(), path, isFolder)

    private fun actions(repository: FilesRepository, path: String, isFolder: Boolean): FileActionsViewModel =
        FileActionsViewModel(
            filesRepository = repository,
            // P5: the installed seams — real per-device offline pinning over the live runtime.
            offlinePreferences = DriveReadSurface.installedOfflinePreferences(),
            syncRulesPreferences = DriveReadSurface.installedSyncRulesPreferences(),
            filePath = path,
            isFolder = isFolder,
        )
}
