package app.tenshu.control.lifecycle

/**
 * Selects the login-start mechanism for the current desktop bundle.
 *
 * MAS builds use the embedded SMAppService login item. Unsandboxed development
 * and Developer ID builds retain the existing LaunchAgent implementation.
 * The Windows MSIX uses its startup task, through the tray agent ([WindowsStartupTask]).
 *
 * ⚠ Where there is no mechanism ([DesktopShellSupport.startAtLoginAvailable]) every call is inert
 * and [isAutostartEnabled] answers `false`, rather than writing a LaunchAgent plist into
 * `~/Library/LaunchAgents` on Windows and then reporting it as enabled.
 */
class DesktopStartAtLoginManager(
    private val daemonExecutablePath: String?,
    private val daemonAgent: LaunchAgentManager = LaunchAgentManager.forDaemon(),
    private val trayAgent: LaunchAgentManager = LaunchAgentManager.forTray(),
    private val macLoginItem: MacLoginItemManager =
        MacLoginItemManager(daemonExecutablePath.orEmpty()),
    private val supported: Boolean = DesktopShellSupport.current.startAtLoginAvailable,
    private val windowsTask: WindowsStartupTask? = WindowsStartupTask.forThisProcess(),
) {
    /**
     * The Windows task's last known state. ⚠ Each fresh read starts the agent as a process, so only
     * [isAutostartEnabled] re-reads; the other calls answer from what it, [enable] or [disable] last saw.
     */
    private var windowsState: WindowsStartupTask.State? = null

    private fun windowsState(refresh: Boolean): WindowsStartupTask.State? {
        val task = windowsTask ?: return null
        if (refresh || windowsState == null) windowsState = task.state()
        return windowsState
    }

    fun isAutostartEnabled(): Boolean = when {
        !supported -> false
        windowsTask != null -> windowsState(refresh = true).isOn()
        macLoginItem.isAvailable() -> macLoginItem.isAutostartEnabled()
        else -> daemonAgent.isAutostartEnabled()
    }

    /**
     * macOS: the login item awaits approval in System Settings. Windows: the user turned the task
     * off in Settings → Apps → Startup, and ⚠ only that page may turn it back on.
     */
    fun isApprovalRequired(): Boolean = when {
        !supported -> false
        windowsTask != null -> windowsState(refresh = false) == WindowsStartupTask.State.DISABLED_BY_USER
        else -> macLoginItem.isAvailable() && macLoginItem.isApprovalRequired()
    }

    fun openLoginItemsSettings(): Boolean = when {
        !supported -> false
        windowsTask != null -> runCatching {
            ProcessBuilder("explorer.exe", "ms-settings:startupapps").start()
            true
        }.getOrDefault(false)
        else -> macLoginItem.isAvailable() && macLoginItem.openSystemSettingsLoginItems()
    }

    fun enable(): Boolean = when {
        !supported -> false
        windowsTask != null -> windowsTask.enable().also { windowsState = it }.isOn()
        macLoginItem.isAvailable() -> macLoginItem.enable()
        else -> daemonExecutablePath?.let {
            daemonAgent.writePlist(it)
            daemonAgent.enable()
            daemonAgent.isAutostartEnabled()
        } ?: false
    }

    fun disable(): Boolean = when {
        !supported -> false
        // ⚠ Success means the task is now off — not merely "not on": an agent that failed answers UNAVAILABLE,
        // and reporting that as a successful disable would hide a task still enabled at login.
        windowsTask != null -> windowsTask.disable().also { windowsState = it }.let {
            it == WindowsStartupTask.State.DISABLED || it == WindowsStartupTask.State.DISABLED_BY_USER
        }
        macLoginItem.isAvailable() -> macLoginItem.disable()
        else -> {
            daemonAgent.uninstall()
            trayAgent.uninstall()
        }
    }

    private fun WindowsStartupTask.State?.isOn() =
        this == WindowsStartupTask.State.ENABLED || this == WindowsStartupTask.State.ENABLED_BY_POLICY
}
