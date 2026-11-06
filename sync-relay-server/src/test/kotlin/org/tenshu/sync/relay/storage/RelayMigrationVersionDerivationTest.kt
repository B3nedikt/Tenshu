package org.tenshu.sync.relay.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `deploy/reset-test-data.sh` derives the `max(schema_version)` a freshly reset relay must report by
 * reading this module's migration list out of git (`deploy/lib/migration-max-version.sh`), and STOPS
 * the relay when the two disagree. A derivation that drifts from the real runner therefore takes a
 * healthy relay down mid-reset — which is exactly what an anchored regex does to a
 * `Migration(version = N, …)` written on one line. So compare against the runner itself: an
 * empty database migrated by [RelayDatabase].
 */
class RelayMigrationVersionDerivationTest {

    @Test
    fun theResetScriptDerivesTheVersionTheRunnerWrites() {
        val dir = Files.createTempDirectory("relay-migration-version").toFile()
        try {
            val real = RelayDatabase("jdbc:sqlite:${File(dir, "relay.db").absolutePath}").use { db ->
                db.withConnection { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT MAX(version) FROM schema_version").use { rs -> rs.next(); rs.getInt(1) }
                    }
                }
            }
            val source = File("src/main/kotlin/org/tenshu/sync/relay/storage/RelayDatabase.kt")
            assertEquals(real, deriveWithResetScript(source), "deploy/lib/migration-max-version.sh disagrees with RelayDatabase")
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** Runs the reset script's helper exactly as the script does: the Kotlin source on stdin. */
internal fun deriveWithResetScript(source: File): Int {
    val helper = File("../deploy/lib/migration-max-version.sh")
    check(helper.isFile) { "missing ${helper.absolutePath}" }
    check(source.isFile) { "missing ${source.absolutePath}" }
    val p = ProcessBuilder("bash", helper.path).redirectInput(source).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    check(p.waitFor() == 0) { "helper failed: $out" }
    return out.toInt()
}
