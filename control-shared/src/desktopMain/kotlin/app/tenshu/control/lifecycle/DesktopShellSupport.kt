package app.tenshu.control.lifecycle

/**
 * Which desktop-shell features this OS actually has an implementation for.
 *
 * ⚠⚠ Both are macOS-only today, and offering them elsewhere is worse than not offering them
 * (`docs/proposals/desktop-windows-port.md` §3.4):
 *  - **Tray.** The tray is the Swift `tray-agent`; nothing draws one on Windows or Linux. With
 *    close-to-tray on (the default), closing the window there left a resident Control with no
 *    window, no tray icon and no taskbar entry.
 *  - **Start at login.** The only mechanisms are a LaunchAgent and `SMAppService`. Elsewhere the
 *    LaunchAgent path wrote a plist nobody reads and the switch then reported itself **on**.
 *
 * When a Windows or Linux implementation lands, it flips its own flag here — this is the one place
 * that says what the shell can do, so the window, the settings screen and the start-at-login
 * manager cannot disagree.
 */
data class DesktopShellSupport(
    val trayAvailable: Boolean,
    val startAtLoginAvailable: Boolean,
) {
    companion object {
        /** The support of the OS this process runs on. */
        val current: DesktopShellSupport by lazy { forOs(System.getProperty("os.name").orEmpty()) }

        /** Pure, so the decision is testable for every OS from any host. */
        fun forOs(osName: String): DesktopShellSupport {
            val mac = osName.startsWith("Mac", ignoreCase = true)
            return DesktopShellSupport(trayAvailable = mac, startAtLoginAvailable = mac)
        }
    }
}
