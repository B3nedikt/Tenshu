package org.tenshu.identity.common

import org.tenshu.core.Data
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.toTString
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.timestamp
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.identity.RealmKeyGeneration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A `RealmKeyGeneration` row written before `revokedMembers` existed (four fields, on a dev store or from an older
 * dev build) must read as "revocations unknown": never throw, and never read as "nobody revoked". The key-hand-out
 * gate (`KeyHandOutPolicy`) refuses on null.
 */
class RealmKeyGenerationLegacyRowTest {

    private val registry = createCoreTestRegistry().also { it.registerConfig(identityServiceCommonModule) }
    private val context = createTestContext(registry)

    /** The shape before `revokedMembers`: same type id, four fields. */
    private val fourFieldSchema: CompositeSchema = composite(
        id = RealmKeyGeneration.ID,
        fields = {
            string(RealmKeyGeneration.Fields.COMPOSITE_KEY)
            string(RealmKeyGeneration.Fields.REALM_ID)
            string(RealmKeyGeneration.Fields.KEY_ID)
            timestamp(RealmKeyGeneration.Fields.GENERATION)
        },
    )

    private class FourFieldRow(private val bound: CompositeSchema, fields: List<Data?>) : CompositeDto(fields) {
        override val schema: CompositeSchema get() = bound
    }

    @Test
    fun aFourFieldRow_readsItsRevokedSetAsUnknown() {
        val legacy = RealmKeyGenerationDto(List(4) { null })
        assertNull(legacy.revokedMembers)
        assertNull(legacy.revokedMemberIds())
    }

    @Test
    fun aFourFieldRowOnTheWire_parsesIntoTheCurrentShape_withTheSetUnknown() {
        val bytes = BinarySerializer().serialize(
            FourFieldRow(
                fourFieldSchema,
                listOf(
                    "realm-1/key-1".toTString(context),
                    "realm-1".toTString(context),
                    "key-1".toTString(context),
                    UnixInstantDto(1_900_000_000_000L),
                ),
            ),
        )

        val parsed = BinaryParser(registry, registry).parse(bytes) as RealmKeyGeneration

        assertEquals("key-1", parsed.keyId.get())
        assertEquals(1_900_000_000_000L, parsed.generation.toEpochMilliseconds())
        assertNull(parsed.revokedMemberIds(), "an absent set is unknown, never empty")
    }

    @Test
    fun aCurrentRow_roundTripsItsRevokedSet() {
        val row = RealmKeyGeneration.create(context, "realm-1", "key-2", generation = 5L, revokedMembers = setOf("b0b", "ca7"))
        val parsed = BinaryParser(registry, registry).parse(BinarySerializer().serialize(row)) as RealmKeyGeneration
        assertEquals(setOf("b0b", "ca7"), parsed.revokedMemberIds())
    }
}
