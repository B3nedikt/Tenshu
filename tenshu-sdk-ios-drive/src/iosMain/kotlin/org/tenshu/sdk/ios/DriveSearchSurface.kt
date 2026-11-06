package org.tenshu.sdk.ios

import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.ui.common.search.FilesSearchBarViewModel

/**
 * Wires Drive's **file search** view model for the SwiftUI client (`ios-drive-parity.md` §24).
 *
 * Android and desktop search through a real proxy-side `Contains` operation; this surface brings
 * the same to iOS with no new op, schema or wire change: [FilesSearchBarViewModel] and its
 * [FilesRepository.searchFiles] source are `commonMain` and already compiled for iOS.
 *
 * The VM owns the debounced (250 ms) search-as-you-type pipeline, so Swift binds `onQueryChanged`
 * per keystroke and does no debouncing of its own. Result taps and per-row actions are deliberately
 * *not* on the VM — they are navigation/host concerns the platform hoists (Android does the same
 * from `FilesScreen`), so SwiftUI routes them through its own `FilesRouter`.
 *
 * Follows the sibling surfaces ([DriveShareSurface], [DriveDetailSurface]): the parameterless entry
 * point binds whatever [DriveReadSurface] has installed — fakes by default so previews and
 * no-runtime bring-up work, the live route-1 repo once `installLocalRuntime()` has run — and the
 * explicit-dependency overload exists for tests that install their own configured fake.
 */
object DriveSearchSurface {

    /** The search bar over the installed repository. */
    fun search(): FilesSearchBarViewModel = search(DriveReadSurface.installedRepository())

    fun search(repository: FilesRepository): FilesSearchBarViewModel =
        FilesSearchBarViewModel(repository)
}
