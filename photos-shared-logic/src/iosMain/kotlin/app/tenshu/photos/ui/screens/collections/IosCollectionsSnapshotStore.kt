package app.tenshu.photos.ui.screens.collections

import app.tenshu.outbox.NsFileOutboxStore
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

/**
 * The iOS [CollectionsSnapshotStore] (`photos-app.md` §36 step 2): the app container's **Caches**
 * directory — a cache, so iOS may reclaim it under storage pressure and it is never backed up;
 * losing it costs one placeholder start. The `iosOpenMoveJournal` shape over `:app-outbox`'s
 * atomic-write `NsFileOutboxStore`.
 *
 * ⚠⚠ ONE instance for the process: its [CollectionsSnapshotStore.fence] is what
 * orders `PhotosLocalRuntime.deprovision()`'s purge behind a write the Collections view model
 * (`PhotosSurface.collections()`) already has in flight. A second instance would carry a second fence,
 * and the purge could finish before that write lands.
 */
fun iosCollectionsSnapshotStore(): CollectionsSnapshotStore = IosSnapshotStore.instance

private object IosSnapshotStore {
    val instance: CollectionsSnapshotStore by lazy {
        val base = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .filterIsInstance<String>()
            .firstOrNull()
            ?: NSTemporaryDirectory()
        JsonCollectionsSnapshotStore(NsFileOutboxStore("$base/${JsonCollectionsSnapshotStore.DIRECTORY}"))
    }
}
