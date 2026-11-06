package app.tenshu.drive.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.io.File

@Composable
actual fun rememberOpenFileLauncher(): OpenFileLauncher {
    return remember {
        // Desktop.OPEN launches the default application registered for the file's type
        // (no FileProvider needed — the JVM hands over a real path). Capability is
        // probed once: a headless / unsupported environment hides the action.
        val supported = Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
        OpenFileLauncher(canOpenExternally = supported) { filePath, _ ->
            try {
                Desktop.getDesktop().open(File(filePath))
            } catch (_: Throwable) {
                // No registered handler / IO error — swallow so it never crashes the
                // preview screen.
            }
        }
    }
}
