package org.tenshu.commerce.store

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The commerce twin of the relay's `RelayMigrationVersionDerivationTest`: `deploy/reset-test-data.sh`
 * stops commerce when its `max(schema_version)` differs from what `deploy/lib/migration-max-version.sh`
 * reads out of this file, so the two must agree. The runner needs Postgres, so the real value comes
 * from the migration list itself (`CommerceDatabase.MIGRATIONS`, read reflectively) — the list the
 * runner walks, not a second parse of the source.
 */
class CommerceMigrationVersionDerivationTest {

    @Test
    fun theResetScriptDerivesTheVersionTheRunnerWrites() {
        val field = CommerceDatabase::class.java.getDeclaredField("MIGRATIONS").apply { isAccessible = true }
        val migrations = field.get(null) as List<*>
        val real = migrations.maxOf { m ->
            m!!.javaClass.getDeclaredMethod("getVersion").apply { isAccessible = true }.invoke(m) as Int
        }

        val helper = File("../deploy/lib/migration-max-version.sh")
        val source = File("src/main/kotlin/org/tenshu/commerce/store/CommerceDatabase.kt")
        val p = ProcessBuilder("bash", helper.path).redirectInput(source).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        assertEquals(0, p.waitFor(), "helper failed: $out")
        assertEquals(real, out.toInt(), "deploy/lib/migration-max-version.sh disagrees with CommerceDatabase")
    }
}
