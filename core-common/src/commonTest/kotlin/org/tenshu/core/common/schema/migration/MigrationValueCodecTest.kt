package org.tenshu.core.common.schema.migration

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.core.TContext
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * End-to-end bytes through the snapshot-keyed transform
 * (`core-schema-evolution.md` §8.4 "Dispatch"): real [BinarySerializer] bytes
 * produced under the frozen v1 snapshot in, migrated bytes that parse under
 * the LIVE registry out. Proves the overlay decode (positional decode under
 * the producing field list) and the live re-encode — the exact seam the
 * store-open (§8.1) and history-upcast (§8.2) slices will drive.
 */
class MigrationValueCodecTest {

    private lateinit var registry: org.tenshu.core.common.proxy.SchemaRegistry
    private lateinit var tContext: TContext
    private lateinit var migrationContext: MigrationContext

    @BeforeTest
    fun setup() {
        registry = createMigrationTestRegistry()
        tContext = createTestContext(registry)
        migrationContext = MigrationContext(tContext, deviceId = "device-under-test")
    }

    private fun v1ThingBytes(title: String, legacy: String, size: String): ByteArray =
        BinarySerializer(registry).serialize(
            SnapshotComposite(
                THING_AT_V1,
                listOf(
                    title.toTString(tContext),
                    legacy.toTString(tContext),
                    size.toTString(tContext),
                )
            )
        )

    @Test
    fun v1BytesMigrateToBytesThatParseUnderTheLiveRegistry() {
        val codec = MigrationValueCodec(thingMigrationStep(), registry, registry)

        val migratedBytes = codec.migrate(
            typeId = THING_ID,
            bytes = v1ThingBytes(title = "alpha", legacy = "junk", size = "42"),
            context = migrationContext,
        )!!

        val parsed = BinaryParser(registry, registry)
            .parse<Composite>(migratedBytes, Type.create(baseSchema = THING_LIVE))

        assertEquals("alpha", parsed.getField<TString>(ThingFields.NAME).get())
        assertEquals("kind:alpha", parsed.getField<TString>(ThingFields.KIND).get())
        assertEquals(42L, parsed.getField<TInteger>(ThingFields.SIZE).get())
        // New optional field: absent, not defaulted.
        assertNull(parsed.getOptionalField(ThingFields.NOTE))
    }

    @Test
    fun droppedTypeBytesMigrateToNullEvenWhenRetiredFromTheLiveRegistry() {
        // A type the LIVE registry does not know at all — the overlay must
        // resolve its 16-byte wire tag and schema from the frozen snapshot.
        val retiredId = "tenshu/test/migration/retired"
        val retiredAtV1 = composite(retiredId, fields = { string("$retiredId/x") })

        val step = schemaMigration(THING_SERVICE_ID, fromVersion = 1) {
            dropType(typeId = retiredId, from = retiredAtV1)
        }
        val codec = MigrationValueCodec(step, registry, registry)

        val oldBytes = BinarySerializer(registry).serialize(
            SnapshotComposite(retiredAtV1, listOf("gone".toTString(tContext)))
        )

        assertNull(codec.migrate(retiredId, oldBytes, migrationContext))
    }

    @Test
    fun untouchedTypesHaveNoMigrationInTheStep() {
        val codec = MigrationValueCodec(thingMigrationStep(), registry, registry)
        assertNull(codec.migrationFor(REORDERED_ID))
        assertEquals(THING_ID, codec.migrationFor(THING_ID)?.typeId)
    }
}
