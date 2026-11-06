package org.tenshu.file.proxy.integration


import org.tenshu.core.collection.list.Drop
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.file.File
import org.tenshu.core.get
import org.tenshu.core.number.toTInteger
import org.tenshu.file.test.createFileProxy
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ListDropTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        testProxy = createFileProxy()
    }

    @Test
    fun dropReturnsCorrectNumberOfElements() {
        val files = listOf(
            File.create(
                context = testProxy.tContext,
                path = "/test1",
            ),
            File.create(
                context = testProxy.tContext,
                path = "/test2",
            ),
            File.create(
                context = testProxy.tContext,
                path = "/test3",
            )
        )

        val caller = TListDto(files)

        val result = testProxy.executionContext.execute<TList<File>>(
            operationId = Drop.ID,
            caller = caller,
            params = mapOf(
                Drop.Params.N to 1.toTInteger()
            )
        )
        val resultList = result.toList()

        assertEquals(2, resultList.size)
        assertEquals("/test2", resultList[0].path.get())
        assertEquals("/test3", resultList[1].path.get())
    }

    @Test
    fun dropMoreThanAvailableReturnsEmptyList() {
        val files = listOf(
            File.create(
                context = testProxy.tContext,
                path = "/test1",
            )
        )

        val caller = TListDto(files)

        val result = testProxy.executionContext.execute<TList<File>>(
            operationId = Drop.ID,
            caller = caller,
            params = mapOf(
                Drop.Params.N to 5.toTInteger()
            )
        )
        val resultList = result.toList()

        assertEquals(0, resultList.size)
    }

    @Test
    fun dropZeroReturnsAllElements() {
        val files = listOf(
            File.create(
                context = testProxy.tContext,
                path = "/test1",
            )
        )

        val caller = TListDto(files)

        val result = testProxy.executionContext.execute<TList<File>>(
            operationId = Drop.ID,
            caller = caller,
            params = mapOf(
                Drop.Params.N to 0.toTInteger()
            )
        )
        val resultList = result.toList()

        assertEquals(1, resultList.size)
        assertEquals("/test1", resultList[0].path.get())
    }
}
