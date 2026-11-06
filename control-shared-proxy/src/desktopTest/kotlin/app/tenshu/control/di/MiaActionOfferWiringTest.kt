package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-text audit over all three `PlatformProxyModule`s: **which platforms' Mia offers an
 * action** (`mia-query-layer.md` §19).
 *
 * ⚠⚠ The iOS module is every route-1 app's runtime, and a routine never runs there
 * (`routines-engine.md` RE-D13). `MiaActionOffer.OFFERED` in it compiles, links and passes every
 * other test — and puts a "Run …" button in front of an iPhone user whose tap is guaranteed to
 * fail. Nothing in this module's own tests can see it: the root build skips the iOS test compile
 * for `-proxy` modules, so the decision is visible only in source text.
 *
 * Same shape as [RoutineExecutionWiringTest].
 */
class MiaActionOfferWiringTest {

    @Test
    fun `Mia offers no action on iOS, and offers them on Android and desktop`() {
        val violations = MODULES.mapNotNull { (platform, path, expected) ->
            val module = File(path)
            // ⚠ Anti-vacuity: a scan that finds no file passes for the wrong reason.
            assertTrue(module.isFile, "the $platform PlatformProxyModule moved; this test is no longer guarding.")
            val text = withoutComments(module.readText())
            val args = argumentsOf(text, "miaServiceProxyImplementationFactory(")
            when {
                args == null ->
                    "$platform: no miaServiceProxyImplementationFactory invocation found — Mia cannot answer here."
                "MiaActionOffer.$expected," !in args ->
                    "$platform: miaServiceProxyImplementationFactory must pass `actionOffer = MiaActionOffer.$expected`."
                else -> null
            }
        }

        assertTrue(violations.isEmpty(), "Mia's action offer is wired wrongly:\n" + violations.joinToString("\n"))
    }

    /**
     * ⚠ The same audit for which app Mia's photo refusals name (`ios-on-device-inference.md` §1.8):
     * on iOS Photos alone owns inference, and "this device" sends the user nowhere.
     */
    @Test
    fun `Mia's photo refusals name Photos on iOS, and this device on Android and desktop`() {
        val expected = mapOf("Android" to "THIS_DEVICE", "desktop" to "THIS_DEVICE", "iOS" to "PHOTOS_APP", "system-test" to "THIS_DEVICE")
        val violations = MODULES.mapNotNull { (platform, path, _) ->
            val module = File(path)
            assertTrue(module.isFile, "the $platform PlatformProxyModule moved; this test is no longer guarding.")
            val args = argumentsOf(withoutComments(module.readText()), "miaServiceProxyImplementationFactory(")
                ?: return@mapNotNull "$platform: no miaServiceProxyImplementationFactory invocation found."
            val home = expected.getValue(platform)
            if ("MiaPhotoSearchHome.$home," !in args) "$platform: must pass `photoSearch = MiaPhotoSearchHome.$home`." else null
        }
        assertTrue(violations.isEmpty(), "Mia's photo wording is wired wrongly:\n" + violations.joinToString("\n"))
    }

    private companion object {
        val MODULES = listOf(
            Triple("Android", "src/androidMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt", "OFFERED"),
            Triple("desktop", "src/desktopMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt", "OFFERED"),
            Triple("iOS", "src/iosMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt", "WITHHELD"),
            // ⚠ The JVM system-test proxy calls the factory too: a required parameter it does not
            // pass breaks `:system-test-utils`, `:hub:test` and the schema baselines.
            Triple("system-test", "../system-test-utils/src/main/kotlin/org/tenshu/systemtest/di/SystemTestPlatformModule.kt", "OFFERED"),
        )

        /** Comments stripped, so a note that names the argument cannot satisfy the scan. */
        fun withoutComments(text: String): String =
            text.lineSequence().joinToString("\n") { it.substringBefore("//") }

        /** Arguments of the first `call(` in [text], by paren balance. */
        fun argumentsOf(text: String, call: String): String? {
            val start = Regex("(^|[^A-Za-z0-9_.])" + Regex.escape(call)).find(text)?.range?.last ?: return null
            var depth = 1
            var i = start + 1
            while (i < text.length && depth > 0) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            return text.substring(start + 1, (i - 1).coerceAtLeast(start + 1))
        }
    }
}
