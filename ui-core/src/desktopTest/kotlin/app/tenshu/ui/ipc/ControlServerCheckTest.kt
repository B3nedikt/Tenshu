package app.tenshu.ui.ipc

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The client side of the IPC gate (`ControlServerCheck`). The cryptography is tested in
 * `:macos-helpers` (`MacPeerAttestationTest`, including the client direction) and the transport's
 * handling in `:core-common` (`UnixSocketServerVerifierTest`); this pins what only this file decides.
 */
class ControlServerCheckTest {

    /**
     * ⚠ A wrong identifier here refuses every genuine Control: every desktop app would show
     * "Control not running" with Control running. Read from Control's own build file, not copied.
     */
    @Test
    fun theExpectedIdentifierIsControlsBundleId() {
        val gradle = File("../control-desktop/build.gradle.kts").readText()
        val bundleId = Regex("""bundleID = "([^"]+)"""").find(gradle)?.groupValues?.get(1)
        assertEquals(bundleId, ControlServerCheck.CONTROL_IDENTIFIER)
    }

    @Test
    fun onlyControlIsVouchedFor() {
        val policy = ControlServerCheck.policy(setOf("752LWAZMKZ"))
        assertEquals(setOf("752LWAZMKZ"), policy.acceptedTeamsFor(ControlServerCheck.CONTROL_IDENTIFIER))
        // Any other first-party binary, including Control's own tray agent, must not serve ipc.sock.
        for (other in listOf("app.tenshu.drive.ios", "tray-agent", "app.tenshu.control", "app.tenshu.control.loginitem")) {
            assertTrue(policy.acceptedTeamsFor(other).isEmpty(), other)
        }
    }

    @Test
    fun developerModeIsTheDevRunFlagOnly_neverInAPackagedApp() {
        val before = System.getProperty("tenshu.release")
        val appPath = System.getProperty(ControlServerCheck.JPACKAGE_APP_PATH)
        try {
            System.clearProperty(ControlServerCheck.JPACKAGE_APP_PATH)
            System.clearProperty("tenshu.release")
            assertEquals(false, ControlServerCheck.developerMode(), "no flag: enforcing")
            System.setProperty("tenshu.release", "true")
            assertEquals(false, ControlServerCheck.developerMode())
            System.setProperty("tenshu.release", "false")
            assertEquals(true, ControlServerCheck.developerMode(), "the dev run")
            // ⚠⚠ JAVA_TOOL_OPTIONS can inject the flag into a packaged app; the launcher's own property wins.
            System.setProperty(ControlServerCheck.JPACKAGE_APP_PATH, "/Applications/TenshuDrive.app/Contents/MacOS/TenshuDrive")
            assertEquals(false, ControlServerCheck.developerMode(), "a packaged app enforces whatever the flag says")
        } finally {
            if (before == null) System.clearProperty("tenshu.release") else System.setProperty("tenshu.release", before)
            if (appPath == null) System.clearProperty(ControlServerCheck.JPACKAGE_APP_PATH) else System.setProperty(ControlServerCheck.JPACKAGE_APP_PATH, appPath)
        }
    }

    /**
     * ⚠⚠ The check needs `--add-opens` for `sun.nio.ch` and `java.io` (`PeerAuditToken`). Without them
     * every attestation is Unavailable and a PACKAGED app refuses the genuine Control, while a dev
     * run (developer mode) and every unit test (whose task has the opens) stay green.
     */
    @Test
    fun everyDesktopAppPassesThePeerGateJvmArgsAtBothSites() {
        val root = File("..")
        val apps = root.listFiles { f -> f.isDirectory && f.name.endsWith("-desktop") && File(f, "build.gradle.kts").exists() }!!
        assertTrue(apps.size >= 10, "found ${apps.map { it.name }}")
        for (app in apps) {
            val gradle = File(app, "build.gradle.kts").readText()
            assertTrue("java.base/sun.nio.ch=ALL-UNNAMED" in gradle && "java.base/java.io=ALL-UNNAMED" in gradle, "${app.name}: the two --add-opens")
            // One list feeds both JVMs: Compose's run task takes `application.jvmArgs` and REPLACES
            // whatever the build script set on it at configuration time.
            assertTrue("jvmArgs += PEER_GATE_JVM_ARGS" in gradle, "${app.name}: on the packaged app and the dev run")
            // ⚠⚠ So the dev-only flag must go in at execution time, never on that shared list.
            assertTrue(
                Regex("""name == "run"[\s\S]*?add\("-Dtenshu\.release=false"\)[\s\S]*?doFirst \{ \(this as JavaExec\)\.jvmArgs\(devArgs\) \}""")
                    .containsMatchIn(gradle),
                "${app.name}: tenshu.release=false on the dev run, via doFirst",
            )
            assertTrue(!Regex("""jvmArgs\s*\+=\s*"-Dtenshu\.release"""").containsMatchIn(gradle), "${app.name}: tenshu.release in a packaged app")
            // ⚠⚠ And a bundled runtime with final FFM (22+): on the daemon's 21 every attestation is a
            // NoSuchMethodError, found only by running a packaged app (2026-11-06).
            val jdk = Regex("""val FFM_JDK = (\d+)""").find(gradle)?.groupValues?.get(1)?.toInt()
            assertTrue(jdk != null && jdk >= 22, "${app.name}: FFM_JDK >= 22, got $jdk")
            assertTrue("javaHome = it" in gradle, "${app.name}: jpackage must bundle the FFM_JDK runtime (javaHome)")
        }
    }
}
