package org.tenshu.systemtest

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.schema.migration.CORE_SERVICE_ID
import org.tenshu.core.common.schema.migration.SchemaMigrationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every service's migration chain must reach its declared version — over the **real** service
 * graph.
 *
 * ## ⚠⚠ Why this test exists
 *
 * `SchemaMigrationRegistry.verify` is called at **store open**
 * (`SqliteObjectStore` → `StoreSchemaMigrator.migrateStoreAtOpen`), with no catch around it. A
 * service whose `version = N` has no `fromVersion = N-1` step therefore does not merely degrade —
 * **it throws before any app can open its store**. And if verification were ever bypassed, the
 * derived floor is worse: `currentVersionFloors` walks the registered steps, so a bumped service
 * with no step sits below its own floor and the store is **WIPED**.
 *
 * ⚠ A bump without a step keeps the rest of the suite green: `NonEmptyMigrationGraphSystemTest`
 * opens a store with migrations, but over a **synthetic probe module**, not the real graph. This is
 * the test that runs `verify()` over the services this repo actually ships.
 *
 * ⚠ `serviceGraphConfigs()` holds every *declared* service in the repo; `fit` declares no
 * `ServiceSchema` at all — it is an app.
 */
class ServiceMigrationChainsReachTheirVersionTest {

    @Test
    fun everyServicesMigrationChainReachesItsDeclaredVersion() {
        val registry: SchemaRegistry = createCoreTestRegistry().apply {
            serviceGraphConfigs().forEach { registerConfig(it) }
        }

        // ⚠ The assertion is `verify()` itself, not a re-implementation of its rules: a copy here
        // would pass while the real one threw, which is the failure mode this whole test answers.
        val failure = runCatching {
            SchemaMigrationRegistry()
                .apply { registry.schemaMigrations().forEach { register(it) } }
                .verify(registry)
        }.exceptionOrNull()

        assertEquals(
            null,
            failure,
            "a service was bumped without registering its migration step — this throws at STORE " +
                "OPEN for every app, and left unverified the derived floor wipes the store. " +
                "Register `schemaMigration(schemaMigration(<Service>.ID, fromVersion = N-1) {})` " +
                "— empty is correct when the bump reshaped no types. Cause: ${failure?.message}",
        )
    }

    /**
     * ⚠⚠ A service's **first** bump, which `verify()` cannot see. It judges a chain only for a
     * service that registered at least one step (`migrations.groupBy { serviceId }`), so a service
     * bumped from the v1 release baseline with NO step raises nothing — and its derived floor is then
     * its new version, so every v1 store is **WIPED** at open. Post-release every service starts at
     * v1 with no steps, so this is the case every first bump hits (`photos-editing.md` §12.1 found
     * it: deleting `:photo-service`'s v1→v2 step left the test above green).
     */
    @Test
    fun aServiceAboveTheBaseline_registersAChainAtAll() {
        val registry: SchemaRegistry = createCoreTestRegistry().apply {
            serviceGraphConfigs().forEach { registerConfig(it) }
        }
        val stepped = registry.schemaMigrations().map { it.serviceId }.toSet()
        val bumpedWithNoChain = registry.serviceSchemas()
            .filter { it.version > 1 && it.id.get() !in stepped }
            .map { "${it.id.get()} v${it.version}" }
            .distinct()
        assertEquals(
            emptyList(),
            bumpedWithNoChain,
            "a service above v1 registers no migration step at all — `verify()` only checks chains " +
                "that exist, so this would WIPE every store at the previous version. Register " +
                "`schemaMigration(schemaMigration(<Service>.ID, fromVersion = N-1) {})`.",
        )
    }

    /**
     * ⚠⚠ `:core`'s chain is the one `verify()` structurally cannot judge, and it is the one whose
     * failure is worst.
     *
     * Rule 1 only checks "the chain reaches current" for ids `live.serviceSchemas()` knows
     * (`SchemaMigrationRegistry`), and `:core` registers **no `ServiceSchema`** — its version is
     * [TENSHU_STORAGE_FORMAT_VERSION], a constant. So dropping core's step raises nothing anywhere,
     * while `floorFor(CORE_SERVICE_ID, …)` silently rises to the new version — and every store at
     * the previous format version is **WIPED** at open. That is the same consequence class as the
     * test above, outside its reach, so it gets its own assertion rather than a wider one.
     */
    @Test
    fun coresOwnChainReachesTheStorageFormatVersion() {
        val registry: SchemaRegistry = createCoreTestRegistry().apply {
            serviceGraphConfigs().forEach { registerConfig(it) }
        }
        val coreSteps = registry.schemaMigrations()
            .filter { it.serviceId == CORE_SERVICE_ID }
            .map { it.fromVersion }
            .sorted()

        // At the release baseline (v1, `core-schema-evolution.md` Appendix A) there is nothing below
        // to reach, so the chain is legitimately empty. From the first post-release bump on it must
        // end at N-1.
        assertEquals(
            if (TENSHU_STORAGE_FORMAT_VERSION == 1) null else TENSHU_STORAGE_FORMAT_VERSION - 1,
            coreSteps.lastOrNull(),
            "`:core`'s migration chain must reach TENSHU_STORAGE_FORMAT_VERSION " +
                "($TENSHU_STORAGE_FORMAT_VERSION) — it ends at ${coreSteps.lastOrNull()}. " +
                "`verify()` cannot catch this because :core declares no ServiceSchema, and the " +
                "consequence is not an exception but a SILENT WIPE of every store at the previous " +
                "format version. Register `schemaMigration(CORE_SERVICE_ID, fromVersion = N-1) {}`.",
        )
        assertEquals(
            coreSteps,
            coreSteps.distinct(),
            "duplicate fromVersions in :core's chain: $coreSteps",
        )
    }
}
