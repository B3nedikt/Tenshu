package org.tenshu.file.proxy.integration


import org.tenshu.core.EqualTo
import org.tenshu.core.TBoolean
import org.tenshu.core.common.TestProxy
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.Preview
import org.tenshu.core.get
import org.tenshu.core.toTByteArray
import org.tenshu.file.test.createFileProxy
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EqualToImplTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setUp() {
        testProxy = createFileProxy()
    }

    @Test
    fun equalToForEqualEntitiesReturnsTrue() {

        val fileEntity = Entity.create<File>(
            context = testProxy.tContext,
            composite = File.create(
                context = testProxy.tContext,
                path = "/test.jpg",
            ),
            properties = mapOf(
                File.Data.id to byteArrayOf(
                    1,
                    2
                ).toTByteArray(testProxy.tContext),
                File.Preview.id to byteArrayOf(
                    1,
                    2
                ).toTByteArray(testProxy.tContext)
            )
        )

        val result = testProxy.executionContext.execute<TBoolean>(
            operationId = EqualTo.ID,
            caller = fileEntity,
            params = mapOf(
                EqualTo.Params.OTHER to fileEntity
            )
        )
        assertTrue(result.get())
    }

    @Test
    fun equalToForDifferentEntitiesReturnsFalse() {

        val fileEntity1 = Entity.create<File>(
            context = testProxy.tContext,
            composite = File.create(
                context = testProxy.tContext,
                path = "/test.jpg",
            ),
            properties = mapOf(
                File.Data.id to byteArrayOf(
                    1,
                    2
                ).toTByteArray(testProxy.tContext),
                File.Preview.id to byteArrayOf(
                    1,
                    2
                ).toTByteArray(testProxy.tContext)
            )
        )

        val fileEntity2 = Entity.create<File>(
            context = testProxy.tContext,
            composite = File.create(
                context = testProxy.tContext,
                path = "/test.jpg",
            ),
            properties = mapOf(
                File.Data.id to byteArrayOf(
                    1,
                    2
                ).toTByteArray(testProxy.tContext),
                File.Preview.id to byteArrayOf(1).toTByteArray(testProxy.tContext)
            )
        )

        val result = testProxy.executionContext.execute<TBoolean>(
            operationId = EqualTo.ID,
            caller = fileEntity1,
            params = mapOf(
                EqualTo.Params.OTHER to fileEntity2
            )
        )
        assertFalse(result.get())
    }

    @Test
    fun equalToForDifferentEntitiesWithNoPropertiesReturnsFalse() {

        val fileEntity1 = File.create(
            context = testProxy.tContext,
            path = "/test.jpg",
        )

        val fileEntity2 = Entity.create<File>(
            context = testProxy.tContext,
            composite = File.create(
                context = testProxy.tContext,
                path = "/test.jpg",
            ),
            properties = mapOf(
                File.Data.id to byteArrayOf(
                    1,
                    2
                ).toTByteArray(testProxy.tContext),
                File.Preview.id to byteArrayOf(
                    1,
                    2
                ).toTByteArray(testProxy.tContext)
            )
        )

        val result = testProxy.executionContext.execute<TBoolean>(
            operationId = EqualTo.ID,
            caller = fileEntity1,
            params = mapOf(
                EqualTo.Params.OTHER to fileEntity2
            )
        )
        assertFalse(result.get())
    }

    @Test
    fun equalToForDifferentEntitiesWithNoPropertiesInSecondReturnsFalse() {

        val fileEntity1 = Entity.create<File>(
            context = testProxy.tContext,
            composite = File.create(
                context = testProxy.tContext,
                path = "/test.jpg",
            ),
            properties = mapOf(
                File.Data.id to byteArrayOf(
                    1,
                    2
                ).toTByteArray(testProxy.tContext),
                File.Preview.id to byteArrayOf(
                    1,
                    2
                ).toTByteArray(testProxy.tContext)
            )
        )

        val fileEntity2 = File.create(
            context = testProxy.tContext,
            path = "/test.jpg",
        )

        val result = testProxy.executionContext.execute<TBoolean>(
            operationId = EqualTo.ID,
            caller = fileEntity1,
            params = mapOf(
                EqualTo.Params.OTHER to fileEntity2
            )
        )
        assertFalse(result.get())
    }

    @Test
    fun equalToWorksForSameList() {

        val fileEntity1 = Entity.create<File>(
            context = testProxy.tContext,
            composite = File.create(
                context = testProxy.tContext,
                path = "/test.jpg",
            ),
            properties = mapOf(
                File.Data.id to byteArrayOf(
                    1,
                    2
                ).toTByteArray(testProxy.tContext),
                File.Preview.id to byteArrayOf(
                    1,
                    2
                ).toTByteArray(testProxy.tContext)
            )
        )

        val fileEntity2 = File.create(
            context = testProxy.tContext,
            path = "/test.jpg",
        )

        val list1 = testProxy.executionContext.context.dataFactory().createList<File>(
            listOf(
                fileEntity1,
                fileEntity2
            )
        )
        val list2 = testProxy.executionContext.context.dataFactory().createList<File>(
            listOf(
                fileEntity1,
                fileEntity2
            )
        )

        val result = testProxy.executionContext.execute<TBoolean>(
            operationId = EqualTo.ID,
            caller = list1,
            params = mapOf(
                EqualTo.Params.OTHER to list2
            )
        )
        assertTrue(result.get())
    }

    @Test
    fun equalToConsidersListOrder() {

        val fileEntity1 = Entity.create<File>(
            context = testProxy.tContext,
            composite = File.create(
                context = testProxy.tContext,
                path = "/test.jpg",
            ),
            properties = mapOf(
                File.Data.id to byteArrayOf(
                    1,
                    2
                ).toTByteArray(testProxy.tContext),
                File.Preview.id to byteArrayOf(
                    1,
                    2
                ).toTByteArray(testProxy.tContext)
            )
        )

        val fileEntity2 = File.create(
            context = testProxy.tContext,
            path = "/test.jpg",
        )

        val list1 = testProxy.executionContext.context
            .dataFactory()
            .createList<File>(listOf(fileEntity1, fileEntity2))

        val list2 = testProxy.executionContext.context
            .dataFactory()
            .createList<File>(listOf(fileEntity2, fileEntity1))

        val result = testProxy.executionContext.execute<TBoolean>(
            operationId = EqualTo.ID,
            caller = list1,
            params = mapOf(
                EqualTo.Params.OTHER to list2
            )
        )

        assertFalse(result.get())
    }
}
