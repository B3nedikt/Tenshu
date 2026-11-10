package app.tenshu.control.lifecycle

import app.tenshu.control.lifecycle.WindowsStartupTask.State
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Start at login on Windows (desktop-windows-port.md §3.5): the agent's one-word answers, and the switch's behaviour
 * over them. The agent itself is faked; its WinRT half is a device check.
 */
class WindowsStartupTaskTest {

    private val agents = Files.createTempDirectory("launch-agents").toFile()

    @AfterTest
    fun cleanUp() {
        agents.deleteRecursively()
    }

    @Test
    fun parsesEveryAnswerTheAgentGives() {
        assertEquals(State.ENABLED, WindowsStartupTask.parse("enabled"))
        assertEquals(State.DISABLED, WindowsStartupTask.parse("disabled\r"))
        assertEquals(State.DISABLED_BY_USER, WindowsStartupTask.parse("disabledByUser"))
        assertEquals(State.DISABLED_BY_POLICY, WindowsStartupTask.parse("disabledByPolicy"))
        assertEquals(State.ENABLED_BY_POLICY, WindowsStartupTask.parse("enabledByPolicy"))
    }

    /** ⚠ A crashed or silent agent must read as "unavailable", never as on. */
    @Test
    fun anythingElseIsUnavailable() {
        assertEquals(State.UNAVAILABLE, WindowsStartupTask.parse(null))
        assertEquals(State.UNAVAILABLE, WindowsStartupTask.parse(""))
        assertEquals(State.UNAVAILABLE, WindowsStartupTask.parse("Unhandled exception. System.Exception"))
    }

    @Test
    fun passesTheVerbToTheAgent() {
        val calls = mutableListOf<List<String>>()
        val task = WindowsStartupTask(File("tray-agent.exe")) { _, args -> calls += args; "enabled" }
        task.state(); task.enable(); task.disable()
        assertEquals(
            listOf(
                listOf("--startup-task", "status"),
                listOf("--startup-task", "enable"),
                listOf("--startup-task", "disable"),
            ),
            calls,
        )
    }

    @Test
    fun theSwitchFollowsTheTask() {
        var state = "disabled"
        val manager = manager { _, args ->
            when (args[1]) {
                "enable" -> state = "enabled"
                "disable" -> state = "disabled"
            }
            state
        }
        assertFalse(manager.isAutostartEnabled())
        assertTrue(manager.enable())
        assertTrue(manager.isAutostartEnabled())
        assertTrue(manager.disable())
        assertFalse(manager.isAutostartEnabled())
        assertEquals(emptyList(), agents.list()?.toList().orEmpty(), "Windows must never write a LaunchAgent plist")
    }

    /** Turned off in Windows Settings: only that page can turn it back on, so the switch sends the user there. */
    @Test
    fun aTaskTheUserTurnedOffInSettingsNeedsApproval() {
        val manager = manager { _, _ -> "disabledByUser" }
        assertFalse(manager.isAutostartEnabled())
        assertTrue(manager.isApprovalRequired())
        assertFalse(manager.enable())
    }

    @Test
    fun policyCanHoldItOn() {
        val manager = manager { _, _ -> "enabledByPolicy" }
        assertTrue(manager.isAutostartEnabled())
        assertFalse(manager.isApprovalRequired())
    }

    private fun manager(run: (File, List<String>) -> String?) = DesktopStartAtLoginManager(
        daemonExecutablePath = "C:\\Program Files\\WindowsApps\\Tenshu.Control\\TenshuControl.exe",
        daemonAgent = LaunchAgentManager.forDaemon(agents),
        trayAgent = LaunchAgentManager.forTray(agents),
        supported = true,
        windowsTask = WindowsStartupTask(File("tray-agent.exe"), run),
    )
}
