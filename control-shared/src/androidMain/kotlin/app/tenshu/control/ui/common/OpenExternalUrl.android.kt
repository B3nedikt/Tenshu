package app.tenshu.control.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberOpenExternalUrlLauncher(): OpenExternalUrlLauncher {
    val context = LocalContext.current
    return remember(context) {
        OpenExternalUrlLauncher(canOpen = true) { url ->
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            try {
                context.startActivity(view)
            } catch (_: Throwable) {
                // No installed browser / activity to handle the URL — swallow so it never crashes
                // the checkout screen. The user can retry from the same screen.
            }
        }
    }
}
