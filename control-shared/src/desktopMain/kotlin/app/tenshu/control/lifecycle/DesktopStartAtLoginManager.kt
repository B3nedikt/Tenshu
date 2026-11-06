package app.tenshu.control.lifecycle

/**
 * Selects the login-start mechanism for the current desktop bundle.
 *
 * MAS builds use the embedded SMAppService login item. Unsandboxed development
 * and Developer ID builds retain the existing LaunchAgent implementation.
 */
class DesktopStartAtLoginManager(
    private val daemonExecutablePath: String?,
    private val daemonAgent: LaunchAgentManager = LaunchAgentManager.forDaemon(),
    private val trayAgent: LaunchAgentManager = LaunchAgentManager.forTray(),
    private val macLoginItem: MacLoginItemManager =
        MacLoginItemManager(daemonExecutablePath.orEmpty()),
) {
    fun isAutostartEnabled(): Boolean =
        if (macLoginItem.isAvailable()) {
            macLoginItem.isAutostartEnabled()
        } else {
            daemonAgent.isAutostartEnabled()
        }

    fun isApprovalRequired(): Boolean =
        macLoginItem.isAvailable() && macLoginItem.isApprovalRequired()

    fun openLoginItemsSettings(): Boolean =
        macLoginItem.isAvailable() && macLoginItem.openSystemSettingsLoginItems()

    fun enable(): Boolean =
        if (macLoginItem.isAvailable()) {
            macLoginItem.enable()
        } else {
            daemonExecutablePath?.let {
                daemonAgent.writePlist(it)
                daemonAgent.enable()
                daemonAgent.isAutostartEnabled()
            } ?: false
        }

    fun disable(): Boolean =
        if (macLoginItem.isAvailable()) {
            macLoginItem.disable()
        } else {
            daemonAgent.uninstall()
            trayAgent.uninstall()
        }
}
