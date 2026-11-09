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
    fun onlyMacOsHasATrayAndAStartAtLoginMechanism() {
        assertEquals(DesktopShellSupport(true, true), DesktopShellSupport.forOs("Mac OS X"))
        assertEquals(DesktopShellSupport(false, false), DesktopShellSupport.forOs("Windows 11"))
        assertEquals(DesktopShellSupport(false, false), DesktopShellSupport.forOs("Linux"))
        assertEquals(DesktopShellSupport(false, false), DesktopShellSupport.forOs(""))
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
