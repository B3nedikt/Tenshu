package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-text audit over all three `PlatformProxyModule`s: **every platform hands the live scheduler to
 * the sync op factory**, or the head-less ops built on it are silent no-ops.
 *
 * ⚠⚠ `syncProxyImplementationFactory`'s `syncSchedulerHolder` defaults to null, and every op that reads
 * it does so as `syncSchedulerHolder?.scheduler?.…()`. Omitting it compiles, starts, passes every other
 * test and logs nothing — and `BackfillContentDeclarations`, `WriteSliceSnapshots`, `DrainSync` and
 * `SyncNow` simply do nothing. Found 2026-10-01: the content-declaration backfill had never run on a
 * desktop or an iPhone, only on Android, which was the one platform that passed it.
 *
 * Same shape as [MoveFenceWiringTest]: an absent argument at a call site, visible only in source text.
 */
class SyncSchedulerHolderWiringTest {

    @Test
    fun `every platform passes syncSchedulerHolder and fills it with the scheduler`() {
        val violations = MODULES.flatMap { (platform, path) ->
            val module = File(path)
            // ⚠ Anti-vacuity: a scan that finds no file passes for the wrong reason.
            assertTrue(module.isFile, "the $platform PlatformProxyModule moved; this test is no longer guarding.")
            val text = module.readText()
            buildList {
                val args = argumentsOf(text, "syncProxyImplementationFactory(")
                if (args == null) {
                    add("$platform: no syncProxyImplementationFactory invocation found — the scan is stale.")
                } else if ("syncSchedulerHolder =" !in withoutComments(args)) {
                    add(
                        "$platform: syncProxyImplementationFactory is invoked without `syncSchedulerHolder =`, so " +
                            "BackfillContentDeclarations, WriteSliceSnapshots, DrainSync and SyncNow are silent no-ops.",
                    )
                }
                if (".scheduler = components.scheduler" !in withoutComments(text)) {
                    add("$platform: the SyncSchedulerHolder is never filled with components.scheduler, so it stays null.")
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "The scheduler-backed sync ops are inert:\n" + violations.joinToString("\n"),
        )
    }

    private companion object {
        val MODULES = listOf(
            "Android" to "src/androidMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt",
            "desktop" to "src/desktopMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt",
            "iOS" to "src/iosMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt",
        )

        /** Comments stripped, so a note that names the argument cannot satisfy the scan. */
        fun withoutComments(text: String): String =
            text.lineSequence().joinToString("\n") { it.substringBefore("//") }

        /** Arguments of the first `call(` in [text], by paren balance. */
        fun argumentsOf(text: String, call: String): String? {
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
}
