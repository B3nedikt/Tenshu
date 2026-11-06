package org.tenshu.core.common.proxy.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.schemaMigration
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import org.tenshu.core.common.schema.migration.SchemaMigration
import org.tenshu.core.common.schema.migration.StoreSchemaMigrations
import org.tenshu.core.common.schema.migration.THING_ID
import org.tenshu.core.common.schema.migration.THING_SERVICE_ID
import org.tenshu.core.common.schema.migration.ThingFields
import org.tenshu.core.common.schema.migration.createMigrationTestRegistry
import org.tenshu.core.common.schema.migration.schemaMigration
import org.tenshu.core.common.schema.migration.thingMigrationStep
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.service as serviceSchema
import org.tenshu.core.schema.string
import org.tenshu.core.toTString
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store-open migration seam (`core-schema-evolution.md` §8.1/§8.4):
 * v-old rows written under a frozen snapshot reopen in the new shape, the
 * recorded per-service version bumps, untouched rows stay byte-identical, a
 * crash-left journal marker resumes idempotently, and the postures of a store
 * opened without a migration config (`user_version` wipe, absent version
 * table) hold.
 */
class SqliteObjectStoreMigrationTest {

    private fun tempDbPath(): String =
        createTempDirectory("sqlite-objstore-migration").resolve("objects.db").toString()

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- configs --------------------------------------------------------------

    private fun migrationsFor(registry: SchemaRegistry) = StoreSchemaMigrations(
        context = createTestContext(registry),
        schemas = registry,
        deviceId = "store-migration-test",
    )

    /** The store's authoring build: thing service at v1, no chains. */
    private fun configAtV1() = migrationsFor(
        createCoreTestRegistry().apply {
            registerConfig(tModule {
                service(serviceSchema(id = THING_SERVICE_ID, version = 1))
            })
        }
    )

    /** The upgraded build: thing service at v2 + the v1→v2 chain registered. */
    private fun configAtV2() = migrationsFor(
        createMigrationTestRegistry().apply {
            registerConfig(tModule {
                service(serviceSchema(id = THING_SERVICE_ID, version = 2))
                schemaMigration(thingMigrationStep())
            })
        }
    )

    // --- rows -----------------------------------------------------------------

    /** A THING row as the v1 snapshot wrote it: [title, legacy, size-as-string]. */
    private fun v1ThingRow(hash: Long, title: String = "alpha") = DbEntry(
        type = THING_ID,
        dataIdHash = hash,
        fields = listOf(
            DbValue.StringValue(title),
            DbValue.StringValue("junk"),
            DbValue.StringValue("42"),
        ),
        // Entity-style relation values ride outside the migrated field list
        // and must be preserved verbatim.
        properties = mapOf("some/property" to DbValue.StringValue("kept")),
    )

    private fun untouchedRow(hash: Long) = DbEntry(
        type = "tenshu/test/migration/untouched",
        dataIdHash = hash,
        fields = listOf(DbValue.StringValue("keep-me"), DbValue.LongValue(7L)),
        properties = emptyMap(),
    )

    // --- raw SQL helpers (closed-store inspection, rawSetUserVersion pattern) --

    private fun <R> withRawConnection(path: String, block: (androidx.sqlite.SQLiteConnection) -> R): R {
        val conn = BundledSQLiteDriver().open(path)
        return try {
            block(conn)
        } finally {
            conn.close()
        }
    }

    private fun rawRowBytes(path: String, hash: Long): ByteArray? =
        withRawConnection(path) { conn ->
            val s = conn.prepare("SELECT data FROM objects WHERE hash = ?")
            try {
                s.bindLong(1, hash)
                if (s.step()) s.getBlob(0) else null
            } finally {
                s.close()
            }
        }

    private fun rawServiceVersions(path: String): Map<String, Int>? =
        withRawConnection(path) { conn ->
            if (!rawTableExists(conn, "schema_service_versions")) return@withRawConnection null
            val s = conn.prepare("SELECT service_id, version FROM schema_service_versions")
            try {
                val versions = mutableMapOf<String, Int>()
                while (s.step()) versions[s.getText(0)] = s.getLong(1).toInt()
                versions
            } finally {
                s.close()
            }
        }

    private fun rawJournalSteps(path: String): List<Pair<String, Int>>? =
        withRawConnection(path) { conn ->
            if (!rawTableExists(conn, "schema_migration_journal")) return@withRawConnection null
            val s = conn.prepare("SELECT service_id, from_version FROM schema_migration_journal")
            try {
                val steps = mutableListOf<Pair<String, Int>>()
                while (s.step()) steps += s.getText(0) to s.getLong(1).toInt()
                steps
            } finally {
                s.close()
            }
        }

    private fun rawTableExists(conn: androidx.sqlite.SQLiteConnection, name: String): Boolean {
        val s = conn.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name = ?")
        return try {
            s.bindText(1, name)
            s.step()
        } finally {
            s.close()
        }
    }

    private fun rawExec(path: String, vararg sql: String) = withRawConnection(path) { conn ->
        sql.forEach { one ->
            val s = conn.prepare(one)
            try {
                s.step()
            } finally {
                s.close()
            }
        }
    }

    private fun rawSetUserVersion(path: String, version: Int) =
        rawExec(path, "PRAGMA user_version = $version")

    private fun rawUserVersion(path: String): Int = withRawConnection(path) { conn ->
        val s = conn.prepare("PRAGMA user_version")
        try {
            if (s.step()) s.getLong(0).toInt() else 0
        } finally {
            s.close()
        }
    }

    // --- tests ----------------------------------------------------------------

    @Test
    fun `v1 rows migrate to the v2 shape on reopen and untouched rows stay byte-identical`() =
        runTest {
            val path = tempDbPath()

            val v1Store = SqliteObjectStore.open(path, scope(), migrations = configAtV1())
            v1Store.storeObject(v1ThingRow(100L))
            v1Store.storeObject(untouchedRow(200L))
            v1Store.flush()
            v1Store.close()
            // The core test registry registers services of its own — assert
            // only the fixture service's recorded version.
            assertEquals(1, rawServiceVersions(path)!![THING_SERVICE_ID])
            val untouchedBytesBefore = rawRowBytes(path, 200L)!!

            val v2Store = SqliteObjectStore.open(path, scope(), migrations = configAtV2())
            val migrated = v2Store.getObject(100L)!!
            // v2 positional shape: [name(rename), kind(derive), size(convert
            // string→int), note(new optional → absent, never defaulted)].
            assertEquals(THING_ID, migrated.type)
            assertEquals(
                listOf<DbValue>(
                    DbValue.StringValue("alpha"),
                    DbValue.StringValue("kind:alpha"),
                    DbValue.LongValue(42L),
                    DbValue.NullValue,
                ),
                migrated.fields,
            )
            // Relation/property values outside the field list are preserved.
            assertEquals(
                mapOf<String, DbValue>("some/property" to DbValue.StringValue("kept")),
                migrated.properties,
            )
            assertEquals(untouchedRow(200L), v2Store.getObject(200L))
            v2Store.close()

            assertEquals(2, rawServiceVersions(path)!![THING_SERVICE_ID])
            assertEquals(emptyList(), rawJournalSteps(path).orEmpty())
            assertContentEquals(untouchedBytesBefore, rawRowBytes(path, 200L))
        }

    @Test
    fun `a multi-step chain applies each step in order as its own store pass`() = runTest {
        val path = tempDbPath()
        val serviceId = "tenshu/test/migration/store/multiService"
        val typeId = "tenshu/test/migration/store/multi"
        val fieldA = "$typeId/a"
        val fieldB = "$typeId/b"
        val v1 = composite(typeId, fields = { string(fieldA) })
        val v2 = composite(typeId, fields = { string(fieldA); string(fieldB) })
        val v3 = composite(typeId, fields = { string(fieldB) })
        // v1→v2 derives B from A; v2→v3 drops A. Step 1's `to` (v2) is NOT the
        // live schema (v3) — the intermediate-dto hazard (§8.5) territory the
        // per-step store pass + to-bound overlay must handle.
        val step1: SchemaMigration = schemaMigration(serviceId, fromVersion = 1) {
            migration(typeId, from = v1, to = v2) {
                derive(fieldB) { old, ctx ->
                    ("b/" + (old as Composite).getField<TString>(fieldA).get()).toTString(ctx.context)
                }
            }
        }
        val step2: SchemaMigration = schemaMigration(serviceId, fromVersion = 2) {
            migration(typeId, from = v2, to = v3) {
                drop(fieldA)
            }
        }
        val atV1 = migrationsFor(
            createCoreTestRegistry().apply {
                registerConfig(tModule { service(serviceSchema(id = serviceId, version = 1)) })
            }
        )
        val atV3 = migrationsFor(
            createCoreTestRegistry().apply {
                registerConfig(tModule {
                    service(serviceSchema(id = serviceId, version = 3))
                    composite(v3)
                    schemaMigration(step1)
                    schemaMigration(step2)
                })
            }
        )

        val v1Store = SqliteObjectStore.open(path, scope(), migrations = atV1)
        v1Store.storeObject(
            DbEntry(typeId, 10L, listOf(DbValue.StringValue("x")), emptyMap())
        )
        v1Store.flush()
        v1Store.close()

        val v3Store = SqliteObjectStore.open(path, scope(), migrations = atV3)
        assertEquals(
            listOf<DbValue>(DbValue.StringValue("b/x")),
            v3Store.getObject(10L)!!.fields,
        )
        v3Store.close()
        assertEquals(3, rawServiceVersions(path)!![serviceId])
    }

    @Test
    fun `dropType rows are deleted and their membership entries swept`() = runTest {
        val path = tempDbPath()
        val serviceId = "tenshu/test/migration/store/dropService"
        val retiredId = "tenshu/test/migration/store/retired"
        val retiredAtV1 = composite(retiredId, fields = { string("$retiredId/x") })
        val step = schemaMigration(serviceId, fromVersion = 1) {
            dropType(typeId = retiredId, from = retiredAtV1)
        }
        val atV1 = migrationsFor(
            createCoreTestRegistry().apply {
                registerConfig(tModule { service(serviceSchema(id = serviceId, version = 1)) })
            }
        )
        val atV2 = migrationsFor(
            createCoreTestRegistry().apply {
                registerConfig(tModule {
                    service(serviceSchema(id = serviceId, version = 2))
                    schemaMigration(step)
                })
            }
        )

        val v1Store = SqliteObjectStore.open(path, scope(), migrations = atV1)
        v1Store.storeObject(
            DbEntry(retiredId, 300L, listOf(DbValue.StringValue("gone")), emptyMap())
        )
        v1Store.storeObject(untouchedRow(301L))
        // An array row holding the retired row + a surviving member.
        v1Store.storeObject(
            DbEntry(
                type = TList.ID,
                dataIdHash = 400L,
                fields = listOf(DbValue.LongValue(300L), DbValue.LongValue(301L)),
                properties = emptyMap(),
            )
        )
        v1Store.flush()
        v1Store.close()

        val v2Store = SqliteObjectStore.open(path, scope(), migrations = atV2)
        assertNull(v2Store.getObject(300L), "dropped-type row must be deleted")
        assertEquals(
            listOf<DbValue>(DbValue.LongValue(301L)),
            v2Store.getObject(400L)!!.fields,
            "the deleted row's membership entry must be swept",
        )
        assertEquals(untouchedRow(301L), v2Store.getObject(301L))
        v2Store.close()
        assertEquals(2, rawServiceVersions(path)!![serviceId])
    }

    @Test
    fun `a crash-left journal marker re-runs the step idempotently and clears`() = runTest {
        val path = tempDbPath()

        val v1Store = SqliteObjectStore.open(path, scope(), migrations = configAtV1())
        v1Store.storeObject(v1ThingRow(500L, title = "resumed"))
        v1Store.flush()
        v1Store.close()

        // Model the crash window: the begin-marker transaction committed, the
        // step's rewrite transaction did not (so rows are still v1-shaped and
        // the recorded version is still 1 — the only state a surviving marker
        // can coexist with, since clear-marker rides the rewrite transaction).
        rawExec(
            path,
            "CREATE TABLE IF NOT EXISTS schema_migration_journal (" +
                "service_id TEXT NOT NULL, from_version INTEGER NOT NULL, " +
                "PRIMARY KEY (service_id, from_version))",
            "INSERT INTO schema_migration_journal (service_id, from_version) " +
                "VALUES ('$THING_SERVICE_ID', 1)",
        )

        val v2Store = SqliteObjectStore.open(path, scope(), migrations = configAtV2())
        assertEquals(
            listOf<DbValue>(
                DbValue.StringValue("resumed"),
                DbValue.StringValue("kind:resumed"),
                DbValue.LongValue(42L),
                DbValue.NullValue,
            ),
            v2Store.getObject(500L)!!.fields,
        )
        v2Store.close()

        assertEquals(emptyList(), rawJournalSteps(path), "marker must be cleared")
        assertEquals(2, rawServiceVersions(path)!![THING_SERVICE_ID])
    }

    @Test
    fun `a no-drift reopen is a no-op with byte-identical rows and no journal`() = runTest {
        val path = tempDbPath()

        val first = SqliteObjectStore.open(path, scope(), migrations = configAtV2())
        // A row already in the live v2 shape.
        first.storeObject(
            DbEntry(
                type = THING_ID,
                dataIdHash = 600L,
                fields = listOf(
                    DbValue.StringValue("name"),
                    DbValue.StringValue("kind:name"),
                    DbValue.LongValue(1L),
                    DbValue.NullValue,
                ),
                properties = emptyMap(),
            )
        )
        first.flush()
        first.close()
        val bytesBefore = rawRowBytes(path, 600L)!!
        val versionsBefore = rawServiceVersions(path)

        val reopened = SqliteObjectStore.open(path, scope(), migrations = configAtV2())
        reopened.close()

        assertContentEquals(bytesBefore, rawRowBytes(path, 600L))
        assertEquals(versionsBefore, rawServiceVersions(path))
        assertNull(rawJournalSteps(path), "no journal table may ever be created on a no-op open")
    }

    // ⚠⚠ At the release baseline `coreCommonModule` registers NO core step, so the
    // core floor equals `TENSHU_STORAGE_FORMAT_VERSION` (1) and no store-level "one version back
    // migrates" case exists to pin. The reachability rule itself stays pinned by
    // `StoreVersionGateTest`, and per-service migration by the THING-service tests above.
    //
    // ⚠ When the first post-release core bump lands WITH its `schemaMigration(CORE_SERVICE_ID,
    // fromVersion = 1)` step, add the in-floor test ("a below-current user_version inside the
    // core floor migrates instead of wiping") and flip the test below to it — that is the case
    // that protects every installed device.

    @Test
    fun `at the baseline the core chain is empty so the core floor is current`() = runTest {
        val path = tempDbPath()

        val first = SqliteObjectStore.open(path, scope(), migrations = configAtV2())
        first.storeObject(untouchedRow(900L))
        first.flush()
        first.close()
        // A store recorded at core v22 (numbering from before the release reset) reads as
        // "written by newer code" and wipes.
        rawSetUserVersion(path, 22)

        val reopened = SqliteObjectStore.open(path, scope(), migrations = configAtV2())

        assertNull(reopened.getObject(900L), "a pre-reset store must wipe, never be read")
        reopened.close()
        assertEquals(TENSHU_STORAGE_FORMAT_VERSION, rawUserVersion(path))
    }

    @Test
    fun `a user_version below the core floor still wipes`() = runTest {
        val path = tempDbPath()

        val first = SqliteObjectStore.open(path, scope(), migrations = configAtV2())
        first.storeObject(untouchedRow(910L))
        first.flush()
        first.close()
        // Below the floor: unreachable, so unreadable. (`- 2` keeps meaning "below" once a core
        // chain exists; at the v1 baseline it is -1, which SQLite stores as-is.)
        rawSetUserVersion(path, TENSHU_STORAGE_FORMAT_VERSION - 2)

        val reopened = SqliteObjectStore.open(
            path,
            scope(),
            migrations = configAtV2(),
        )

        assertNull(reopened.getObject(910L), "below-floor store must wipe")
        assertTrue(reopened.isEmpty())
        reopened.close()
        assertEquals(TENSHU_STORAGE_FORMAT_VERSION, rawUserVersion(path))
    }

    @Test
    fun `a store written by newer code wipes rather than reading forward-shaped rows`() = runTest {
        val path = tempDbPath()

        val first = SqliteObjectStore.open(path, scope(), migrations = configAtV2())
        first.storeObject(untouchedRow(920L))
        first.flush()
        first.close()
        rawSetUserVersion(path, TENSHU_STORAGE_FORMAT_VERSION + 1)

        // Mirrors StoreSchemaMigrator's per-service `recorded > declared` posture:
        // a chain migrates forward, never backward, so however low the floor is
        // there is no step that could read these rows.
        val reopened = SqliteObjectStore.open(
            path,
            scope(),
            migrations = configAtV2(),
        )

        assertNull(reopened.getObject(920L), "forward-shaped store must wipe")
        reopened.close()
        assertEquals(TENSHU_STORAGE_FORMAT_VERSION, rawUserVersion(path))
    }

    @Test
    fun `an unreachable user_version wipes and the service versions re-stamp current`() =
        runTest {
            val path = tempDbPath()

            val first = SqliteObjectStore.open(path, scope(), migrations = configAtV2())
            first.storeObject(v1ThingRow(700L))
            first.flush()
            first.close()
            // A genuinely unreachable version: `- 2` stays below the floor once a core chain
            // exists (one version back becomes reachable then).
            rawSetUserVersion(path, TENSHU_STORAGE_FORMAT_VERSION - 2)

            val reopened = SqliteObjectStore.open(path, scope(), migrations = configAtV2())
            assertNull(reopened.getObject(700L), "below-floor store must still wipe")
            assertTrue(reopened.isEmpty())
            reopened.close()
            assertEquals(2, rawServiceVersions(path)!![THING_SERVICE_ID])
        }

    @Test
    fun `a pre-E2b store without a version table is stamped current without touching rows`() =
        runTest {
            val path = tempDbPath()

            // An open with no migration config → no version table is created.
            val preE2b = SqliteObjectStore.open(path, scope())
            preE2b.storeObject(v1ThingRow(800L))
            preE2b.flush()
            preE2b.close()
            assertNull(rawServiceVersions(path))
            val bytesBefore = rawRowBytes(path, 800L)!!

            // First migration-aware open: stamp current, touch no rows — even
            // though a v1→v2 chain is registered (a store with no version table
            // under a matching user_version is current-shaped by House Rule #9).
            val migrationAware = SqliteObjectStore.open(path, scope(), migrations = configAtV2())
            migrationAware.close()

            assertEquals(2, rawServiceVersions(path)!![THING_SERVICE_ID])
            assertContentEquals(bytesBefore, rawRowBytes(path, 800L))
            assertFalse(
                rawJournalSteps(path) != null && rawJournalSteps(path)!!.isNotEmpty(),
                "stamping must not journal any step",
            )
        }
}
