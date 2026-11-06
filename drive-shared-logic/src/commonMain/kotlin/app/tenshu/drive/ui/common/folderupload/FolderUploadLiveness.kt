package app.tenshu.drive.ui.common.folderupload

import app.tenshu.drive.ui.common.addfileactions.FolderUploadState
import app.tenshu.liveness.NotificationLiveness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Drive-side analogue of Control's `ForegroundTaskLiveness`
 * (`docs/proposals/drive-folder-upload.md` §5d). The folder-upload orchestration
 * loop runs in the **Drive** process (SAF enumeration needs Drive's content
 * resolver — §2.1); Drive has no foreground service, so a backgrounded Drive is
 * OS-reclaimable and the loop dies mid-upload. This observes the Drive-owned
 * [FolderUploadController.state], maps it via the pure
 * [folderUploadNotificationContent], and drives a foreground service for the
 * lifetime of an in-flight upload.
 *
 * The idle ⇄ in-flight ⇄ terminal **edge logic** now lives in the generic
 * [NotificationLiveness] (shared with Synced-Folders auto-backup — §9
 * "generalise it to observe either controller, don't fork it"); this is the
 * thin folder-upload binding (state type + copy mapper). The Android effect is
 * `FolderUploadForegroundService` behind [FolderUploadNotificationHandle].
 */
class FolderUploadLiveness(
    state: StateFlow<FolderUploadState?>,
    handle: FolderUploadNotificationHandle,
) {
    private val delegate = NotificationLiveness(
        state = state,
        toContent = ::folderUploadNotificationContent,
        handle = handle,
    )

    fun launchIn(scope: CoroutineScope) = delegate.launchIn(scope)

    /** Test seam: drive one observed state value through the edge logic. */
    internal fun onState(state: FolderUploadState?) = delegate.onState(state)
}
