package org.tenshu.core.common.parser

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.core.dataFactory
import org.tenshu.core.id
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertContentEquals

class BinarySerializerTest {

    private val registry = createSyntheticTestRegistry()
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()

    @Test
    fun serializeSimpleValue() {
        val data = "test".toTString(context)
        val result = serializer.serialize(data)

        // Type UUID (16) + Length (8) + Data
        val expected = idToUuid(data.id) + 4L.toByteArray() + "test".encodeToByteArray()
        assertContentEquals(expected, result)
    }

    @Test
    fun serializeComposite() {
        val data = SyntheticTestData.Factory.create(context, "test", 123L)
        val result = serializer.serialize(data)

        // SyntheticTestData ID (16) 
        // + name: String (length header + data)
        // + value: Long (fixed 8 bytes, no header)
        val expected = idToUuid(SyntheticTestData.ID) +
                4L.toByteArray() + "test".encodeToByteArray() +
                123L.toByteArray()

        assertContentEquals(expected, result)
    }

    @Test
    fun serializeTList() {
        val data = context.dataFactory().createList<Data>(
            listOf(
                "a".toTString(context),
                "b".toTString(context)
            )
        )

        val result = serializer.serialize(data)

        // Root List Rule: ID (16) + elements (each with ID + content)
        // NO outer length header for root lists.
        val elementA = idToUuid("tenshu/core/string") + 1L.toByteArray() + "a".encodeToByteArray()
        val elementB = idToUuid("tenshu/core/string") + 1L.toByteArray() + "b".encodeToByteArray()
        val expected = idToUuid(TList.ID) + elementA + elementB

        assertContentEquals(expected, result)
    }

    @Test
    fun serializeNestedList() {
        // A Success result containing a list
        val list = context.dataFactory().createList<Data>(listOf("a".toTString(context)))
        val data = TResult.Success.create(context, list)

        val result = serializer.serialize(data)

        // Success ID (16)
        // + List ID (16)
        // + List Length (8)
        // + Element A
        val elementA = idToUuid("tenshu/core/string") + 1L.toByteArray() + "a".encodeToByteArray()

        // Correct nested list structure: TList ID (16) + Length (8) + Content
        val expectedList = idToUuid(TList.ID) + elementA.size.toLong().toByteArray() + elementA
        val expected = idToUuid(TResult.Success.ID) + expectedList

        assertContentEquals(expected, result)
    }

    // ---- Optional value-field wire contract (pins the null-optional parser desync) --------
    //
    // Every optional value field is length-prefixed: an 8-byte leading marker — 0 = absent,
    // else the byte length of the value that follows. A present 0 / false therefore serialises
    // with marker 8 (not 0) and can never alias the null marker. See [OptionalFieldFixture].

    private val optionalRegistry = OptionalFieldFixture.registry()
    private val optionalContext = createTestContext(optionalRegistry)
    private val optionalSerializer = BinarySerializer(optionalRegistry)

    @Test
    fun serializeNullOptionalValue_writesZeroMarker() {
        val data = OptionalFieldFixture.dto(optionalContext, a = "before", b = null, c = "after", d = null, e = null)
        val result = optionalSerializer.serialize(data, isTopLevel = true, expectedType = null)

        // type id (16)
        // + a: string  (len 6 ++ utf8)
        // + b: int?   null -> zero marker
        // + c: string  (len 5 ++ utf8)
        // + d: bool?  null -> zero marker
        // + e: int?   null -> zero marker
        val expected = idToUuid(OptionalFieldFixture.ID) +
            6L.toByteArray() + "before".encodeToByteArray() +
            0L.toByteArray() +
            5L.toByteArray() + "after".encodeToByteArray() +
            0L.toByteArray() +
            0L.toByteArray()
        assertContentEquals(expected, result)
    }

    @Test
    fun serializePresentOptionalValue_lengthPrefixesIt_soZeroIsNotNull() {
        val data = OptionalFieldFixture.dto(optionalContext, a = "x", b = 0L, c = "y", d = false, e = 0L)
        val result = optionalSerializer.serialize(data, isTopLevel = true, expectedType = null)

        // present int 0 / false -> marker 8 ++ value 0L (never a bare 0 that aliases the null marker)
        val expected = idToUuid(OptionalFieldFixture.ID) +
            1L.toByteArray() + "x".encodeToByteArray() +
            8L.toByteArray() + 0L.toByteArray() +   // b: int 0
            1L.toByteArray() + "y".encodeToByteArray() +
            8L.toByteArray() + 0L.toByteArray() +   // d: false
            8L.toByteArray() + 0L.toByteArray()     // e: int 0
        assertContentEquals(expected, result)
    }
}
