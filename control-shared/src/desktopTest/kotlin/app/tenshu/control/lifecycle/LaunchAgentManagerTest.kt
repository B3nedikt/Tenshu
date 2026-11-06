package app.tenshu.control.lifecycle

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaunchAgentManagerTest {

    private val dir = Files.createTempDirectory("tenshu-launchagents").toFile()
    private val manager = LaunchAgentManager(label = "app.tenshu.control.test", launchAgentsDir = dir)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun plistContentCarriesLabelExecutableAndHiddenStart() {
        val plist = manager.plistContent("/Applications/TenshuControl.app/Contents/MacOS/TenshuControl")
        assertTrue(plist.contains("<string>app.tenshu.control.test</string>"))
        assertTrue(plist.contains("/Applications/TenshuControl.app/Contents/MacOS/TenshuControl"))
        assertTrue(plist.contains("<key>RunAtLoad</key>"))
        assertTrue(plist.contains("TENSHU_CONTROL_START_HIDDEN"))
    }

    @Test
    fun plistContentEscapesXml() {
        val plist = manager.plistContent("/path/with <&> chars")
        assertTrue(plist.contains("/path/with &lt;&amp;&gt; chars"))
    }

    @Test
    fun writePlistThenRemoveTogglesInstalledState() {
        assertFalse(manager.isInstalled())

        val file = manager.writePlist("/Applications/TenshuControl.app/Contents/MacOS/TenshuControl")
        assertTrue(file.exists())
        assertTrue(manager.isInstalled())
        assertEquals("app.tenshu.control.test.plist", file.name)

        assertTrue(manager.removePlist())
        assertFalse(manager.isInstalled())
        assertFalse(manager.removePlist(), "removing an absent plist is a no-op")
    }

    @Test
    fun daemonAgentIsHiddenBootAndQuitable() {
        val plist = LaunchAgentManager.forDaemon(dir)
            .plistContent("/Applications/TenshuControl.app/Contents/MacOS/TenshuControl")
        assertTrue(plist.contains("<string>app.tenshu.control</string>"), "daemon label")
        assertTrue(plist.contains("TENSHU_CONTROL_START_HIDDEN"), "daemon boots hidden")
        // KeepAlive=false → an explicit Quit stays quit until next login.
        assertTrue(plist.contains("<key>KeepAlive</key>\n    <false/>"))
    }

    @Test
    fun trayAgentIsAlwaysOnWithNoDaemonEnv() {
        val plist = LaunchAgentManager.forTray(dir)
            .plistContent("/Applications/TenshuControl.app/Contents/Helpers/tray-agent")
        assertTrue(plist.contains("<string>app.tenshu.control.tray</string>"), "tray label")
        assertTrue(plist.contains("/Applications/TenshuControl.app/Contents/Helpers/tray-agent"))
        // KeepAlive=true → the lightweight menu-bar presence is always up.
        assertTrue(plist.contains("<key>KeepAlive</key>\n    <true/>"))
        // No daemon-only env on the tray → the EnvironmentVariables key is omitted.
        assertFalse(plist.contains("EnvironmentVariables"), "tray has no env dict")
        assertFalse(plist.contains("TENSHU_CONTROL_START_HIDDEN"))
    }

    @Test
    fun daemonAndTrayInstallToDistinctPlists() {
        val daemon = LaunchAgentManager.forDaemon(dir)
        val tray = LaunchAgentManager.forTray(dir)
        assertEquals("app.tenshu.control.plist", daemon.plistFile().name)
        assertEquals("app.tenshu.control.tray.plist", tray.plistFile().name)
    }

    @Test
    fun trayBinaryForResolvesEmbeddedHelperWhenPresent() {
        // Mirror the packaged layout: <app>/Contents/{MacOS/TenshuControl, Helpers/tray-agent}.
        val contents = Files.createDirectories(dir.toPath().resolve("TenshuControl.app/Contents")).toFile()
        val daemonExec = contents.resolve("MacOS/TenshuControl").apply { parentFile.mkdirs(); writeText("launcher") }
        val helper = contents.resolve("Helpers/tray-agent").apply { parentFile.mkdirs(); writeText("tray") }

        assertEquals(helper, LaunchAgentManager.trayBinaryFor(daemonExec.absolutePath))
    }

    @Test
    fun trayBinaryForIsNullWithoutEmbeddedHelper() {
        // Dev run: the JVM launcher has no sibling Contents/Helpers/tray-agent.
        assertNull(LaunchAgentManager.trayBinaryFor("/usr/bin/java"))
    }

    // ── launchd's disabled database ──────────────────────────────────────────
    // Verbatim `launchctl print-disabled gui/<uid>` output (macOS 26.5.2): two
    // leading tabs per entry, one label per line.

    private fun printDisabled(vararg entries: String) =
        "\ndisabled services = {\n" + entries.joinToString("\n") { "\t\t$it" } + "\n}\n"

    @Test
    fun disabledStateIsReadPerLabel() {
        val out = printDisabled(
            "\"app.tenshu.control\" => disabled",
            "\"com.apple.ManagedClientAgent.enrollagent\" => disabled",
        )
        assertEquals(true, LaunchAgentManager.parseDisabledState(out, "app.tenshu.control"))

        val enabled = printDisabled("\"app.tenshu.control\" => enabled")
        assertEquals(false, LaunchAgentManager.parseDisabledState(enabled, "app.tenshu.control"))
    }

    @Test
    fun disabledStateDoesNotConfuseTheDaemonWithTheTrayLabel() {
        // The real pairing: `uninstall()` disables BOTH labels, so the tray record
        // is present far more often than not. `app.tenshu.control` is a prefix of
        // `app.tenshu.control.tray`, so a `contains(label)` implementation reports
        // the daemon as disabled here — the whole point of matching the quoted label.
        val onlyTrayDisabled = printDisabled("\"app.tenshu.control.tray\" => disabled")

        assertNull(
            LaunchAgentManager.parseDisabledState(onlyTrayDisabled, "app.tenshu.control"),
            "the tray's record must not be read as the daemon's",
        )
        assertEquals(
            true,
            LaunchAgentManager.parseDisabledState(onlyTrayDisabled, "app.tenshu.control.tray"),
        )
    }

    @Test
    fun disabledStateIsNullWhenLaunchdHasNoRecord() {
        // launchd's default for an unknown label is *enabled*, and "no record" has
        // to stay distinguishable from "explicitly enabled" for the caller.
        assertNull(
            LaunchAgentManager.parseDisabledState(printDisabled("\"com.other.thing\" => disabled"), "app.tenshu.control"),
        )
        assertNull(LaunchAgentManager.parseDisabledState("", "app.tenshu.control"))
    }

    @Test
    fun disabledStateAcceptsTheLegacyTrueFalseSpelling() {
        // Older macOS prints the same two states as `=> true` / `=> false`.
        assertEquals(
            true,
            LaunchAgentManager.parseDisabledState(printDisabled("\"app.tenshu.control\" => true"), "app.tenshu.control"),
        )
        assertEquals(
            false,
            LaunchAgentManager.parseDisabledState(printDisabled("\"app.tenshu.control\" => false"), "app.tenshu.control"),
        )
    }

    @Test
    fun autostartIsNotEnabledWithoutAPlist() {
        // The bug this guards: `isInstalled()` (file exists) was standing in for
        // "autostart works". Without a plist there is nothing to enable, and the
        // check short-circuits before it ever shells out to launchctl.
        assertFalse(manager.isInstalled())
        assertFalse(manager.isAutostartEnabled())
    }
}
