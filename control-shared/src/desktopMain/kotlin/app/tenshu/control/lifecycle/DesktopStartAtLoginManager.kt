package app.tenshu.control.lifecycle

/**
 * Selects the login-start mechanism for the current desktop bundle.
 *
 * MAS builds use the embedded SMAppService login item. Unsandboxed development
 * and Developer ID builds retain the existing LaunchAgent implementation.
 *
 * ⚠ Off macOS there is no mechanism yet ([DesktopShellSupport.startAtLoginAvailable]): every call
 * is inert and [isAutostartEnabled] answers `false`, rather than writing a LaunchAgent plist into
 * `~/Library/LaunchAgents` on Windows and then reporting it as enabled.
 */
class DesktopStartAtLoginManager(
    private val daemonExecutablePath: String?,
    private val daemonAgent: LaunchAgentManager = LaunchAgentManager.forDaemon(),
    private val trayAgent: LaunchAgentManager = LaunchAgentManager.forTray(),
    private val macLoginItem: MacLoginItemManager =
        MacLoginItemManager(daemonExecutablePath.orEmpty()),
    private val supported: Boolean = DesktopShellSupport.current.startAtLoginAvailable,
) {
    fun isAutostartEnabled(): Boolean = when {
        !supported -> false
        macLoginItem.isAvailable() -> macLoginItem.isAutostartEnabled()
        else -> daemonAgent.isAutostartEnabled()
    }

    fun isApprovalRequired(): Boolean =
        supported && macLoginItem.isAvailable() && macLoginItem.isApprovalRequired()

    fun openLoginItemsSettings(): Boolean =
        supported && macLoginItem.isAvailable() && macLoginItem.openSystemSettingsLoginItems()

    fun enable(): Boolean = when {
        !supported -> false
        macLoginItem.isAvailable() -> macLoginItem.enable()
        else -> daemonExecutablePath?.let {
            daemonAgent.writePlist(it)
            daemonAgent.enable()
            daemonAgent.isAutostartEnabled()
        } ?: false
    }

    fun disable(): Boolean = when {
        !supported -> false
        macLoginItem.isAvailable() -> macLoginItem.disable()
        else -> {
            daemonAgent.uninstall()
            trayAgent.uninstall()
        }
    }
}
