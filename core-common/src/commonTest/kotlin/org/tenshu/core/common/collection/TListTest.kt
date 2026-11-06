package org.tenshu.core.common.collection

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.impl.EntityDto
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.impl.reference.CompositeTypeReferenceDto
import org.tenshu.core.entity.Entity
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TListTest {

    interface TestData : Composite {
        companion object {
            const val ID = "test/data"
            val Schema = composite(
                id = ID,
                fields = {
                    composite("name")
                }
            )
        }

        override val schema: CompositeSchema get() = Schema
        val name: TString get() = getField("name")
    }

    class TestDataDto(fieldValues: List<Data?>) : CompositeDto(fieldValues), TestData {
        constructor(name: String) : this(listOf(name.toTString(null)))
    }

    private val item1 = TestDataDto("item1")
    private val item2 = TestDataDto("item2")
    private val item3 = "item3".toTString(null)
    private val boolTrue = true.toTBoolean(null)

    @Test
    fun `TenshuArrayDto can hold mixed data types`() {
        // WHEN: A mixed array is created
        val mixedArray = TListDto(listOf(item1, item2, item3, boolTrue))

        // THEN: The array contains the correct elements in order
        val list = mixedArray.toList()
        assertEquals(4, list.size)

        // Verify the first element
        val first = list[0]
        assertIs<TestData>(first)
        assertEquals("item1", first.name.get())

        // Verify the second element
        val second = list[1]
        assertIs<TestData>(second)
        assertEquals("item2", second.name.get())

        // Verify the third element
        val third = list[2]
        assertIs<TString>(third)
        assertEquals("item3", third.get())

        // Verify the fourth element
        val fourth = list[3]
        assertEquals(true, (fourth as org.tenshu.core.TBoolean).get())
    }

    @Test
    fun `TenshuArrayDto can be empty`() {
        // WHEN: An empty TenshuArrayDto is created
        val emptyArray = TListDto(emptyList<Data>())

        // THEN: The resulting list is empty and its size is 0
        assertTrue(emptyArray.toList().isEmpty())
        assertFalse(emptyArray.toList().isNotEmpty())
        assertEquals(0, emptyArray.toList().size)
    }

    @Test
    fun `contains and containsAll methods work correctly`() {
        // GIVEN: A mixed array and a separate entity
        val mixedArray = TListDto(listOf(item1, item2))
        val otherItem = TestDataDto("other")

        // THEN: contains() should return correct results
        assertTrue(mixedArray.toList().contains(item1))
        assertTrue(mixedArray.toList().contains(item2))
        assertFalse(mixedArray.toList().contains(otherItem))

        // THEN: containsAll() should return correct results
        assertTrue(mixedArray.toList().containsAll(listOf(item1, item2)))
        assertFalse(mixedArray.toList().containsAll(listOf(item1, otherItem)))
    }

    @Test
    fun `EntityDto can be part of the list`() {
        // GIVEN: An EntityDto
        val entity = EntityDto<TestData>(
            type = CompositeTypeReferenceDto(TestData.ID),
            fields = mapOf("name" to "entityName".toTString(null)),
            properties = emptyMap()
        )

        // WHEN: Added to a list
        val list = TListDto(listOf(entity))

        // THEN: It is correctly retrieved
        val retrieved = list.toList()[0]
        assertIs<Entity<TestData>>(retrieved)
        assertEquals(TestData.ID, retrieved.type.get())
    }
}
