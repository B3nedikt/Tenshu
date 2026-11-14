package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PR-6: the processing wake's identifier is permitted, and its background mode declared, in
 * `photos-ios/project.yml`. ⚠ An identifier the plist does not permit is refused by `register`, and
 * a request without the `processing` mode is refused by `submit`: either way no wake ever comes,
 * silently, which is why this is a test.
 */
class PhotosProcessingWakeTaskDeclaredTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }

    private fun yml(): String = File(repoRoot(), "photos-ios/project.yml").readText()

    /** The YAML list under [key]: its `- item` lines, up to the next key. */
    private fun listUnder(key: String): List<String> {
        val lines = yml().lines()
        val at = lines.indexOfFirst { it.trim() == "$key:" }
        assertTrue(at >= 0, "project.yml has no $key list")
        return lines.drop(at + 1)
            .takeWhile { it.trim().startsWith("- ") || it.trim().startsWith("#") || it.isBlank() }
            .filter { it.trim().startsWith("- ") }
            .map { it.trim().removePrefix("- ").trim().trim('"') }
    }

    @Test
    fun theIdentifierIsPermittedBesideIndexNowsWildcard() {
        val permitted = listUnder("BGTaskSchedulerPermittedIdentifiers")
        assertTrue(PhotosProcessingWakeTask.IDENTIFIER in permitted, "project.yml does not permit ${PhotosProcessingWakeTask.IDENTIFIER}: $permitted")
        assertTrue(PhotosIndexNowTask.PERMITTED in permitted, "PR-7's wildcard went missing: $permitted")
    }

    @Test
    fun theProcessingBackgroundModeIsDeclared() {
        assertEquals(listOf("processing"), listUnder("UIBackgroundModes"))
    }

    /** ⚠⚠ D5: never on battery. Swift submits exactly these values. */
    @Test
    fun theRequestAsksForExternalPowerAndNoNetwork() {
        assertTrue(PhotosProcessingWakeTask.REQUIRES_EXTERNAL_POWER)
        assertEquals(false, PhotosProcessingWakeTask.REQUIRES_NETWORK_CONNECTIVITY)
    }
}
