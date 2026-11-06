package app.tenshu.control.lifecycle

import app.tenshu.macos.MacLoginItemService
import java.io.File

/**
 * Controls the sandbox-compatible macOS login item.
 *
 * MAS builds cannot write ~/Library/LaunchAgents or invoke launchctl. Control.app calls
 * SMAppService in-process because the API resolves the nested login item relative to the
 * containing bundle. The nested app only launches Control at login. Unsandboxed development
 * and Developer ID builds continue to use [LaunchAgentManager] through [DesktopStartAtLoginManager].
 */
class MacLoginItemManager internal constructor(
    private val daemonExecutablePath: String,
    private val sandboxed: Boolean = isAppSandboxed(),
    private val registration: MacLoginItemRegistration = NativeMacLoginItemRegistration(),
) {
    fun isAvailable(): Boolean =
        sandboxed && helperExecutable()?.isFile == true && registration.isAvailable()

    fun isAutostartEnabled(): Boolean =
        isAvailable() && statusIsEnabled(registration.status())

    fun isApprovalRequired(): Boolean =
        isAvailable() && registration.status() == STATUS_REQUIRES_APPROVAL

    fun openSystemSettingsLoginItems(): Boolean =
        if (!isApprovalRequired()) {
            false
        } else {
            runCatching {
                ProcessBuilder(
                    "/usr/bin/open",
                    "x-apple.systempreferences:com.apple.LoginItems-Settings.extension",
                ).start()
                true
            }.getOrDefault(false)
        }

    fun enable(): Boolean =
        isAvailable() &&
            registration.register() &&
            statusIsEnabled(registration.status())

    fun disable(): Boolean =
        isAvailable() &&
            registration.unregister() &&
            registration.status() in setOf(STATUS_NOT_REGISTERED, STATUS_UNKNOWN)

    internal fun helperExecutable(): File? {
        val contents = File(daemonExecutablePath).parentFile?.parentFile ?: return null
        return File(
            contents,
            "Library/LoginItems/TenshuControlLoginItem.app/Contents/MacOS/TenshuControlLoginItem",
        )
    }

    companion object {
        internal const val STATUS_ENABLED = "enabled"
        internal const val STATUS_REQUIRES_APPROVAL = "requiresApproval"
        internal const val STATUS_NOT_REGISTERED = "notRegistered"
        internal const val STATUS_UNKNOWN = "unknown"

        internal fun statusIsEnabled(status: String?): Boolean =
            status == STATUS_ENABLED

        private fun isAppSandboxed(): Boolean =
            System.getenv("APP_SANDBOX_CONTAINER_ID")?.isNotBlank() == true
    }
}

internal interface MacLoginItemRegistration {
    fun isAvailable(): Boolean
    fun status(): String?
    fun register(): Boolean
    fun unregister(): Boolean
}

private class NativeMacLoginItemRegistration : MacLoginItemRegistration {
    override fun isAvailable(): Boolean = MacLoginItemService.isAvailable()

    override fun status(): String? =
        MacLoginItemService.status(MAC_LOGIN_ITEM_IDENTIFIER)

    override fun register(): Boolean =
        MacLoginItemService.register(MAC_LOGIN_ITEM_IDENTIFIER)

    override fun unregister(): Boolean =
        MacLoginItemService.unregister(MAC_LOGIN_ITEM_IDENTIFIER)

    private companion object {
        const val MAC_LOGIN_ITEM_IDENTIFIER = "app.tenshu.control.loginitem"
    }
}
