package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-text audit over all three `PlatformProxyModule`s: **which platforms run routines**
 * (`routines-engine.md` RE-D13).
 *
 * ⚠⚠ The iOS module is every route-1 app's runtime — several processes, one device id, one store,
 * and no platform wake. `RoutineExecution.EXECUTES` there compiles, links and passes every other
 * test, and would give each of those processes an evaluation loop able to fire the same routine.
 * Nothing in this module's own tests can see it: the root build skips the iOS test compile for
 * `-proxy` modules, so the decision is visible only in source text.
 *
 * Same shape as [SyncSchedulerHolderWiringTest].
 */
class RoutineExecutionWiringTest {

    @Test
    fun `iOS never executes routines, Android and desktop do`() {
        val violations = MODULES.flatMap { (platform, path, expected) ->
            val module = File(path)
            // ⚠ Anti-vacuity: a scan that finds no file passes for the wrong reason.
            assertTrue(module.isFile, "the $platform PlatformProxyModule moved; this test is no longer guarding.")
            val text = withoutComments(module.readText())
            buildList {
                val args = argumentsOf(text, "routineServiceProxyImplementationFactory(")
                if (args == null) {
                    add("$platform: no routineServiceProxyImplementationFactory invocation found — routines cannot be saved here.")
                } else if ("execution = RoutineExecution.$expected," !in args) {
                    add("$platform: routineServiceProxyImplementationFactory must pass `execution = RoutineExecution.$expected`.")
                }
                // ⚠⚠ A null emitter compiles and passes everything else, and makes DeleteRoutine device-local.
                if (args != null && "tombstoneEmitter = emitterHolder.emitter," !in args) {
                    add("$platform: routineServiceProxyImplementationFactory must pass `tombstoneEmitter = emitterHolder.emitter,` — without it a routine delete never leaves this device.")
                }
                // The rows are only readable through their relations once the maintainer is installed.
                if ("initRoutineService()" !in text) {
                    add("$platform: initRoutineService() is never called, so synced routines stay invisible.")
                }
                // The device-event host is the one long-lived engine entry point, and it is the hub's.
                if (expected == "NEVER" && "RoutineEventHost" in text) {
                    add("$platform: binds a RoutineEventHost, which evaluates routines on device events.")
                }
            }
        }

        assertTrue(violations.isEmpty(), "Routine execution is wired wrongly:\n" + violations.joinToString("\n"))
    }

    private companion object {
        val MODULES = listOf(
            Triple("Android", "src/androidMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt", "EXECUTES"),
            Triple("desktop", "src/desktopMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt", "EXECUTES"),
            Triple("iOS", "src/iosMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt", "NEVER"),
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
