package org.tenshu.sync.relay

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ⚠⚠ **A knob the config reads but compose does not forward is inert in production.**
 *
 * `docker compose --env-file deploy.env` feeds compose-file **substitution** only. A container
 * receives exactly what its `environment:` block declares and nothing else, so an operator who adds
 * `S3_ENDPOINT=…` to `deploy.env` — the documented way to configure this relay — changes nothing at
 * all. The relay starts, reports healthy, and quietly keeps the previous behaviour.
 *
 * That cost a live migration window on 2026-09-13: the blobs were copied to object storage, the
 * window was taken, the settings were added to `deploy.env`, the relay was recreated — and it came
 * back on `FileBlobStore`, because none of the five `S3_*` lines existed in the compose file. There
 * is no error for this; both backends serve reads identically.
 *
 * Two more had the same defect and were never noticed because nobody had tried to set them:
 * `ACCOUNT_CLOSURE_RECLAIM` (the retention plane's deletion lever) and `ACCOUNT_CLOSURE_GRACE_MS`,
 * whose KDoc records decision **D4: configurable, never hardcoded** — a property it did not actually
 * have through a compose deploy.
 *
 * ⇒ Every `getenv(...)` in [RelayConfig] must appear in the compose `relay:` service. Declaring one
 * with an empty default (`"${'$'}{VAR:-}"`) is free: every read is `takeIf { it.isNotBlank() }` or a
 * null-returning parse, so an unset knob still falls through to its in-code default.
 */
class ComposeEnvPassthroughTest {

    @Test
    fun everyEnvVarRelayConfigReadsIsDeclaredInCompose() {
        val configSource = repoFile("sync-relay-server/src/main/kotlin/org/tenshu/sync/relay/RelayConfig.kt")
        val compose = repoFile("sync-relay-server/docker-compose.yml")

        val read = Regex("""getenv\("([A-Z0-9_]+)"\)""")
            .findAll(configSource.readText())
            .map { it.groupValues[1] }
            .toSortedSet()
        assertTrue(read.isNotEmpty(), "parsed no getenv(...) calls out of ${configSource.path} — the regex has rotted")

        val declared = relayServiceEnvKeys(compose.readText())
        assertTrue(
            "DATABASE_URL" in declared,
            "parsed no plausible environment block out of ${compose.path} — found $declared",
        )

        val missing = read - declared
        if (missing.isNotEmpty()) {
            fail(
                "RelayConfig reads ${missing.joinToString(", ")} but the compose relay service never " +
                    "declares them, so setting them in deploy.env does NOTHING — the container only " +
                    "receives what `environment:` lists. Add each as `VAR: \"\${VAR:-}\"`.",
            )
        }
    }

    /** Keys of the `environment:` mapping inside the `relay:` service, by indentation. */
    private fun relayServiceEnvKeys(yaml: String): Set<String> {
        val lines = yaml.lines()
        val relayAt = lines.indexOfFirst { it == "  relay:" }
        require(relayAt >= 0) { "no `  relay:` service in the compose file" }
        val envAt = lines.drop(relayAt).indexOfFirst { it == "    environment:" }
        require(envAt >= 0) { "the relay service declares no `environment:` block" }
        return lines.drop(relayAt + envAt + 1)
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
