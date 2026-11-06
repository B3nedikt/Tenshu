package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-text audit of X-4's one optional seam (`drive-xxlarge-thumbnail-rung.md` §4.6).
 *
 * ⚠⚠ `fileServiceProxyImplementationFactory(meteredNetwork = …)` defaults to
 * `MeteredNetworkSignal.Unknown`, which **never refuses** — so a platform that forgets it does not
 * fail, it silently downloads a 6–18 MB original over cellular on a one-second dwell. That is the
 * same failure shape as [ThumbnailBackfillWiringTest]'s three seams and
 * [MotionThumbnailRendererWiringTest]'s renderer: an absent argument at a call site, invisible
 * except in source text, with a default that looks like working software.
 *
 * ⚠ Deliberately not asserted here: *what* the argument computes. That it reads the platform's own
 * network state is the wiring's business; that the gate exists at all, and is consulted before the
 * download, is pinned by `EnsureThumbnailClassGatesTest` in `:file-service-proxy`.
 */
class MintOnViewWiringTest {

    @Test
    fun `android and desktop pass a metered signal to the file service proxy`() {
        val violations = MODULES.flatMap { (platform, path) ->
            val module = File(path)
            // ⚠ Anti-vacuity: a scan that finds no file passes for the wrong reason.
            assertTrue(module.isFile, "the $platform PlatformProxyModule moved; this test is no longer guarding.")
            val text = module.readText()
            buildList {
                val factoryArgs = argumentsOf(text, "fileServiceProxyImplementationFactory(")
                if (factoryArgs == null) {
                    add("$platform: no fileServiceProxyImplementationFactory invocation found — the scan is stale.")
                } else if ("meteredNetwork =" !in factoryArgs) {
                    add(
                        "$platform: fileServiceProxyImplementationFactory is invoked without " +
                            "`meteredNetwork =`, so a view mint downloads originals on a metered network.",
                    )
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Mint-on-view's metered gate is wired open:\n" + violations.joinToString("\n"),
        )
    }

    /**
     * ⚠ iOS is not the only unwired caller: the JS SDK (`FirstPartyPlugins`) and the system-test
     * harness call the same factory without the signal, so both fail open too. Neither is a surface
     * that dwells on a photograph, which is why only iOS is asserted below.
     *
     * ⚠⚠ **iOS wires none, and that is recorded rather than fixed here.** The signal would have to
     * come from a `NetworkStateProvider` iOS does not have in this module, and the default fails
     * open — so on iOS a dwell may spend cellular data. This pins the gap so the next reader meets
     * it as a known limitation; when iOS gains a provider, this test fails and §9.6 is what to
     * update.
     */
    @Test
    fun `ios wires no metered signal, so a view mint there is not gated`() {
        val module = File("src/iosMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt")
        assertTrue(module.isFile, "the iOS PlatformProxyModule moved; this test is no longer guarding.")

        assertTrue(
            "meteredNetwork =" !in module.readText(),
            "iOS now passes a metered signal to the file service proxy — " +
                "`drive-xxlarge-thumbnail-rung.md` §9.6's 'iOS is not metered-gated' is out of date.",
        )
    }

    private fun argumentsOf(text: String, call: String): String? {
        val start = Regex("(^|[^A-Za-z0-9_])" + Regex.escape(call)).find(text)?.range?.last ?: return null
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

    private companion object {
        val MODULES = listOf(
            "android" to "src/androidMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt",
            "desktop" to "src/desktopMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt",
        )
    }
}
