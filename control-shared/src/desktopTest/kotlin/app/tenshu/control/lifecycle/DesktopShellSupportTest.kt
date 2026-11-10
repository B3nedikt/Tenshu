package app.tenshu.control.lifecycle

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The two Windows defects of `docs/proposals/desktop-windows-port.md` §3.4: a close-to-tray with no
 * tray stranded Control, and a start-at-login switch with no mechanism reported itself on.
 */
class DesktopShellSupportTest {

    private val agents = Files.createTempDirectory("launch-agents").toFile()

    @AfterTest
    fun cleanUp() {
        agents.deleteRecursively()
    }

    @Test
    fun macOsHasTheAgentTrayAndTheOnlyStartAtLoginMechanism() {
        assertEquals(
            DesktopShellSupport(trayAvailable = true, startAtLoginAvailable = true, inProcessTray = false),
            DesktopShellSupport.forOs("Mac OS X"),
        )
        // The Swift agent does not depend on AWT's answer.
        assertEquals(
            DesktopShellSupport(trayAvailable = true, startAtLoginAvailable = true, inProcessTray = false),
            DesktopShellSupport.forOs("Mac OS X", systemTraySupported = false),
        )
    }

    /** ⚠⚠ Never `inProcessTray` on macOS, or a second icon joins the Swift agent's. */
    @Test
    fun windowsDrawsItsOwnTrayAndStartsAtLoginOnlyInsideThePackage() {
        // A dev run: no packaged agent, so no startup task to toggle.
        assertEquals(
            DesktopShellSupport(trayAvailable = true, startAtLoginAvailable = false, inProcessTray = true),
            DesktopShellSupport.forOs("Windows 11", systemTraySupported = true),
        )
        assertEquals(
            DesktopShellSupport(trayAvailable = true, startAtLoginAvailable = true, inProcessTray = true),
            DesktopShellSupport.forOs("Windows 11", systemTraySupported = true, windowsStartupTask = true),
        )
    }

    /** The startup task is a Windows mechanism; claiming it elsewhere must change nothing. */
    @Test
    fun theStartupTaskFlagIsIgnoredOffWindows() {
        assertEquals(DesktopShellSupport(false, false, false), DesktopShellSupport.forOs("Linux", windowsStartupTask = true))
    }

    /** A headless JVM or a shell without a notification area: no tray, so close must quit. */
    @Test
    fun windowsWithoutASystemTrayHasNoTray() {
        assertEquals(DesktopShellSupport(false, false, false), DesktopShellSupport.forOs("Windows 11", systemTraySupported = false))
    }

    @Test
    fun linuxAndUnknownOsesHaveNeither() {
        assertEquals(DesktopShellSupport(false, false, false), DesktopShellSupport.forOs("Linux"))
        assertEquals(DesktopShellSupport(false, false, false), DesktopShellSupport.forOs(""))
    }

    /** ⚠⚠ The lie itself: before, `enable()` wrote a plist and `isAutostartEnabled()` then said yes. */
    @Test
    fun anUnsupportedOsNeitherWritesALaunchAgentNorReportsOne() {
        val manager = DesktopStartAtLoginManager(
            daemonExecutablePath = "C:\\Program Files\\Tenshu\\TenshuControl.exe",
            daemonAgent = LaunchAgentManager.forDaemon(agents),
            trayAgent = LaunchAgentManager.forTray(agents),
            supported = false,
        )

        assertFalse(manager.enable())
        assertFalse(manager.isAutostartEnabled())
        assertFalse(manager.isApprovalRequired())
        assertEquals(emptyList(), agents.list()?.toList().orEmpty(), "no plist may be written")
    }
}
