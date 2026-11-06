package org.tenshu.core.common.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-text audit: production code must access wall time via `context.clock()`,
 * never `kotlin.time.Clock.System.now()` directly. Catches regressions where a new
 * file imports the system clock instead of using the injected one.
 */
class NoDirectClockAccessTest {

    @Test
    fun `no production source under target modules reads Clock_System_now`() {
        val violators = mutableListOf<String>()
        SOURCE_ROOTS.forEach { root ->
            val rootFile = File(root)
            if (!rootFile.exists()) return@forEach
            rootFile.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.name != "SystemClock.kt" }
                .forEach { f ->
                    val text = f.readText()
                    if ("Clock.System.now" in text || "import kotlin.time.Clock" in text) {
                        violators += f.path
                    }
                }
        }
        assertTrue(
            violators.isEmpty(),
            "Production code must access wall time via context.clock(); offenders:\n" +
                    violators.joinToString("\n")
        )
    }

    @Test
    fun `no production source has centisecond residue near time identifiers`() {
        val centisecondPattern = Regex("""(/\s*100\b|\*\s*100\b)""")
        val timeKeyword = Regex("""(?i)time|millis|instant|timestamp|lastModified|hlc|epoch""")
        val violators = mutableListOf<String>()
        SOURCE_ROOTS.forEach { root ->
            val rootFile = File(root)
            if (!rootFile.exists()) return@forEach
            rootFile.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { f ->
                    f.readLines().forEachIndexed { idx, line ->
                        if (centisecondPattern.containsMatchIn(line) &&
                            timeKeyword.containsMatchIn(line)
                        ) {
                            violators += "${f.path}:${idx + 1}: $line"
                        }
                    }
                }
        }
        assertTrue(
            violators.isEmpty(),
            "Centisecond residue (* 100 / / 100 near time identifier):\n" +
                    violators.joinToString("\n")
        )
    }

    companion object {
        // Modules whose production code SystemTests will load. The injected
        // `Clock` invariant must hold across all of them; see
        // `docs/proposals/testing-system-tests.md` §6 phase 0.
        private val SOURCE_ROOTS = listOf(
            "../core/src/commonMain",
            "../core-common/src/commonMain",
            "../file-service/src/commonMain",
            "../file-service-common/src/commonMain",
            "../file-service-client/src/commonMain",
            "../file-service-proxy/src/commonMain",
            "../identity-service/src/commonMain",
            "../identity-service-common/src/commonMain",
            "../identity-service-client/src/commonMain",
            "../identity-service-proxy/src/commonMain",
            "../notification-service/src/commonMain",
            "../notification-service-common/src/commonMain",
            "../notification-service-client/src/commonMain",
            "../notification-service-proxy/src/commonMain",
            "../sync-service/src/commonMain",
            "../sync-service-common/src/commonMain",
            "../sync-service-client/src/commonMain",
            "../sync-service-proxy/src/commonMain"
        )
    }
}
