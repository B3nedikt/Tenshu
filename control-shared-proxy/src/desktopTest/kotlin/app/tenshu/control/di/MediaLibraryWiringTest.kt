package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-text audit: **the read filter and the reconcile's source are wired on every platform**
 * (P0f-b PR-1, `media-processing-pipeline-sweep.md` §6.4.6).
 *
 * ⚠⚠ Both seams fail SILENTLY when absent. `FaceGroupingComponents.livePaths` defaults to `null`,
 * which clusters every stored row — a deleted photograph keeps counting in People, and nothing
 * errors. `MediaSource.walk` defaults to a partial walk, which reconciles nothing — no pass ever
 * erases a deleted photograph's template, and every pass still reports success. Both were exactly
 * that on master until this PR.
 *
 * What is pinned:
 *  - `ProxyModule` (common, so all three platforms) binds ONE `LivePaths` to [fileLivePaths], hands
 *    it to `FaceGroupingComponents`, and builds the `MediaSource` with [controlMediaSource] over
 *    the same predicate;
 *  - every `PlatformProxyModule` passes the common singletons (`faceGrouping = get()`,
 *    `mediaSource = get()`) to the inference factory and never builds its own or overrides
 *    `livePaths` — a platform-local copy is how one platform silently loses the filter.
 *
 * ⭐ `MediaLibrarySeamsTest` proves what the bound seams DO; this proves they are the ones bound.
 */
class MediaLibraryWiringTest {

    @Test
    fun `the common module binds the live-path predicate and the vouching source`() {
        val text = file("src/commonMain/kotlin/app/tenshu/control/di/ProxyModule.kt").readText()

        val faceGrouping = calls(text, "FaceGroupingComponents(")
        assertTrue(faceGrouping.size == 1, "expected exactly one FaceGroupingComponents(...) in ProxyModule, found $faceGrouping")
        assertTrue(
            Regex("""livePaths\s*=\s*get\(\)""").containsMatchIn(faceGrouping.single()),
            "FaceGroupingComponents is built without `livePaths = get()`: People keeps counting deleted photographs",
        )
        assertTrue(
            Regex("""single<LivePaths>\s*\{\s*fileLivePaths\s*}""").containsMatchIn(text),
            "ProxyModule must bind `single<LivePaths> { fileLivePaths }`",
        )

        val sourceBinding = Regex("""single<MediaSource>\s*\{(.*?)\n    }""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)
        assertTrue(sourceBinding != null, "no `single<MediaSource>` binding found — the scan is stale")
        assertTrue(
            "controlMediaSource(" in sourceBinding && Regex("""livePaths\s*=\s*get\(\)""").containsMatchIn(sourceBinding),
            "the MediaSource is not `controlMediaSource(..., livePaths = get())`: B1 never runs in production",
        )
        assertTrue(
            "MediaSource {" !in text && "collectImagePaths" !in text,
            "a hand-rolled MediaSource lambda is back in ProxyModule; it cannot vouch for completeness",
        )
    }

    @Test
    fun `every platform module hands the common seams to the inference factory`() {
        val modules = EXPECTED_PLATFORMS.mapNotNull { platform ->
            val f = file("src/${platform}Main/kotlin/app/tenshu/control/di/PlatformProxyModule.kt")
            if (f.isFile) platform to f.readText() else null
        }.toMap()

        // ⚠ Anti-vacuity: a scan that finds no files passes for the wrong reason.
        assertTrue(
            modules.size == EXPECTED_PLATFORMS.size,
            "expected a PlatformProxyModule for each of $EXPECTED_PLATFORMS but found ${modules.keys}; " +
                "the scan paths are stale and this test is no longer guarding.",
        )

        val violations = modules.flatMap { (platform, text) ->
            val call = calls(text, "inferenceServiceProxyImplementationFactory(").singleOrNull()
            buildList {
                if (call == null) {
                    add("$platform: no inferenceServiceProxyImplementationFactory(...) call found — the scan is stale")
                } else {
                    if (!Regex("""faceGrouping\s*=\s*get\(\)""").containsMatchIn(call)) {
                        add("$platform: does not pass `faceGrouping = get()` — People loses the read filter")
                    }
                    if (!Regex("""mediaSource\s*=\s*get\(\)""").containsMatchIn(call)) {
                        add("$platform: does not pass `mediaSource = get()` — the pass loses B1")
                    }
                    if (Regex("""livePaths\s*=""").containsMatchIn(call)) {
                        add("$platform: overrides `livePaths` — search and People would answer different realms")
                    }
                }
                if ("FaceGroupingComponents(" in text) add("$platform: builds its own FaceGroupingComponents")
                if (Regex("""MediaSource\s*\{""").containsMatchIn(text)) add("$platform: builds its own MediaSource")
            }
        }

        assertTrue(violations.isEmpty(), "Media read filter / reconcile wired to nothing:\n" + violations.joinToString("\n"))
    }

    /** Every `name(…)` call in [text], each up to its closing `)` at the call's own depth. */
    private fun calls(text: String, name: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = text.indexOf(name, from).takeIf { it >= 0 } ?: return out
            var depth = 0
            var end = -1
            for (i in start until text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) { end = i; break }
                }
            }
            if (end < 0) return out
            out += text.substring(start, end + 1)
            from = end + 1
        }
    }

    private fun file(path: String) = File(path)

    private companion object {
        val EXPECTED_PLATFORMS = listOf("android", "desktop", "ios")
    }
}
