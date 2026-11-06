package org.tenshu.core.common.parser

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.number.TInteger
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BinaryParserTest {

    private val registry = createSyntheticTestRegistry()
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun parseSimpleValue() {
        val original = "hello".toTString(context)
        val bytes = serializer.serialize(original)
        val parsed = parser.parse(bytes) as TString

        assertEquals(original.get(), parsed.get())
    }

    @Test
    fun parseComposite() {
        val original = SyntheticTestData.Factory.create(context, "test", 456L)
        val bytes = serializer.serialize(original)
        val parsed = parser.parse(bytes) as SyntheticTestData

        assertEquals(original.name.get(), parsed.name.get())
        assertEquals(original.value.get(), parsed.value.get())
    }

    @Test
    fun parseTList() {
        val original = context.dataFactory().createList<TString>(
            listOf(
                "item1".toTString(context),
                "item2".toTString(context)
            )
        )
        val bytes = serializer.serialize(original)
        val parsed = parser.parse(bytes) as TList<*>

        assertEquals(2, parsed.toList().size)
        assertEquals("item1", (parsed.toList()[0] as TString).get())
        assertEquals("item2", (parsed.toList()[1] as TString).get())
    }

    @Test
    fun parseResultSuccess() {
        val original = TResult.Success.create(context, "success".toTString(context))
        val bytes = serializer.serialize(original)
        val parsed = parser.parse(bytes) as TResult.Success<*>

        assertEquals("success", (parsed.value as TString).get())
    }

    @Test
    fun parseEntity() {
        val base = SyntheticTestData.Factory.create(context, "entity", 789L)

        // Ensure schema is reachable
        val schema = context.schemaProvider().getSchema(SyntheticTestData.ID)
        assertNotNull(schema, "Synthetic schema not found")

        val original = Entity.create<SyntheticTestData>(context, base)
        val bytes = serializer.serialize(original)
        val parsed = parser.parse(bytes) as Entity<*>

        assertEquals(SyntheticTestData.ID, parsed.type.get())

        // Use generic getFieldValue with correct IDs
        val nameValue =
            parsed.getFieldValue<TString>(SyntheticTestData.Companion.Field.NAME)
        assertEquals("entity", nameValue.get())

        val intValue = parsed.getFieldValue<TInteger>(SyntheticTestData.Companion.Field.VALUE)
        assertEquals(789L, intValue.get())
    }

    /**
     * When a `Select { relations(File.Data) }`
     * over an `OnDemand`-marked relation leaves a `PendingOnDemandProperty`
     * placeholder in the projected entity, and the entity then crosses an
     * IPC boundary (e.g. Control proxy → Drive app), the binary parser on
     * the receiver side must be able to decode the placeholder. A
     * `PendingOnDemandProperty.Schema` with no fields, or not registered with
     * the parser, crashes receivers with
     * `No type id registered for bytes: c6b3690829f132ef…`.
     */
    @Test
    fun parsePendingOnDemandProperty() {
        val entityId = org.tenshu.core.entity.DataId.StringDataId.create(
            context = context,
            type = SyntheticTestData.ID,
            keyValue = "test-key"
        )
        val original = org.tenshu.core.common.proxy.PendingOnDemandProperty.create(
            context = context,
            entityId = entityId,
            propertyId = "test/onDemandProperty",
            params = null
        )

        val bytes = serializer.serialize(original)
        val parsed = parser.parse(bytes) as org.tenshu.core.common.proxy.PendingOnDemandProperty

        assertEquals("test/onDemandProperty", parsed.propertyId.get())
        val parsedEntityId = parsed.entityId as org.tenshu.core.entity.DataId.StringDataId
        assertEquals(SyntheticTestData.ID, parsedEntityId.type.get())
        assertEquals("test-key", parsedEntityId.keyValue.get())
    }

    // ---- Optional value-field wire contract (pins the null-optional parser desync) --------
    //
    // Feed the parser HAND-CRAFTED bytes (independent of the serializer) so a same-direction
    // drift of both sides can't hide a bug. Each optional value field carries an 8-byte leading
    // marker: 0 = absent, else the byte length of the value. See [OptionalFieldFixture].

    private val optionalRegistry = OptionalFieldFixture.registry()
    private val optionalParser = BinaryParser(optionalRegistry, optionalRegistry)

    @Test
    fun parseHandCraftedNullMiddleOptional() {
        // Explicit 8-byte zero marker where the middle int? (b) is absent — the shape that
        // desyncs a parser ignoring the marker.
        val crafted = idToUuid(OptionalFieldFixture.ID) +
            6L.toByteArray() + "before".encodeToByteArray() +
            0L.toByteArray() +                                  // b absent
            5L.toByteArray() + "after".encodeToByteArray() +
            0L.toByteArray() +                                  // d absent
            0L.toByteArray()                                    // e absent
        val parsed = optionalParser.parse(crafted, expectedType = null) as Composite

        assertEquals("before", parsed.getField<TString>(OptionalFieldFixture.F.A).get())
        assertNull(parsed.getOptionalField<TInteger>(OptionalFieldFixture.F.B), "absent int must parse to null")
        assertEquals("after", parsed.getField<TString>(OptionalFieldFixture.F.C).get())
        assertNull(parsed.getOptionalField<TBoolean>(OptionalFieldFixture.F.D))
        assertNull(parsed.getOptionalField<TInteger>(OptionalFieldFixture.F.E))
    }

    @Test
    fun parseHandCraftedPresentZeroOptional_isNotNull() {
        // marker 8 ++ value 0L for each present optional — must decode to 0 / false, not null.
        val crafted = idToUuid(OptionalFieldFixture.ID) +
            1L.toByteArray() + "x".encodeToByteArray() +
            8L.toByteArray() + 0L.toByteArray() +               // b present, value 0
            1L.toByteArray() + "y".encodeToByteArray() +
            8L.toByteArray() + 0L.toByteArray() +               // d present, false
            8L.toByteArray() + 0L.toByteArray()                 // e present, value 0
        val parsed = optionalParser.parse(crafted, expectedType = null) as Composite

        assertEquals("x", parsed.getField<TString>(OptionalFieldFixture.F.A).get())
        assertNotNull(parsed.getOptionalField<TInteger>(OptionalFieldFixture.F.B), "present int 0 must not be null")
        assertEquals(0L, parsed.getOptionalField<TInteger>(OptionalFieldFixture.F.B)?.get())
        assertEquals("y", parsed.getField<TString>(OptionalFieldFixture.F.C).get())
        assertNotNull(parsed.getOptionalField<TBoolean>(OptionalFieldFixture.F.D), "present boolean false must not be null")
        assertEquals(false, parsed.getOptionalField<TBoolean>(OptionalFieldFixture.F.D)?.get())
        assertEquals(0L, parsed.getOptionalField<TInteger>(OptionalFieldFixture.F.E)?.get())
    }
}
