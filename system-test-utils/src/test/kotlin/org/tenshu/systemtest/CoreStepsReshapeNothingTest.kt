package org.tenshu.systemtest

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.schema.migration.CORE_SERVICE_ID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ⚠⚠ Every `:core` step (`schemaMigration(CORE_SERVICE_ID, …)`) must be **empty** until the JVM
 * store migrates core rows, over the **real** service graph.
 *
 * A core step lowers `coreReadableFloor()` on every platform, but only the iOS replica's chunked
 * migrator runs its row work: `StoreSchemaMigrator.migrateStoreAtOpen` walks `serviceSchemas()`,
 * which `:core` is not in, so `SqliteObjectStore` (and JS's `InMemoryStoreMigration`) re-stamp
 * `user_version` and rewrite **no** row. A core step with `CompositeMigration`s or
 * `droppedProperties` would leave every JVM/Android/desktop/JS store holding old-shaped core rows,
 * read by new code, while iOS migrated the same rows (`ios-shared-store-sqlite.md` B.24).
 *
 * The day this goes red is the day the JVM walk owes a core pass: `docs/tech-debt.md`, "A `:core`
 * step that reshapes rows is honoured on iOS and not on the JVM".
 */
class CoreStepsReshapeNothingTest {

    @Test
    fun everyCoreStepIsEmptyUntilTheJvmStoreMigratesCoreRows() {
        val registry: SchemaRegistry = createCoreTestRegistry().apply {
            serviceGraphConfigs().forEach { registerConfig(it) }
        }
        val reshaping = registry.schemaMigrations()
            .filter { it.serviceId == CORE_SERVICE_ID }
            .filter { it.migrations.isNotEmpty() || it.droppedProperties.isNotEmpty() }
            .map { step ->
                "v${step.fromVersion}→v${step.fromVersion + 1}: " +
                    "migrations ${step.migrations.map { it.typeId }}, dropped properties " +
                    step.droppedProperties.map { "${it.typeId}#${it.propertyId}" }
            }

        assertEquals(
            emptyList(),
            reshaping,
            "a `:core` step reshapes rows, and the JVM store never runs a core step's row work " +
                "(StoreSchemaMigrator walks serviceSchemas() only): JVM/Android/desktop/JS stores " +
                "would keep old-shaped core rows while iOS migrates them. Give the JVM walk a core " +
                "pass first — see docs/tech-debt.md, \"A `:core` step that reshapes rows is honoured " +
                "on iOS and not on the JVM\".",
        )
    }
}
