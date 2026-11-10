package app.tenshu.control.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopMac
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.FolderOpen
import app.tenshu.control.bridge.ExplorerIntegration
import app.tenshu.control.shared.resources.settings_explorer_integration_headline
import app.tenshu.control.shared.resources.settings_explorer_integration_support
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.tenshu.control.lifecycle.DesktopControlSettings
import app.tenshu.control.lifecycle.DesktopShellSupport
import app.tenshu.control.lifecycle.DesktopStartAtLoginManager
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.settings_close_to_tray_headline
import app.tenshu.control.shared.resources.settings_close_to_tray_headline_windows
import app.tenshu.control.shared.resources.settings_close_to_tray_support
import app.tenshu.control.shared.resources.settings_close_to_tray_support_windows
import app.tenshu.control.shared.resources.settings_start_at_login_approval_support
import app.tenshu.control.shared.resources.settings_start_at_login_approval_support_windows
import app.tenshu.control.shared.resources.settings_start_at_login_headline
import app.tenshu.control.shared.resources.settings_start_at_login_support
import app.tenshu.ui.chrome.AppListItem
import org.jetbrains.compose.resources.stringResource

/**
 * The desktop menu-bar settings (slice 1E). Both managers are cheap, stateless
 * wrapper over the same device-local settings used by `Main.kt`. The
 * start-at-login manager selects SMAppService for MAS bundles and LaunchAgent
 * for unsandboxed development/Developer ID bundles. Local `mutableStateOf`
 * mirrors that state for the switches; there's no config-change recreation on
 * desktop, and re-entering Settings re-reads it.
 *
 * ⚠ Each switch is shown only where [DesktopShellSupport] says the OS has the feature: a
 * close-to-tray switch with no tray, or a start-at-login switch with no mechanism behind it, is a
 * promise the app cannot keep (desktop-windows-port.md §3.4).
 *
 * ⚠ Start-at-login calls run on [Dispatchers.IO], never in composition or a click handler: on Windows
 * each one starts the tray agent as a process (`WindowsStartupTask`), which can take seconds. The
 * switch is disabled while one is in flight.
 */
@Composable
actual fun PlatformSettingsSection() {
    val settings = remember { DesktopControlSettings() }
    val startAtLoginManager = remember {
        DesktopStartAtLoginManager(ProcessHandle.current().info().command().orElse(null))
    }

    var closeToTray by remember { mutableStateOf(settings.closeToTray()) }
    var startAtLogin by remember { mutableStateOf(false) }
    var startAtLoginApprovalRequired by remember { mutableStateOf(false) }
    var startAtLoginBusy by remember { mutableStateOf(true) }
    var explorer by remember { mutableStateOf(ExplorerIntegration.isEnabled()) }
    var explorerBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Registering, filling or emptying the folder is file and COM work: off the UI thread, one at a time.
    fun setExplorer(value: Boolean) {
        if (explorerBusy) return
        explorerBusy = true
        explorer = value
        scope.launch {
            try {
                // What it is now, not what was asked: a start Windows refused leaves it off.
                explorer = withContext(Dispatchers.IO) { ExplorerIntegration.setEnabled(value) }
            } finally {
                explorerBusy = false
            }
        }
    }

    suspend fun readStartAtLogin() {
        val (enabled, approval) = withContext(Dispatchers.IO) {
            startAtLoginManager.isAutostartEnabled() to startAtLoginManager.isApprovalRequired()
        }
        startAtLogin = enabled
        startAtLoginApprovalRequired = approval
    }

    LaunchedEffect(startAtLoginManager) {
        readStartAtLogin()
        startAtLoginBusy = false
    }

    fun setCloseToTray(value: Boolean) {
        closeToTray = value
        settings.setCloseToTray(value)
    }

    fun setStartAtLogin(value: Boolean) {
        if (startAtLoginBusy) return
        startAtLoginBusy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Re-read first: the user may have changed it in the OS's own settings since this screen opened.
                    startAtLoginManager.isAutostartEnabled()
                    if (value && startAtLoginManager.isApprovalRequired()) {
                        startAtLoginManager.openLoginItemsSettings()
                    } else if (value) {
                        startAtLoginManager.enable()
                    } else {
                        startAtLoginManager.disable()
                    }
                }
                readStartAtLogin()
            } finally {
                startAtLoginBusy = false
            }
        }
    }

    val shell = DesktopShellSupport.current
    // The OS decides the wording, not how the tray is drawn: a packaged Windows Control can start at login
    // even where AWT reports no system tray.
    val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    // The Windows tray lives in the notification area, not a menu bar; the wording follows it.
    if (shell.trayAvailable) AppListItem(
        headline = stringResource(
            if (windows) Res.string.settings_close_to_tray_headline_windows else Res.string.settings_close_to_tray_headline,
        ),
        supportingText = stringResource(
            if (windows) Res.string.settings_close_to_tray_support_windows else Res.string.settings_close_to_tray_support,
        ),
        leadingContent = {
            Icon(if (windows) Icons.Default.DesktopWindows else Icons.Default.DesktopMac, contentDescription = null)
        },
        trailingContent = { Switch(checked = closeToTray, onCheckedChange = ::setCloseToTray) },
        onClick = { setCloseToTray(!closeToTray) },
    )
    if (shell.startAtLoginAvailable) AppListItem(
        headline = stringResource(Res.string.settings_start_at_login_headline),
        supportingText = stringResource(
            if (startAtLoginApprovalRequired) {
                // Windows: turned off in its Settings, the one place allowed to turn it back on.
                if (windows) Res.string.settings_start_at_login_approval_support_windows
                else Res.string.settings_start_at_login_approval_support
            } else {
                Res.string.settings_start_at_login_support
            },
        ),
        leadingContent = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) },
        trailingContent = {
            Switch(checked = startAtLogin, onCheckedChange = ::setStartAtLogin, enabled = !startAtLoginBusy)
        },
        onClick = { setStartAtLogin(!startAtLogin) },
    )
    if (ExplorerIntegration.supported) AppListItem(
        headline = stringResource(Res.string.settings_explorer_integration_headline),
        supportingText = stringResource(Res.string.settings_explorer_integration_support),
        leadingContent = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
        trailingContent = { Switch(checked = explorer, onCheckedChange = ::setExplorer, enabled = !explorerBusy) },
        onClick = { setExplorer(!explorer) },
    )
}
