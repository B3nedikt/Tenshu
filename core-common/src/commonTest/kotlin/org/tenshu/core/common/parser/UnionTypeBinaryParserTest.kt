package org.tenshu.core.common.parser

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.schema.Type
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnionTypeBinaryParserTest {

    private val registry = createSyntheticTestRegistry()
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer(registry)
    private val parser = BinaryParser(registry, registry)

    @Test
    fun parseUnionTypeResultSuccess() {
        val original = TResult.Success.create(context, "win".toTString(context))

        // Serialize with expected UnionSchema
        val expectedType = Type.create(baseSchema = TResult.Schema)
        val bytes = serializer.serialize(original, expectedType = expectedType)

        // Parse with expected UnionSchema
        val parsed = parser.parse(bytes, expectedType = expectedType) as TResult.Success<*>

        assertEquals("win", (parsed.value as TString).get())
    }

    @Test
    fun parseUnionTypeResultFailure() {
        val error = GenericError.create(context, "boom")
        val original = TResult.Failure.create<Data>(context, error)

        val expectedType = Type.create(baseSchema = TResult.Schema)
        val bytes = serializer.serialize(original, expectedType = expectedType)
        val parsed = parser.parse(bytes, expectedType = expectedType) as TResult.Failure<*>

        assertEquals("boom", (parsed.error as GenericError).message.get())
    }

    @Test
    fun testTagOmissionForTypedList() {
        val original = context.dataFactory().createList<TString>(
            listOf("a".toTString(context), "b".toTString(context))
        )

        val listType = Type.create(baseSchema = TList.Schema)

        // Serialize with expected type -> should omit TList.ID tag
        val bytes = serializer.serialize(original, expectedType = listType)

        val legacyBytes = serializer.serialize(original) // No expectedType
        assertEquals(
            expected = bytes.size + 16,
            actual = legacyBytes.size,
            message = "Optimized version should be 16 bytes smaller"
        )

        val parsed = parser.parse(bytes, expectedType = listType) as TList<*>
        assertEquals(2, parsed.toList().size)
    }

    @Test
    fun testNestedTagOmission() {
        val innerList =
            context.dataFactory().createList<TString>(listOf("inner".toTString(context)))

        val result = TResult.Success.create(context, innerList)
        val resultType = TResult.type(itemType = Type.create(baseSchema = TList.Schema))

        val bytes = serializer.serialize(result, expectedType = resultType)

        val legacyBytes = serializer.serialize(result)
        // Legacy: TResult.Success.ID (16) + TList.ID (16) + LEN (8) + element
        // Optimized: TResult.Success.ID (16) + LEN (8) + element

        assertEquals(
            expected = bytes.size + 16,
            actual = legacyBytes.size,
            message = "Nested list tag should be omitted in optimized mode"
        )

        val parsed = parser.parse(bytes, expectedType = resultType) as TResult.Success<*>
        val parsedList = parsed.value as TList<*>
        assertEquals(1, parsedList.toList().size)
    }

    @Test
    fun testUnionSchemaDispatch() {
        val original = TResult.Success.create(context, "union".toTString(context))

        // Expected type using the formal UnionSchema
        val expectedType = Type.create(baseSchema = TResult.Schema)

        val bytes = serializer.serialize(original, expectedType = expectedType)
        val parsed = parser.parse(bytes, expectedType = expectedType) as TResult.Success<*>

        assertEquals("union", (parsed.value as TString).get())
    }

    @Test
    fun testSelectOperationWithUnionSchema() {
        val fieldId = org.tenshu.core.common.SyntheticTestData.Companion.Field.NAME
        val selector = Selector.FieldSelector.create<Data>(
            context,
            org.tenshu.core.schema.fieldRef(fieldId)
        )
        val original = context.dataFactory().createComposite<org.tenshu.core.Operation>(
            Select.ID,
            listOf(context.dataFactory().createList<Selector<Data>>(listOf(selector)))
        )

        // Expected type for Select operation
        val expectedType = Type.create(baseSchema = Select.Schema)

        val bytes = serializer.serialize(original, expectedType = expectedType)
        val parsed = parser.parse(bytes, expectedType = expectedType) as org.tenshu.core.Operation

        val parsedSelectors = parsed.params[Select.Params.SELECTORS] as TList<*>
        assertTrue(parsedSelectors.toList()[0] is Selector.FieldSelector<*>)
    }
}
