package app.tenshu.control.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.net.URI

@Composable
actual fun rememberOpenExternalUrlLauncher(): OpenExternalUrlLauncher {
    return remember {
        // Capability probed once: a headless / unsupported environment hides the action.
        val supported = Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        OpenExternalUrlLauncher(canOpen = supported) { url ->
            try {
                Desktop.getDesktop().browse(URI(url))
            } catch (_: Throwable) {
                // Malformed URI / no registered browser / IO error — swallow so it never crashes
                // the checkout screen.
            }
        }
    }
}
