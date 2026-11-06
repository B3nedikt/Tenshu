package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-text audit of P1a's seam (`media-processing-pipeline-sweep.md` §4.1): **desktop hands its
 * one-decode ladder renderer to the file service proxy.**
 *
 * ⚠⚠ Same failure shape as [MotionThumbnailRendererWiringTest] and [MintOnViewWiringTest], only
 * quieter: `fileServiceProxyImplementationFactory(thumbnailLadderRenderer = …)` defaults to `null`,
 * and `null` is a fully working fallback — every rung is still minted, each from its own full decode.
 * Nothing fails and nothing looks wrong; an upload just costs four decodes again. Only the call site
 * shows it.
 *
 * ⚠ Android and iOS are deliberately not asserted: their renderers are P1b and P1c. When one lands,
 * add its module here.
 */
class ThumbnailLadderRendererWiringTest {

    @Test
    fun `desktop passes the ladder renderer to the file service proxy`() {
        val module = File("src/desktopMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt")
        // ⚠ Anti-vacuity: a scan that finds no file passes for the wrong reason.
        assertTrue(module.isFile, "the desktop PlatformProxyModule moved; this test is no longer guarding.")

        val arguments = argumentsOf(module.readText(), "fileServiceProxyImplementationFactory(")
        assertTrue(arguments != null, "no fileServiceProxyImplementationFactory invocation found — the scan is stale.")
        assertTrue(
            "thumbnailLadderRenderer =" in arguments,
            "desktop invokes fileServiceProxyImplementationFactory without `thumbnailLadderRenderer =`, " +
                "so every upload decodes the original once per rung again.",
        )
    }

    /** The argument text of the first invocation of [call], balanced on parentheses. */
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
}
