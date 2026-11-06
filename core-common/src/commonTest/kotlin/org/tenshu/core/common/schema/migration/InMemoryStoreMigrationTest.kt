package org.tenshu.core.common.schema.migration

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.schemaMigration
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import org.tenshu.core.schema.service as serviceSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The load-migrate-persist seam the iOS and JS stores use
 * (`core-schema-evolution.md` §8.9, E2j-3 capability half).
 *
 * ⚖️ These tests are load-bearing beyond their module: neither twin's own
 * toolchain runs in the build sandbox (no Xcode; karma's fetch is proxy-blocked),
 * so this is where their migration *logic* is actually verified. What stays
 * unverified there is only the persist step — writing bytes — which is why the
 * callers wrap this in a fail-closed `runCatching`.
 */
class InMemoryStoreMigrationTest {

    private fun migrationsFor(registry: SchemaRegistry) = StoreSchemaMigrations(
        context = createTestContext(registry),
        schemas = registry,
        deviceId = "in-memory-migration-test",
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

    /** v2 declared but NO chain registered ⇒ floor == 2, so a v1 store is unreadable. */
    private fun configAtV2WithoutChain() = migrationsFor(
        createMigrationTestRegistry().apply {
            registerConfig(tModule {
                service(serviceSchema(id = THING_SERVICE_ID, version = 2))
            })
        }
    )

    /** A THING row as the v1 snapshot wrote it: [title, legacy, size-as-string]. */
    private fun v1ThingRow(hash: Long, title: String = "alpha") = DbEntry(
        type = THING_ID,
        dataIdHash = hash,
        fields = listOf(
            DbValue.StringValue(title),
            DbValue.StringValue("junk"),
            DbValue.StringValue("42"),
        ),
        properties = mapOf("some/property" to DbValue.StringValue("kept")),
    )

    private fun untouchedRow(hash: Long) = DbEntry(
        type = "tenshu/test/migration/untouched",
        dataIdHash = hash,
        fields = listOf(DbValue.StringValue("keep-me"), DbValue.LongValue(7L)),
        properties = emptyMap(),
    )

    @Test
    fun v1RowsComeBackInTheV2ShapeAndUntouchedRowsAreIdentical() {
        val result = InMemoryStoreMigration(configAtV2()).run(
            rows = mapOf(100L to v1ThingRow(100L), 200L to untouchedRow(200L)),
            recordedVersions = mapOf(THING_SERVICE_ID to 1),
        )

        // v2 positional shape: [name(rename), kind(derive), size(convert
        // string→int), note(new optional → absent, never defaulted)].
        assertEquals(
            listOf<DbValue>(
                DbValue.StringValue("alpha"),
                DbValue.StringValue("kind:alpha"),
                DbValue.LongValue(42L),
                DbValue.NullValue,
            ),
            result.rows.getValue(100L).fields,
        )
        // Relation/property values outside the migrated field list are preserved.
        assertEquals(
            mapOf<String, DbValue>("some/property" to DbValue.StringValue("kept")),
            result.rows.getValue(100L).properties,
        )
        assertEquals(untouchedRow(200L), result.rows.getValue(200L))
        assertEquals(2, result.serviceVersions[THING_SERVICE_ID])
        assertFalse(result.wiped)
        assertTrue(result.changedRows > 0)
    }

    /**
     * `dropProperty` (CR-2 B6): a retired named-map slot leaves every stored row of its type — on
     * top of the step's composite rewrite for a migrated type, and on a type the step does not
     * otherwise migrate — while every other property, and every other type's rows, stay verbatim.
     */
    @Test
    fun aDroppedPropertyLeavesEveryRowOfItsTypeAndNothingElse() {
        val untouchedType = "tenshu/test/migration/untouched"
        val config = migrationsFor(
            createMigrationTestRegistry().apply {
                registerConfig(tModule {
                    service(serviceSchema(id = THING_SERVICE_ID, version = 2))
                    schemaMigration(
                        SchemaMigration(
                            serviceId = THING_SERVICE_ID,
                            fromVersion = 1,
                            migrations = thingMigrationStep().migrations,
                            droppedProperties = schemaMigration(THING_SERVICE_ID, fromVersion = 1) {
                                dropProperty(THING_ID, "some/property")
                                dropProperty(untouchedType, "retired/root")
                            }.droppedProperties,
                        )
                    )
                })
            }
        )
        val other = untouchedRow(300L).copy(
            properties = mapOf(
                "retired/root" to DbValue.StringValue("r"),
                "live/value" to DbValue.StringValue("v"),
            ),
        )

        val result = InMemoryStoreMigration(config).run(
            rows = mapOf(
                100L to v1ThingRow(100L),
                200L to untouchedRow(200L),
                300L to other,
            ),
            recordedVersions = mapOf(THING_SERVICE_ID to 1),
        )

        assertEquals(emptyMap(), result.rows.getValue(100L).properties)
        assertEquals(DbValue.StringValue("alpha"), result.rows.getValue(100L).fields.first(), "the rewrite still applies")
        assertEquals(mapOf<String, DbValue>("live/value" to DbValue.StringValue("v")), result.rows.getValue(300L).properties)
        assertEquals(untouchedRow(200L), result.rows.getValue(200L), "a row without the slot is not rewritten")
        assertEquals(2, result.serviceVersions[THING_SERVICE_ID])
    }

    @Test
    fun aNoDriftStoreComesBackUnchangedAndReportsNothingToPersist() {
        val rows = mapOf(200L to untouchedRow(200L))

        val result = InMemoryStoreMigration(configAtV2()).run(
            rows = rows,
            recordedVersions = mapOf(THING_SERVICE_ID to 2),
        )

        assertEquals(rows, result.rows)
        assertEquals(0, result.changedRows, "a no-drift open must be persist-free")
        assertFalse(result.wiped)
    }

    @Test
    fun anAbsentVersionRecordStampsCurrentAndTouchesNoRows() {
        // The pre-E2b posture: the format gate already judged the store, so its
        // rows are current-shaped even though nothing recorded a vector.
        val rows = mapOf(100L to v1ThingRow(100L))

        val result = InMemoryStoreMigration(configAtV2()).run(rows, recordedVersions = null)

        assertEquals(rows, result.rows)
        assertEquals(0, result.changedRows)
        assertEquals(2, result.serviceVersions[THING_SERVICE_ID])
        assertFalse(result.wiped)
    }

    @Test
    fun aBelowFloorServiceWipesRatherThanMisreadingItsRows() {
        val result = InMemoryStoreMigration(configAtV2WithoutChain()).run(
            rows = mapOf(100L to v1ThingRow(100L), 200L to untouchedRow(200L)),
            recordedVersions = mapOf(THING_SERVICE_ID to 1),
        )

        assertTrue(result.wiped)
        assertEquals(emptyMap(), result.rows)
        assertEquals(2, result.serviceVersions[THING_SERVICE_ID], "wipe re-stamps current")
    }

    @Test
    fun aStoreWrittenByNewerCodeWipes() {
        val result = InMemoryStoreMigration(configAtV2()).run(
            rows = mapOf(200L to untouchedRow(200L)),
            recordedVersions = mapOf(THING_SERVICE_ID to 3),
        )

        assertTrue(result.wiped)
        assertEquals(emptyMap(), result.rows)
    }

    @Test
    fun theInputMapIsNeverMutated() {
        // The callers persist `result.rows`; if `run` aliased the input, a
        // fail-closed caller that discarded the result would still have lost
        // its rows. Structural, but worth pinning.
        val input = mapOf(100L to v1ThingRow(100L))

        InMemoryStoreMigration(configAtV2()).run(input, mapOf(THING_SERVICE_ID to 1))

        assertEquals(v1ThingRow(100L).fields, input.getValue(100L).fields)
    }
}
