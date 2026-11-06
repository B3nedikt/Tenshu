package org.tenshu.core.common.proxy.db.sqlite

import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.schema.migration.CORE_SERVICE_ID
import org.tenshu.core.common.schema.migration.SchemaMigration
import org.tenshu.core.common.schema.migration.SchemaMigrationRegistry
import org.tenshu.core.common.schema.migration.StoreRowMigrationCodec
import org.tenshu.core.common.schema.migration.StoreSchemaMigrations

/**
 * ⚠⚠ **The schema registry's migration chains as the chunked migrator reads them**
 * (`ios-shared-store-sqlite.md` Appendix B.3 (25), B.24): what the JVM `StoreSchemaMigrator.runStep`
 * does to one row, handed to `ReplicaReplacement` one row at a time.
 *
 * A step's [ReplicaMigrationStep.typeIds] are its `CompositeMigration`s' types **and** its
 * `droppedProperties`' types; its `migrate` is `StoreRowMigrationCodec.migrateRow` (for a type the
 * step reshapes) followed by the property strip (for a type it retires a property of). The JVM's
 * order and result, row for row: the rewrite first, a row dropped by decision is never stripped, a
 * row of a strip-only type is the stored row less those slots.
 *
 * `:core`'s steps are addressed like any service's, under [CORE_SERVICE_ID]: the chunked migrator
 * stamps them into `user_version` (`ReplicaReplacement.stamp`).
 *
 * ⚠⚠ **A step at or above this build's version is a defect, never a wipe.** The JVM migrator's
 * "written by newer code" branch wipes; on iOS a newer store is `Skew` by `classify` before any job
 * exists, and every marker a chunk resumes was re-judged by the same rule. So [step] is never asked
 * for a version this build does not migrate *from*, and if it ever were, answering null would be
 * read as "no step: wipe the store". It throws instead: the chunk is rolled back, nothing is
 * written, the run stops.
 *
 * Built once per open, from the graph's own schema registry. The chains are verified here, as the
 * JVM verifies them at every configured open (`SchemaMigrationRegistry.verify`): a broken chain
 * refuses the open rather than migrating rows by a wrong snapshot.
 *
 * ⚠ **Every step is built here, up front, on the open's thread** ([built]): [step] is a map lookup.
 * It is asked once per chunk inside `BEGIN IMMEDIATE`, so building a codec there would count against
 * `HOLD_MAX`; and it is asked on the worker's thread. What runs on the worker is then only
 * `migrate` itself, whose registry calls (`createValue`, `createConstant`, `create` under the step's
 * overlay) read the registry's module list, which is not written after the graph is built, and its
 * caches, which are lock-free compare-and-set maps (`SchemaRegistry.materializationCache`,
 * `typeIdIndexRef`): the same calls every `Dispatchers.Default` reader of the live graph makes
 * concurrently today. Nothing is resolved from the DI graph by building a codec
 * (`OverlayTContext` resolves per key, at use: E2j-4).
 */
class RegistryReplicaMigrationSteps(
    private val migrations: StoreSchemaMigrations,
    /** `:core`'s version: [TENSHU_STORAGE_FORMAT_VERSION]. A test's own, to bump `:core` with production at v1. */
    private val coreVersion: Int = TENSHU_STORAGE_FORMAT_VERSION,
) : ReplicaMigrationSteps {
    private val chains = SchemaMigrationRegistry().apply {
        migrations.schemas.schemaMigrations().forEach(::register)
        verify(migrations.schemas)
    }

    /** This build's versions, `:core`'s under its service id. */
    private val declared: Map<String, Int> =
        migrations.declaredServiceVersions() + (CORE_SERVICE_ID to coreVersion)

    /** Every registered step this build migrates from, by `(service, from)`, built once ([RegistryStep] is stateless). */
    private val built: Map<Pair<String, Int>, ReplicaMigrationStep> =
        migrations.schemas.schemaMigrations()
            .filter { step -> declared[step.serviceId]?.let { step.fromVersion < it } == true }
            .associate { step ->
                (step.serviceId to step.fromVersion) to
                    RegistryStep(step, StoreRowMigrationCodec(step, migrations.schemas, migrations.context, migrations.deviceId))
            }

    override fun step(serviceId: String, fromVersion: Int): ReplicaMigrationStep? {
        val current = declared[serviceId]
            // Not a registry service: a store naming one this build does not know is Skew, and the
            // list-segment format stamp (`ReplicaListSegments.FORMAT_SERVICE`) has no chain at all
            // (its floor is its version, and an unstamped store is stamped with no step asked).
            ?: error("[replica] a migration step was asked of '$serviceId', which has no chain in this build's schema registry")
        check(fromVersion < current) {
            "[replica] a migration step was asked of '$serviceId' from v$fromVersion, at or above this build's v$current: " +
                "a newer store is Skew and is never migrated or wiped"
        }
        return built[serviceId to fromVersion]
    }

    /** The oldest version of [serviceId] (or `:core`, [CORE_SERVICE_ID]) the registered chain migrates from: its readable floor. */
    fun floorOf(serviceId: String): Int = chains.floorFor(serviceId, declared.getValue(serviceId))

    /**
     * ⚠⚠ This build as the open judges a store by (§5.2, §7), **each floor the oldest version its
     * registered chain migrates from** (B.24), from the same registry as the steps: a store at or
     * above every floor is migrated, one below any is wiped ("no step can read it"), as the JVM store
     * does. With no step registered (every production chain at the 1.0.1 baseline) a floor is the
     * version itself. With the list-segment format's stamp (B.19.9 (343)), whose floor is its version.
     */
    fun replicaBuild(): ReplicaBuild {
        val services = migrations.declaredServiceVersions()
        return ReplicaBuild(
            coreVersion = coreVersion,
            coreFloor = floorOf(CORE_SERVICE_ID),
            services = services,
            serviceFloors = services.mapValues { (service, _) -> floorOf(service) },
        ).withListSegments()
    }

    private class RegistryStep(step: SchemaMigration, private val codec: StoreRowMigrationCodec) : ReplicaMigrationStep {
        private val reshaped: Set<String> = step.migrations.mapTo(HashSet()) { it.typeId }
        private val retired: Map<String, Set<String>> =
            step.droppedProperties.groupBy({ it.typeId }, { it.propertyId }).mapValues { (_, ids) -> ids.toSet() }

        override val typeIds: Set<String> = reshaped + retired.keys

        override fun migrate(row: DbEntry): DbEntry? {
            val rewritten = if (row.type in reshaped) codec.migrateRow(row) ?: return null else row
            val gone = retired[rewritten.type] ?: return rewritten
            if (rewritten.properties.keys.none { it in gone }) return rewritten
            return rewritten.copy(properties = rewritten.properties.filterKeys { it !in gone })
        }
    }
}
