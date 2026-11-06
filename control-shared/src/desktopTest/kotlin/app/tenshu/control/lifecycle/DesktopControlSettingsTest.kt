package app.tenshu.control.lifecycle

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopControlSettingsTest {

    private val dir = Files.createTempDirectory("tenshu-settings").toFile()
    private val file = File(dir, "control.settings.properties")

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun defaultsToCloseToTrayTrue() {
        assertTrue(DesktopControlSettings(file).closeToTray())
    }

    @Test
    fun persistsAcrossInstances() {
        DesktopControlSettings(file).setCloseToTray(false)
        assertFalse(DesktopControlSettings(file).closeToTray(), "should read the persisted value")

        DesktopControlSettings(file).setCloseToTray(true)
        assertTrue(DesktopControlSettings(file).closeToTray())
    }

    @Test
    fun corruptFileFallsBackToDefault() {
        file.writeText("=== definitely not valid properties ===")
        // Doesn't throw; missing/unparseable key falls back to default true.
        assertTrue(DesktopControlSettings(file).closeToTray())
    }

    @Test
    fun startAtLoginDefaultApplied_latchesOnceSet() {
        // Defaults false so the first-run "default Start-at-login ON" step runs once.
        assertFalse(DesktopControlSettings(file).startAtLoginDefaultApplied())

        DesktopControlSettings(file).setStartAtLoginDefaultApplied(true)
        assertTrue(
            DesktopControlSettings(file).startAtLoginDefaultApplied(),
            "latch must persist so a later opt-out isn't silently re-enabled",
        )
    }
}
