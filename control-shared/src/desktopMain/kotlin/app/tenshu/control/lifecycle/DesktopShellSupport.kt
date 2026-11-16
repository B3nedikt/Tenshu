package app.tenshu.control.lifecycle

import java.awt.GraphicsEnvironment
import java.awt.SystemTray

/**
 * Which desktop-shell features this OS actually has an implementation for.
 *
 * ⚠⚠ Offering a feature where it is missing is worse than not offering it
 * (`docs/proposals/desktop-windows-port.md` §3.4):
 *  - **Tray.** macOS: the Swift `tray-agent`, a separate process. Windows: an in-process Compose
 *    `Tray` in the notification area ([inProcessTray]), so only where AWT reports a system tray.
 *    Linux: none. With close-to-tray on (the default), closing the window where there is no tray
 *    would leave a resident Control with no window, no tray icon and no taskbar entry.
 *  - **Start at login.** macOS: a LaunchAgent or `SMAppService`. Windows: the MSIX startup task,
 *    toggled through the packaged tray agent ([WindowsStartupTask]), so only inside the package.
 *    Linux: none. Without this gate the LaunchAgent path would write a plist nobody reads and the
 *    switch would report itself **on**.
 *
 * This is the one place that says what the shell can do, so the window, the settings screen, the
 * tray and the start-at-login manager cannot disagree.
 */
data class DesktopShellSupport(
    val trayAvailable: Boolean,
    val startAtLoginAvailable: Boolean,
    /**
     * The tray is drawn by this process (Windows) rather than by a separate agent (macOS). Implies
     * [trayAvailable]. ⚠ Never true on macOS: the Swift agent already owns the menu-bar icon, and a
     * second, AWT one would put two cats in the menu bar.
     */
    val inProcessTray: Boolean = false,
) {
    companion object {
        /** The support of the OS this process runs on. */
        val current: DesktopShellSupport by lazy {
            val osName = System.getProperty("os.name").orEmpty()
            // ⚠ Probed only on Windows: AWT is never touched for the macOS answer, which does not
            // depend on it.
            forOs(
                osName,
                systemTraySupported = isWindows(osName) && awtSystemTraySupported(),
                windowsStartupTask = isWindows(osName) && WindowsTrayAgentBinary.packaged() != null,
            )
        }

        /**
         * Pure, so the decision is testable for every OS from any host. [systemTraySupported] is
         * the host's AWT answer, which only the Windows tray depends on — a headless JVM or a
         * shell with no notification area degrades to "no tray", so close quits.
         * [windowsStartupTask]: this is the packaged Control, whose agent can toggle the startup task.
         */
        fun forOs(
            osName: String,
            systemTraySupported: Boolean = true,
            windowsStartupTask: Boolean = false,
        ): DesktopShellSupport {
            val mac = osName.startsWith("Mac", ignoreCase = true)
            val windowsTray = isWindows(osName) && systemTraySupported
            return DesktopShellSupport(
                trayAvailable = mac || windowsTray,
                startAtLoginAvailable = mac || (isWindows(osName) && windowsStartupTask),
                inProcessTray = windowsTray,
            )
        }

        private fun isWindows(osName: String) = osName.startsWith("Windows", ignoreCase = true)

        /** `SystemTray.isSupported()` throws nothing documented, but a headless JVM must not ask. */
        private fun awtSystemTraySupported(): Boolean = runCatching {
            !GraphicsEnvironment.isHeadless() && SystemTray.isSupported()
        }.getOrDefault(false)
    }
}
