package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Source-text audit of two iOS lines no runtime test reaches
 * (`ios-on-device-inference.md` §13.5).
 *
 * ⚠⚠ Both fail silently.
 * - The custody's `cancelPass` is a lambda. Left empty, a realm mismatch clears the stores under a
 *   pass that is still running, and the pass then writes the old realm's vectors back. A pass in
 *   an assembled iOS graph has no backend yet and ends at once, so no test can hold one in flight.
 * - The shared media-index schedule is registered by a one-line call in the platform init. Put
 *   back on iOS, every route-1 process dispatches the row again; taken out of Android or desktop,
 *   nothing indexes on a schedule on that platform.
 */
class OwnedInferenceStoresWiringTest {

    private val owned = File("src/iosMain/kotlin/app/tenshu/control/di/OwnedInferenceStores.kt")
    private val platform = File("src/iosMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt")

    @Test
    fun `the custody's cancel is the runner's`() {
        // ⚠ Anti-vacuity: if the path moves, go red rather than quietly stop guarding.
        assertTrue(owned.isFile, "the scanned file moved; this test guards nothing")
        assertTrue(
            "cancelPass = { get<MediaIndexRunner>().cancelRunningPass() }" in owned.readText(),
            "the owning graph's InferenceStoreCustody no longer stops the running pass before it clears.",
        )
    }

    private fun code(file: File): String {
        assertTrue(file.isFile, "the scanned file moved; this test guards nothing: $file")
        return file.readLines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }

    @Test
    fun `iOS registers no media-index schedule`() {
        assertFalse(
            "registerMediaIndexSchedule" in code(platform),
            "the iOS platform module registers the shared media-index row again; the owning app drives its passes.",
        )
    }

    @Test
    fun `Android and desktop still register the media-index schedule`() {
        listOf("androidMain", "desktopMain").forEach { sourceSet ->
            val module = code(File("src/$sourceSet/kotlin/app/tenshu/control/di/PlatformProxyModule.kt"))
            assertTrue(
                "sessionScope.launch { registerMediaIndexSchedule() }" in module,
                "$sourceSet no longer registers the media-index schedule: nothing indexes on a schedule there.",
            )
        }
    }
}
