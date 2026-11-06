package org.tenshu.systemtest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.schemaMigration
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.openSqliteObjectStore
import org.tenshu.core.common.schema.migration.StoreSchemaMigrations
import org.tenshu.core.common.schema.migration.schemaMigration as buildSchemaMigration
import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.service
import org.tenshu.core.schema.string
import org.tenshu.core.toTString
import org.tenshu.systemtest.di.systemTestProxyModule
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * ⚠⚠ **The gate that was missing for three slices (E2j-5,
 * `core-schema-evolution.md` §8.9).**
 *
 * E2j-1..E2j-3 shipped the whole store-migration path green — 639 `desktopTest`,
 * `StoreVersionGateTest`, `InMemoryStoreMigrationTest`, the full system-test
 * graph boot, the Android smoke gate, `xcodebuild` — and **the first real
 * migration deadlocked the app at startup on both platforms.** Every suite
 * exercised the machinery with *nothing to do*: the only migrations in the repo
 * are deliberately empty bodies (`SyncService` v2, `TimelineService` v1), and
 * the only test that migrates real rows
 * ([SqliteObjectStoreMigrationTest][org.tenshu.core.common.proxy.db]) builds its
 * store by hand, outside any container.
 *
 * The two properties that mattered were therefore untested **together**:
 *
 *  1. a **non-empty** `SchemaMigration` registered in a real service graph, with
 *     real drift to apply — not an empty body, not a hand-built fixture; and
 *  2. the store opening **inside a DI factory**, which is where production opens
 *     it, and which is what made the migration re-enter the container that was
 *     still being constructed (E2j-4: `base.components + overrides` enumerated
 *     the lazy DI-backed component view from a worker thread while the calling
 *     thread held the singleton locks).
 *
 * So this test supplies exactly that pair, over a synthetic service nothing else
 * consumes. ⚠ It deliberately uses a **real `SqliteObjectStore` on disk** rather
 * than the harness's `InMemoryObjectStore` — an in-memory store has no recorded
 * version table and so cannot drift.
 *
 * ⚠ A regression here **hangs** rather than fails, which is why the resolution
 * runs on a watchdog thread: a deadlocked Koin graph would otherwise wedge the
 * whole suite until Gradle's timeout, with nothing naming the cause.
 */
class NonEmptyMigrationGraphSystemTest {

    @Test
    fun `a reshaped service migrates its rows when the store opens inside the graph`() {
        val path = createTempDirectory("non-empty-migration").resolve("objects.db").toString()

        // ── The old build: the probe service at v1, one row in its v1 shape.
        seedStoreAtV1(path)

        // ── The new build: v2 + a registered migration, resolved through a
        // graph shaped like production's (DI-backed TContext, store opened from
        // a `single` factory that bridges the suspend open with runBlocking).
        val koin = koinApplication {
            modules(systemTestProxyModule(), storeOnDiskAt(path))
        }.koin
        // Mirrors E2j-3's `augment` path: a config folded in after the registry
        // exists. Registering before the store resolves is what gives the open
        // gate a v2 declaration to compare the recorded v1 against.
        koin.get<SchemaRegistry>().registerConfig(probeModuleAtV2())

        val store = resolveWithinTimeout(koin)

        // ── The row migrated in place: kept, reshaped, not wiped.
        val migrated = store.getObject(PROBE_ROW_HASH)
            ?: fail("the probe row is gone — the store wiped instead of migrating")
        assertEquals(
            listOf<ObjectStore.DbValue>(
                ObjectStore.DbValue.StringValue(PROBE_NAME),
                ObjectStore.DbValue.StringValue("derived/$PROBE_NAME"),
            ),
            migrated.fields,
            "the v2 row must keep its v1 value and carry the derived one",
        )
    }

    // --- the graph under test -------------------------------------------------

    /**
     * The production store binding's shape: a `single` that bridges the suspend
     * open with `runBlocking` and builds its migration config from the graph.
     * ⚠ Both halves are load-bearing for what this test covers — resolving the
     * context via `get()` inside the factory is precisely the re-entrancy E2j-4
     * fixed.
     */
    private fun storeOnDiskAt(path: String): Module = module {
        single<ObjectStore> {
            runBlocking {
                openSqliteObjectStore(
                    path = path,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    migrations = StoreSchemaMigrations(
                        context = get(),
                        schemas = get(),
                        deviceId = "non-empty-migration-test",
                    ),
                )
            }
        }
    }

    /** Fails on a deadlock instead of hanging the suite. */
    private fun resolveWithinTimeout(koin: org.koin.core.Koin): ObjectStore {
        val outcome = AtomicReference<Result<ObjectStore>>()
        val worker = Thread { outcome.set(runCatching { koin.get<ObjectStore>() }) }
        worker.isDaemon = true
        worker.start()
        worker.join(RESOLUTION_TIMEOUT_MS)
        if (worker.isAlive) {
            fail(
                "opening the store did not finish in ${RESOLUTION_TIMEOUT_MS}ms — the migration " +
                    "deadlocked against the graph it runs inside (core-schema-evolution.md §8.9, E2j-4)",
            )
        }
        return outcome.get().getOrThrow()
    }

    // --- the synthetic service ------------------------------------------------

    private fun seedStoreAtV1(path: String) = runBlocking {
        val v1Registry = org.tenshu.core.common.createCoreTestRegistry().apply {
            registerConfig(probeModuleAtV1())
        }
        val store = openSqliteObjectStore(
            path = path,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            migrations = StoreSchemaMigrations(
                context = org.tenshu.core.common.createTestContext(v1Registry),
                schemas = v1Registry,
                deviceId = "non-empty-migration-test",
            ),
        )
        store.storeObject(
            ObjectStore.DbEntry(
                type = PROBE_ID,
                dataIdHash = PROBE_ROW_HASH,
                fields = listOf(ObjectStore.DbValue.StringValue(PROBE_NAME)),
                properties = emptyMap(),
            ),
        )
        store.flushBarrier()
    }

    private fun probeModuleAtV1(): ModuleConfiguration = tModule {
        service(service(id = PROBE_SERVICE_ID, version = 1))
        composite(PROBE_AT_V1)
    }

    /**
     * v2 = one new REQUIRED field derived from the old one. Required-and-derived
     * on purpose: an optional add starts absent (§8.4) and would leave the row's
     * bytes unchanged, so it could pass without the migration ever running.
     */
    private fun probeModuleAtV2(): ModuleConfiguration = tModule {
        service(service(id = PROBE_SERVICE_ID, version = 2))
        composite(PROBE_AT_V2)
        schemaMigration(
            buildSchemaMigration(PROBE_SERVICE_ID, fromVersion = 1) {
                migration(PROBE_ID, from = PROBE_AT_V1, to = PROBE_AT_V2) {
                    derive(ProbeFields.DERIVED) { old, ctx ->
                        ("derived/" + (old as Composite).getField<TString>(ProbeFields.NAME).get())
                            .toTString(ctx.context)
                    }
                }
            },
        )
    }

    private companion object {
        const val PROBE_SERVICE_ID = "tenshu/test/nonEmptyMigration/service"
        const val PROBE_ID = "tenshu/test/nonEmptyMigration/probe"
        const val PROBE_NAME = "alpha"
        const val PROBE_ROW_HASH = 4242L
        const val RESOLUTION_TIMEOUT_MS = 60_000L

        val PROBE_AT_V1: CompositeSchema = composite(
            id = PROBE_ID,
            fields = { string(ProbeFields.NAME) },
        )

        val PROBE_AT_V2: CompositeSchema = composite(
            id = PROBE_ID,
            fields = {
                string(ProbeFields.NAME)
                string(ProbeFields.DERIVED)
            },
        )
    }

    private object ProbeFields {
        const val NAME = "tenshu/test/nonEmptyMigration/probe/name"
        const val DERIVED = "tenshu/test/nonEmptyMigration/probe/derived"
    }
}

private fun TContext.unused() = Unit
