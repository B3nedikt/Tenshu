package app.tenshu.drive.ui.common

import app.tenshu.macos.MacImageDecoder

/**
 * Skia covers the everyday raster formats; HEIC/HEIF need the [MacImageDecoder]
 * bridge, which exists only on macOS. On Windows and Linux those files route to
 * the open-in-another-app fallback rather than to a viewer that would fail.
 */
actual fun isImagePreviewSupported(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val needsImageIo = extension == "heic" || extension == "heif"
    return !needsImageIo || MacImageDecoder.isAvailable()
}
