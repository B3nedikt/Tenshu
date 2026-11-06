package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PR-7: the identifier Photos submits its continued *Index now* task under is the one its
 * `project.yml` permits. ⚠ An identifier the plist does not permit is refused by `register`, and
 * every press is then an ordinary foreground pass — silently, which is why this is a test.
 */
class PhotosIndexNowTaskDeclaredTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }

    @Test
    fun theWildcardIsPermittedInProjectYmlAndThePrefixIsItsBase() {
        val yml = File(repoRoot(), "photos-ios/project.yml").readText()
        assertTrue(Regex("""BGTaskSchedulerPermittedIdentifiers:\s*\n\s*- "?${Regex.escape(PhotosIndexNowTask.PERMITTED)}"?""").containsMatchIn(yml), "project.yml does not permit ${PhotosIndexNowTask.PERMITTED}")
        assertEquals(PhotosIndexNowTask.PERMITTED.removeSuffix("*"), PhotosIndexNowTask.PREFIX)
    }

    /** Apple: the base "must at least contain the bundle ID of the submitting application". */
    @Test
    fun theBaseBeginsWithPhotosBundleId() {
        val yml = File(repoRoot(), "photos-ios/project.yml").readText()
        val bundleId = Regex("""PRODUCT_BUNDLE_IDENTIFIER: (\S+)""").find(yml)!!.groupValues[1]
        assertEquals("app.tenshu.photos.ios", bundleId)
        assertTrue(PhotosIndexNowTask.PREFIX.startsWith("$bundleId."), "${PhotosIndexNowTask.PREFIX} does not begin with $bundleId")
    }
}
