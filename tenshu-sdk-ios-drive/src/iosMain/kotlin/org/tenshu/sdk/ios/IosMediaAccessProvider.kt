package org.tenshu.sdk.ios

import app.tenshu.ingest.backup.MediaAccess
import app.tenshu.ingest.backup.MediaAccessProvider
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary

/**
 * iOS twin of `AndroidMediaAccessProvider`
 * (`drive-synced-folders-new-folder-prompt.md` §5) — the same three-state grant,
 * arrived at from the other direction.
 *
 * iOS shipped **limited photo access** in iOS 14, three years before Android's
 * `READ_MEDIA_VISUAL_USER_SELECTED`, and it is far more common in the wild because
 * the system picker offers it prominently on the *first* prompt. It behaves the
 * same way: PhotoKit keeps answering every fetch, silently scoped to the assets the
 * user picked, so nothing errors — the library simply appears smaller than it is.
 *
 * Unlike Android there is no ordering trap: [PHPhotoLibrary.authorizationStatusForAccessLevel]
 * returns one enum, and `limited` is its own case.
 *
 * ⚠ Read against `PHAccessLevelReadWrite`, matching the level `PhotoBackupView`
 * requests. Asking about `addOnly` would report a grant that permits writing to the
 * library but not reading it — the opposite of what backup needs.
 */
class IosMediaAccessProvider : MediaAccessProvider {

    override fun current(): MediaAccess =
        when (PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)) {
            PHAuthorizationStatusAuthorized -> MediaAccess.FULL
            PHAuthorizationStatusLimited -> MediaAccess.PARTIAL
            // notDetermined / denied / restricted all mean "we cannot enumerate".
            // notDetermined is deliberately folded in: until the user has answered,
            // fetches return empty, which is operationally identical to denied.
            else -> MediaAccess.DENIED
        }
}
