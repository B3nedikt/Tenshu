package org.tenshu.core.common

import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DataIdSerializationTest {

    private lateinit var tContext: TContext

    @BeforeTest
    fun setup() {
        tContext = createTestContext()
    }

    @Test
    fun stringDataIdSerializationIsWorking() {
        val testRegistry = createCoreTestRegistry()
        val serializer = BinarySerializer()
        val parser = BinaryParser(testRegistry, testRegistry)

        val dataId =
            DataId.StringDataId.create(context = tContext, type = "test", keyValue = "id123")

        val bytes = serializer.serialize(dataId)
        val deserialized = parser.parse(bytes) as DataId.StringDataId

        assertEquals(dataId.type, deserialized.type)
        assertEquals(dataId.keyValue, deserialized.keyValue)
    }

    @Test
    fun longDataIdSerializationIsWorking() {
        val testRegistry = createCoreTestRegistry()
        val serializer = BinarySerializer()
        val parser = BinaryParser(testRegistry, testRegistry)

        val dataId = DataId.LongDataId.create(context = tContext, type = "test", keyValue = 12345L)

        val bytes = serializer.serialize(dataId)
        val deserialized = parser.parse(bytes) as DataId.LongDataId

        assertEquals(dataId.type, deserialized.type)
        assertEquals(dataId.keyValue, deserialized.keyValue)
    }
}
