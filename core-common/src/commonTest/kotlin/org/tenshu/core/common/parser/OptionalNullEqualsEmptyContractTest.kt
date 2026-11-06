package org.tenshu.core.common.parser

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.tModule
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.list
import org.tenshu.core.schema.string
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the wire contract the project **accepted permanently** on 2026-10-29
 * (`protocol-improvements.md`, "Null vs. Empty"; `core-common/binary-protocol.md` §3.4): for an
 * **optional** variable-length field, an empty value and null are the same bytes (an 8-byte zero
 * marker), and the parser reads both back as **null**.
 *
 * ⚠ This is a guard on purpose, not a wish: a schema that needs "empty" to differ from "absent"
 * must not lean on an optional field (`docs/schema-dsl.md`, "Optional fields"). If the protocol
 * ever gains a presence prefix, this test is the one that should change, with a core format bump.
 */
class OptionalNullEqualsEmptyContractTest {

    private object Fixture {
        const val ID = "test/optionalNullEqualsEmpty"
        const val S = "$ID/s" // string?    (middle)
        const val B = "$ID/b" // byteArray? (middle)
        const val L = "$ID/l" // list<string>? (middle)
        const val TAIL = "$ID/tail" // string, mandatory — proves the optionals did not desync it

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(S, isOptional = true)
                byteArray(B, isOptional = true)
                list(L, of = TString.Schema, isOptional = true)
                string(TAIL)
            }
        )

        class Dto(fieldValues: List<Data?>) : CompositeDto(fieldValues), Composite {
            override val schema: CompositeSchema get() = Schema
        }
    }

    private val registry = createSyntheticTestRegistry().also { registry ->
        registry.registerConfig(
            tModule { composite(interfaceClass = Fixture.Dto::class, dataSchema = Fixture.Schema, dtoFactory = Fixture::Dto) }
        )
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer(registry)
    private val parser = BinaryParser(registry, registry)
    private val expectedType = Type.create(baseSchema = Fixture.Schema)

    private fun dto(s: String?, b: ByteArray?, l: List<String>?) = Fixture.Dto(
        listOf(
            s?.toTString(context),
            b?.toTByteArray(context),
            l?.let { SimpleTList(it.map { item -> item.toTString(context) }) },
            "tail".toTString(context),
        )
    )

    private fun roundTrips(data: Composite): List<Composite> = listOf(
        parser.parse(serializer.serialize(data, isTopLevel = true, expectedType = null), expectedType = null) as Composite,
        parser.parse(serializer.serialize(data, isTopLevel = true, expectedType = expectedType), expectedType = expectedType) as Composite,
    )

    @Test
    fun anEmptyOptionalValue_isTheSameBytesAsNull() {
        val empty = serializer.serialize(dto("", ByteArray(0), emptyList()), isTopLevel = true, expectedType = null)
        val absent = serializer.serialize(dto(null, null, null), isTopLevel = true, expectedType = null)
        assertContentEquals(absent, empty)
    }

    @Test
    fun anEmptyOptionalValue_readsBackAsNull_andTheFollowingFieldSurvives() {
        for (parsed in roundTrips(dto("", ByteArray(0), emptyList()))) {
            assertNull(parsed.getOptionalField<TString>(Fixture.S), "an empty optional string reads back as null")
            assertNull(parsed.getOptionalField<TByteArray>(Fixture.B), "an empty optional byte array reads back as null")
            assertNull(parsed.getOptionalField<TList<TString>>(Fixture.L), "an empty optional list reads back as null")
            assertEquals("tail", parsed.getField<TString>(Fixture.TAIL).get())
        }
    }

    @Test
    fun aNonEmptyOptionalValue_roundTrips() {
        for (parsed in roundTrips(dto("x", byteArrayOf(7), listOf("a", "b")))) {
            assertEquals("x", parsed.getOptionalField<TString>(Fixture.S)?.get())
            assertContentEquals(byteArrayOf(7), parsed.getOptionalField<TByteArray>(Fixture.B)?.get())
            assertEquals(listOf("a", "b"), parsed.getOptionalField<TList<TString>>(Fixture.L)?.toList()?.map { it.get() })
            assertEquals("tail", parsed.getField<TString>(Fixture.TAIL).get())
        }
    }
}
