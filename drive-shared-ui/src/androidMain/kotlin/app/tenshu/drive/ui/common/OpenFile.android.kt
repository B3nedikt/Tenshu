package app.tenshu.drive.ui.common

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

@Composable
actual fun rememberOpenFileLauncher(): OpenFileLauncher {
    val context = LocalContext.current
    return remember(context) {
        OpenFileLauncher(canOpenExternally = true) { filePath, fileName ->
            // A raw file:// URI would throw FileUriExposedException on API 24+, so the
            // app-private temp file is shared through the Drive FileProvider (declared
            // in drive-android's manifest; authority = "<applicationId>.fileprovider").
            // The temp file lives under java.io.tmpdir == the app cache dir, which the
            // provider's <cache-path> root covers.
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, File(filePath))
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension) ?: "*/*"
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                // Grant the resolved app temporary read access to the content URI.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(view, null))
            } catch (_: Throwable) {
                // No installed app can handle the type, or the URI grant was refused —
                // swallow so a missing handler never crashes the preview screen.
            }
        }
    }
}
