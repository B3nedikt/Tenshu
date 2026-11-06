package org.tenshu.commerce

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ⚠⚠ **A knob [CommerceConfig] reads but compose does not forward is inert in production.**
 *
 * `docker compose --env-file deploy.env` feeds compose-file **substitution** only. A container gets
 * exactly what its `environment:` block lists, so a key set in `deploy.env` and missing there
 * changes nothing. The service starts, reports healthy, and runs on the code default.
 *
 * Nineteen keys were in that state until 2026-10-30 (`docs/tech-debt.md` had the list). The one
 * that mattered was `WEB_APP_ORIGIN`: without it commerce installs no CORS, so production refused
 * every call from the web apex. Its sign-in, checkout and plan sync could not work however the box
 * was configured. The dev lab hid this by setting the key in its own compose overlay.
 *
 * ⇒ Every `getenv(...)` in [CommerceConfig] must appear in the compose `commerce:` service. This is
 * the twin of the relay's `ComposeEnvPassthroughTest`.
 */
class ComposeEnvPassthroughTest {

    @Test
    fun everyEnvVarCommerceConfigReadsIsDeclaredInCompose() {
        val configSource = repoFile("commerce-service/src/main/kotlin/org/tenshu/commerce/CommerceConfig.kt")
        val compose = repoFile("commerce-service/docker-compose.yml")

        val read = Regex("""getenv\("([A-Z0-9_]+)"\)""")
            .findAll(configSource.readText())
            .map { it.groupValues[1] }
            .toSortedSet()
        assertTrue(read.isNotEmpty(), "parsed no getenv(...) calls out of ${configSource.path} — the regex has rotted")

        val declared = commerceServiceEnvKeys(compose.readText())
        assertTrue(
            "ISSUER_SEED" in declared,
            "parsed no plausible environment block out of ${compose.path} — found $declared",
        )

        val missing = read - declared
        if (missing.isNotEmpty()) {
            fail(
                "CommerceConfig reads ${missing.joinToString(", ")} but the compose commerce service never " +
                    "declares them, so setting them in deploy.env does NOTHING — the container only " +
                    "receives what `environment:` lists. Add each as `VAR: \"\${VAR:-}\"`.",
            )
        }
    }

    /** Keys of the `environment:` mapping inside the `commerce:` service, by indentation. */
    private fun commerceServiceEnvKeys(yaml: String): Set<String> {
        val lines = yaml.lines()
        val serviceAt = lines.indexOfFirst { it == "  commerce:" }
        require(serviceAt >= 0) { "no `  commerce:` service in the compose file" }
        val envAt = lines.drop(serviceAt).indexOfFirst { it == "    environment:" }
        require(envAt >= 0) { "the commerce service declares no `environment:` block" }
        return lines.drop(serviceAt + envAt + 1)
            .takeWhile { it.isBlank() || it.startsWith("      ") }
            .mapNotNull { Regex("""^ {6}([A-Z0-9_]+):""").find(it)?.groupValues?.get(1) }
            .toSortedSet()
    }

    /** Walks up from the working directory so the test runs from the module or the repo root. */
    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("could not find $relative walking up from ${System.getProperty("user.dir")}")
    }
}
