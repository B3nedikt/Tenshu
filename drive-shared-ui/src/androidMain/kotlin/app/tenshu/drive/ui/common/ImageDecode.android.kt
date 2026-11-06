package app.tenshu.drive.ui.common

import android.os.Build

/**
 * `BitmapFactory` *is* the platform decoder, so Android renders whatever the OS
 * renders — including HEIC, but only from **API 28** (Android 9), where platform
 * HEIF support landed. Below that the module's `minSdk` 24 still applies and a
 * HEIC would decode to `null`, so those devices get the open-in-another-app
 * fallback rather than a broken viewer.
 */
actual fun isImagePreviewSupported(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val needsHeif = extension == "heic" || extension == "heif"
    return !needsHeif || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
}
