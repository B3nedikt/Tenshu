package app.tenshu.drive.ui.common

import java.io.File

// Shared by android + desktop (both JVM). On Android `java.io.tmpdir` is the app's
// private cache dir (set by ActivityThread), so the decrypted bytes never leave the
// app sandbox and the file is exposable to other apps via the Drive FileProvider
// (whose `<cache-path>` root covers it — drive-android `file_provider_paths.xml`).
actual fun writePreviewTempFile(bytes: ByteArray, fileExtension: String): String? = try {
    val suffix = if (fileExtension.isBlank()) ".tmp" else ".${fileExtension.lowercase()}"
    val file = File.createTempFile("preview-", suffix)
    file.writeBytes(bytes)
    file.absolutePath
} catch (e: Throwable) {
    null
}

actual fun deletePreviewTempFile(filePath: String) {
    try {
        File(filePath).delete()
    } catch (_: Throwable) {
    }
}
