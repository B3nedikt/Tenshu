package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Photos' two on-device build flags, as `photos-ios/project.yml` declares them: each written into
 * the Info.plist key `PhotosLocalRuntime` reads, and each **off** (`ios-on-device-inference.md`
 * §14.18.16, `ios-face-grouping.md` FD10). ⚠⚠ Plist keys live in `project.yml`; `Info.plist` is
 * generated. A key that is not written there reads as absent, which is off, silently.
 */
class PhotosBuildFlagsDeclaredTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }

    private val yml: String by lazy { File(repoRoot(), "photos-ios/project.yml").readText() }

    private fun assertDeclaredOff(plistKey: String, setting: String) {
        assertTrue(Regex("""\n\s+${Regex.escape(plistKey)}: \$\(${Regex.escape(setting)}\)\n""").containsMatchIn(yml), "$plistKey is not fed from $setting")
        val values: List<String> = Regex("""\n\s+${Regex.escape(setting)}(\[[^\]]*\])?: (.+)\n""").findAll(yml).map { it.groupValues[2].trim() }.toList()
        assertEquals(listOf("\"NO\""), values, "$setting is not exactly one NO")
    }

    @Test
    fun onDeviceAiIsDeclaredAndOff() = assertDeclaredOff("TenshuPhotosOnDeviceAi", "TENSHU_PHOTOS_ON_DEVICE_AI")

    @Test
    fun facesAreDeclaredAndOff() = assertDeclaredOff("TenshuPhotosFaces", "TENSHU_PHOTOS_FACES")
}
