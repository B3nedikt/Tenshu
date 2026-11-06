package org.tenshu.core.common.schema.migration

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.common.createTestContext
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.toTString
import org.tenshu.core.number.toTInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Forced-accounting rules of the declarative field-mapping builder
 * (`core-schema-evolution.md` §8.4): every rejection names the offending
 * field id, and the generated `migrate` maps by id, never by position.
 */
class SchemaMigrationBuilderTest {

    private val simpleId = "tenshu/test/migration/simple"
    private val fieldA = "$simpleId/a"
    private val fieldB = "$simpleId/b"
    private val fieldF = "$simpleId/f"

    private fun failsResolution(
        expectedFieldInMessage: String,
        block: SchemaMigrationBuilder.() -> Unit,
    ) {
        val failure = assertFailsWith<IllegalStateException> {
            schemaMigration(THING_SERVICE_ID, fromVersion = 1, block)
        }
        assertTrue(
            failure.message!!.contains(expectedFieldInMessage),
            "expected the offending field '$expectedFieldInMessage' in: ${failure.message}"
        )
    }

    @Test
    fun unaccountedFromOnlyFieldFailsResolution() {
        val from = composite(simpleId, fields = { string(fieldA); string(fieldB) })
        val to = composite(simpleId, fields = { string(fieldA) })
        failsResolution(fieldB) {
            migration(simpleId, from, to)
        }
    }

    @Test
    fun requiredToOnlyFieldWithoutDeriveFailsResolution() {
        val from = composite(simpleId, fields = { string(fieldA) })
        val to = composite(simpleId, fields = { string(fieldA); string(fieldB) })
        failsResolution(fieldB) {
            migration(simpleId, from, to)
        }
    }

    @Test
    fun optionalToOnlyFieldNeedsNoDeclaration() {
        val from = composite(simpleId, fields = { string(fieldA) })
        val to = composite(simpleId, fields = { string(fieldA); string(fieldB, isOptional = true) })
        val step = schemaMigration(THING_SERVICE_ID, fromVersion = 1) {
            migration(simpleId, from, to)
        }
        assertEquals(1, step.migrations.size)
    }

    @Test
    fun retypeWithoutConvertFailsResolution() {
        val from = composite(simpleId, fields = { string(fieldF) })
        val to = composite(simpleId, fields = { int(fieldF) })
        failsResolution(fieldF) {
            migration(simpleId, from, to)
        }
    }

    @Test
    fun retypeWithConvertResolves() {
        val from = composite(simpleId, fields = { string(fieldF) })
        val to = composite(simpleId, fields = { int(fieldF) })
        val step = schemaMigration(THING_SERVICE_ID, fromVersion = 1) {
            migration(simpleId, from, to) {
                convert(fieldF) { oldValue, ctx ->
                    (oldValue as TString).get().toInt().toTInteger(ctx.context)
                }
            }
        }
        assertEquals(simpleId, step.migrations.single().typeId)
    }

    @Test
    fun renameWithMissingSourceFailsResolution() {
        val from = composite(simpleId, fields = { string(fieldA) })
        val to = composite(simpleId, fields = { string(fieldB) })
        failsResolution("$simpleId/nope") {
            migration(simpleId, from, to) {
                rename("$simpleId/nope", fieldB)
            }
        }
    }

    @Test
    fun renameWithMissingTargetFailsResolution() {
        val from = composite(simpleId, fields = { string(fieldA) })
        val to = composite(simpleId, fields = { string(fieldB) })
        failsResolution("$simpleId/gone") {
            migration(simpleId, from, to) {
                rename(fieldA, "$simpleId/gone")
            }
        }
    }

    @Test
    fun dropOfSharedIdFailsResolution() {
        val from = composite(simpleId, fields = { string(fieldA) })
        val to = composite(simpleId, fields = { string(fieldA) })
        failsResolution(fieldA) {
            migration(simpleId, from, to) {
                drop(fieldA)
            }
        }
    }

    @Test
    fun happyPathWithDropDeriveRenameConvertResolves() {
        val step = thingMigrationStep()
        val migration = step.migrations.single()
        assertEquals(THING_ID, migration.typeId)
        assertEquals(THING_SERVICE_ID, step.serviceId)
        assertEquals(1, step.fromVersion)
    }

    @Test
    fun generatedMigrateAppliesAllRulesAndLeavesOptionalAbsent() {
        val registry = createMigrationTestRegistry()
        val tContext = createTestContext(registry)
        val migrationContext = MigrationContext(tContext, deviceId = "device-under-test")

        val old = SnapshotComposite(
            THING_AT_V1,
            listOf(
                "alpha".toTString(tContext),
                "junk".toTString(tContext),
                "42".toTString(tContext),
            )
        )

        val migrated = thingMigrationStep().migrations.single()
            .migrate(old, migrationContext) as Composite

        assertEquals("alpha", migrated.getField<TString>(ThingFields.NAME).get())
        assertEquals("kind:alpha", migrated.getField<TString>(ThingFields.KIND).get())
        assertEquals(42L, migrated.getField<TInteger>(ThingFields.SIZE).get())
        // The new optional field starts ABSENT — never defaulted (§8.4).
        assertNull(migrated.getOptionalField(ThingFields.NOTE))
    }

    @Test
    fun identityAutoCopyMapsByIdAcrossReorderedPositions() {
        val registry = createMigrationTestRegistry()
        val tContext = createTestContext(registry)
        val migrationContext = MigrationContext(tContext, deviceId = "device-under-test")

        val step = schemaMigration(THING_SERVICE_ID, fromVersion = 1) {
            // Same ids, swapped positions — nothing to declare.
            migration(REORDERED_ID, from = REORDERED_AT_V1, to = REORDERED_LIVE)
        }
        val old = SnapshotComposite(
            REORDERED_AT_V1,
            listOf("x".toTString(tContext), 7.toTInteger(tContext)),
        )

        val migrated = step.migrations.single().migrate(old, migrationContext) as Composite

        assertEquals("x", migrated.getField<TString>(ReorderedFields.A).get())
        assertEquals(7L, migrated.getField<TInteger>(ReorderedFields.B).get())
    }

    @Test
    fun dropTypeMigrateReturnsNull() {
        val step = schemaMigration(THING_SERVICE_ID, fromVersion = 1) {
            dropType(typeId = THING_ID, from = THING_AT_V1)
        }
        val tContext = createTestContext(createMigrationTestRegistry())
        val old = SnapshotComposite(
            THING_AT_V1,
            listOf(
                "a".toTString(tContext),
                "b".toTString(tContext),
                "1".toTString(tContext),
            )
        )
        assertNull(
            step.migrations.single().migrate(old, MigrationContext(tContext, "device-under-test"))
        )
    }

    @Test
    fun deriveReturningNullForRequiredFieldFailsAtMigrate() {
        val from = composite(simpleId, fields = { string(fieldA) })
        val to = composite(simpleId, fields = { string(fieldA); string(fieldB) })
        val step = schemaMigration(THING_SERVICE_ID, fromVersion = 1) {
            migration(simpleId, from, to) {
                derive(fieldB) { _, _ -> null }
            }
        }
        val tContext = createTestContext(createMigrationTestRegistry())
        val old = SnapshotComposite(from, listOf("v".toTString(tContext)))
        val failure = assertFailsWith<IllegalStateException> {
            step.migrations.single().migrate(old, MigrationContext(tContext, "device-under-test"))
        }
        assertTrue(failure.message!!.contains(fieldB))
    }
}
