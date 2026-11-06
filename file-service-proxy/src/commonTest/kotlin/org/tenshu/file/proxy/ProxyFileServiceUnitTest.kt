package org.tenshu.file.proxy


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.tenshu.core.EqualTo
import org.tenshu.core.IfEmptyError
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.collection.list.Search
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.file.Category
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import org.tenshu.core.file.path
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.file.system.UploadFile
import org.tenshu.file.test.createFileProxy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyFileServiceUnitTest {

    private fun createProxy(scope: CoroutineScope) = createFileProxy(scope)

    private fun assertSuccess(testProxy: TestProxy, result: ByteArray, label: String = "") {
        val type = result.sliceArray(0 until 16)
        if (!type.contentEquals(idToUuid(TResult.Success.ID))) {
            val error = testProxy.parser.parse<TResult<*>>(result)
            error("Operation failed at $label: $error")
        }
    }

    @Test
    fun binaryDeserializationIsWorking() = runTest {
        val testPath = "/hello"

        val testProxy = createProxy(backgroundScope)

        val request = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(CreateFolder.ID),
            *testPath.encodeToByteArray().size.toLong().toByteArray(),
            *testPath.encodeToByteArray()
        )

        val result = testProxy.handleRequest(request)
        assertSuccess(testProxy, result, "binary deserialization")

        assertEquals(
            expected = testPath,
            actual = (testProxy.database.getData(
                DataId.StringDataId.create(
                    context = testProxy.tContext,
                    type = File.ID,
                    keyValue = testPath
                )
            ) as Folder).path.get()
        )
    }

    @Test
    fun flowRequest_ReturnsCorrectResult() = runTest {

        val testProxy = createProxy(backgroundScope)

        val flowId = 2L

        // Wire layout per selector: FieldSelector = 32 B (tag + ref);
        // PropertySelector = 40 B (tag + ref + 8-byte null marker for the
        // optional nested-projection field, added by core-nested-projection.md P2.1).
        val request = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(FileSystem.Relations.ROOT),
            *idToUuid(Folder.Fields.FILES),
            *idToUuid(Select.ID),
            *(32 + 40 * 3).toLong().toByteArray(),
            *idToUuid(Selector.FieldSelector.ID), *idToUuid(File.Field.PATH),
            *idToUuid(Selector.PropertySelector.ID), *idToUuid(File.LastModifiedAt.id), *0L.toByteArray(),
            *idToUuid(Selector.PropertySelector.ID), *idToUuid(File.Size.id), *0L.toByteArray(),
            *idToUuid(Selector.PropertySelector.ID), *idToUuid(FileCategory.Schema.id.get()), *0L.toByteArray(),
            *idToUuid(Stream.ID),
            *flowId.toByteArray()
        )

        val result = testProxy.handleRequest(request)
        assertEquals(16, result.size.coerceAtMost(16), "Result too short")
        assertSuccess(testProxy, result, "basic flow")
    }

    @Test
    fun flowRequest_SendsNewDataToClientAfterDbUpdate() = runTest {

        val testProxy = createProxy(backgroundScope)

        val flowId = 2L

        val request = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(FileSystem.Relations.ROOT),
            *idToUuid(Folder.Fields.FILES),
            *idToUuid(Select.ID),
            *(32 + 40 * 4).toLong().toByteArray(),
            *idToUuid(Selector.FieldSelector.ID), *idToUuid(File.Field.PATH),
            *idToUuid(Selector.PropertySelector.ID), *idToUuid(File.LastModifiedAt.id), *0L.toByteArray(),
            *idToUuid(Selector.PropertySelector.ID), *idToUuid(File.Size.id), *0L.toByteArray(),
            *idToUuid(Selector.PropertySelector.ID), *idToUuid(FileCategory.Schema.id.get()), *0L.toByteArray(),
            *idToUuid(Selector.PropertySelector.ID), *idToUuid(File.Data.id), *0L.toByteArray(),
            *idToUuid(Stream.ID),
            *flowId.toByteArray()
        )

        assertSuccess(testProxy, testProxy.handleRequest(request), "start stream")

        // Send two files as update
        // ⚠ Real extensions since PH-1 (`photos-app.md`): `File.Category` is derived from the
        // name now, so what this asserts is the answer the extension implies.
        val testPath1 = "/test1.jpg"

        val createRequest = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(CreateFolder.ID),
            *testPath1.encodeToByteArray().size.toLong().toByteArray(),
            *testPath1.encodeToByteArray()
        )

        assertSuccess(testProxy, testProxy.handleRequest(createRequest), "create folder 1")

        val testPath2 = "/test2.txt"

        val createRequest2 = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(CreateFolder.ID),
            *testPath2.encodeToByteArray().size.toLong().toByteArray(),
            *testPath2.encodeToByteArray()
        )

        assertSuccess(testProxy, testProxy.handleRequest(createRequest2), "create folder 2")

        // Wait for asynchronous updates to arrive in TestProxy
        for (i in 1..100) {
            val count = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
                .count { it.streamId.get() == flowId }
            if (count >= 3) break
            delay(100)
        }

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }

        assertEquals(
            3,
            valueUpdates.size,
            "Updates: ${testProxy.updates}"
        ) // Initial empty + testPath1 + testPath2

        // First update (initial)
        @Suppress("UNCHECKED_CAST")
        val initialPayload = (valueUpdates[0].payload as TList<Entity<Folder>>).toList()
        assertTrue(initialPayload.isEmpty())

        // Second update (testPath1)
        @Suppress("UNCHECKED_CAST")
        val firstElementResult =
            (valueUpdates[1].payload as Entity<Folder>)
        assertEquals(testPath1, (firstElementResult as Entity<out File>).path.get())

        // Third update (testPath2)
        @Suppress("UNCHECKED_CAST")
        val secondElementResult =
            (valueUpdates[2].payload as Entity<Folder>)
        assertEquals(testPath2, (secondElementResult as Entity<out File>).path.get())
    }

    @Test
    fun flowRequest_SendsExistingDataToClient() = runTest {

        val testProxy = createProxy(backgroundScope)

        val rootId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = "/"
        )

        // ⚠ Real extensions since PH-1 (`photos-app.md`) — the category is derived from the
        // name now, so the seeded value below is inert and the extension is what is asserted.
        val testPath1 = "/test1.jpg"
        testProxy.executionContext.database.addToArray(
            reference = ArrayDatabaseReference(
                arrayId = DataId.EntityFieldDataId.create(
                    context = testProxy.tContext,
                    type = TList.ID,
                    parentEntity = rootId,
                    fieldRef = Folder.Fields.FILES
                ),
                isEntity = false
            ),
            value = Entity.create<Folder>(
                context = testProxy.tContext,
                composite = Folder.create(
                    context = testProxy.tContext,
                    path = testPath1,
                    files = emptyList()
                ),
            ) {
                set(File.Size, 1234L)
                set(File.LastModifiedAt, 123L)
            }
        )

        val testPath2 = "/test2.txt"
        testProxy.executionContext.database.addToArray(
            reference = ArrayDatabaseReference(
                arrayId = DataId.EntityFieldDataId.create(
                    context = testProxy.tContext,
                    type = TList.ID,
                    parentEntity = rootId,
                    fieldRef = Folder.Fields.FILES
                ),
                isEntity = false
            ),
            value = Entity.create<Folder>(
                context = testProxy.tContext,
                composite = Folder.create(testProxy.tContext, path = testPath2, files = emptyList()),
            ) {
                set(File.Size, 1234L)
                set(File.LastModifiedAt, 123L)
            }
        )

        val flowId = 2L

        val request = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(FileSystem.Relations.ROOT),
            *idToUuid(Folder.Fields.FILES),
            *idToUuid(Select.ID),
            *(32 + 40 * 3).toLong().toByteArray(),
            *idToUuid(Selector.FieldSelector.ID), *idToUuid(File.Field.PATH),
            *idToUuid(Selector.PropertySelector.ID), *idToUuid(File.LastModifiedAt.id), *0L.toByteArray(),
            *idToUuid(Selector.PropertySelector.ID), *idToUuid(File.Size.id), *0L.toByteArray(),
            *idToUuid(Selector.PropertySelector.ID), *idToUuid(FileCategory.Schema.id.get()), *0L.toByteArray(),
            *idToUuid(Stream.ID),
            *flowId.toByteArray()
        )

        testProxy.handleRequest(request)

        // Polling loop for initial update
        for (i in 1..50) {
            if (testProxy.updates.any { it is StreamElement.Value<*> }) break
            delay(100)
        }

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }

        assertEquals(1, valueUpdates.size)

        val result = valueUpdates.first()

        @Suppress("UNCHECKED_CAST")
        val resultList = (result.payload as TList<Entity<Folder>>).toList()

        assertEquals(2, resultList.size)

        val firstElementResult = resultList[0]
        assertEquals(testPath1, (firstElementResult as Entity<out File>).path.get())
        assertEquals(FileCategory.Image, firstElementResult.get(File.Category))
        assertEquals(
            123L,
            (firstElementResult as Entity<out File>).get(File.LastModifiedAt)?.get()
        )
        assertEquals(1234L, (firstElementResult as Entity<out File>).get(File.Size)?.get())

        val secondElementResult = resultList[1]
        assertEquals(testPath2, (secondElementResult as Entity<out File>).path.get())
        assertEquals(FileCategory.Document, secondElementResult.get(File.Category))
        assertEquals(
            123L,
            (secondElementResult as Entity<out File>).get(File.LastModifiedAt)?.get()
        )
        assertEquals(1234L, (secondElementResult as Entity<out File>).get(File.Size)?.get())
    }

    @Test
    fun flowRequest_WithTake_ReturnsCorrectNumberOfElements() = runTest {
        val testProxy = createProxy(backgroundScope)

        val rootId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = "/"
        )

        // Add 3 files
        for (i in 1..3) {
            testProxy.executionContext.database.addToArray(
                reference = ArrayDatabaseReference(
                    arrayId = DataId.EntityFieldDataId.create(
                        context = testProxy.tContext,
                        type = TList.ID,
                        parentEntity = rootId,
                        fieldRef = Folder.Fields.FILES
                    ),
                    isEntity = false
                ),
                value = Entity.create<Folder>(
                    context = testProxy.tContext,
                    composite = Folder.create(testProxy.tContext, path = "/test$i", files = emptyList()),
                ) {
                    set(File.Size, 1234L)
                    set(File.LastModifiedAt, 123L)
                }
            )
        }

        val flowId = 2L

        val request = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(FileSystem.Relations.ROOT),
            *idToUuid(Folder.Fields.FILES),
            *idToUuid(Select.ID),
            *(32 + 40 * 2).toLong().toByteArray(),
            *idToUuid(Selector.FieldSelector.ID), *idToUuid(File.Field.PATH),
            *idToUuid(Selector.PropertySelector.ID), *idToUuid(File.Size.id), *0L.toByteArray(),
            *idToUuid(Selector.PropertySelector.ID), *idToUuid(File.LastModifiedAt.id), *0L.toByteArray(),
            *idToUuid(Take.ID),
            *1L.toByteArray(), // take(1)
            *idToUuid(Stream.ID),
            *flowId.toByteArray()
        )

        testProxy.handleRequest(request)

        for (i in 1..50) {
            if (testProxy.updates.any { it is StreamElement.Value<*> }) break
            delay(100)
        }

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }

        assertEquals(1, valueUpdates.size)
        val result = valueUpdates.first()

        @Suppress("UNCHECKED_CAST")
        val resultList = (result.payload as TList<Entity<Folder>>).toList()

        assertEquals(1, resultList.size)
        assertEquals("/test1", (resultList[0] as Entity<out File>).path.get())
    }

    @Test
    fun flowRequest_WithDrop_ReturnsCorrectNumberOfElements() = runTest {
        val testProxy = createProxy(backgroundScope)

        val rootId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = "/"
        )

        // Add 3 files
        for (i in 1..3) {
            testProxy.executionContext.database.addToArray(
                reference = ArrayDatabaseReference(
                    arrayId = DataId.EntityFieldDataId.create(
                        context = testProxy.tContext,
                        type = TList.ID,
                        parentEntity = rootId,
                        fieldRef = Folder.Fields.FILES
                    ),
                    isEntity = false
                ),
                value = Entity.create<Folder>(
                    context = testProxy.tContext,
                    composite = Folder.create(testProxy.tContext, path = "/test$i", files = emptyList()),
                ) {
                    set(File.Size, 1234L)
                    set(File.LastModifiedAt, 123L)
                }
            )
        }

        val flowId = 2L

        val request = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(FileSystem.Relations.ROOT),
            *idToUuid(Folder.Fields.FILES),
            *idToUuid(Select.ID),
            *(32 + 40 * 2).toLong().toByteArray(),
            *idToUuid(Selector.FieldSelector.ID), *idToUuid(File.Field.PATH),
            *idToUuid(Selector.PropertySelector.ID), *idToUuid(File.Size.id), *0L.toByteArray(),
            *idToUuid(Selector.PropertySelector.ID), *idToUuid(FileCategory.Schema.id.get()), *0L.toByteArray(),
            *idToUuid(Drop.ID),
            *1L.toByteArray(), // drop(1)
            *idToUuid(Stream.ID),
            *flowId.toByteArray()
        )

        testProxy.handleRequest(request)

        for (i in 1..50) {
            if (testProxy.updates.any { it is StreamElement.Value<*> }) break
            delay(100)
        }

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }

        assertEquals(1, valueUpdates.size)
        val result = valueUpdates.first()

        @Suppress("UNCHECKED_CAST")
        val resultList = (result.payload as TList<Entity<Folder>>).toList()

        assertEquals(2, resultList.size)
        assertEquals("/test2", (resultList[0] as Entity<out File>).path.get())
        assertEquals("/test3", (resultList[1] as Entity<out File>).path.get())
    }

    @Test
    fun uploadFileToSubfolderIsWorking() = runTest {
        val testProxy = createProxy(backgroundScope)
        val subfolderPath = "/subfolder"
        val fileName = "test.txt"
        val fileData = byteArrayOf(1, 2, 3)

        // 1. Create the subfolder first
        val createFolderRequest = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(CreateFolder.ID),
            *subfolderPath.encodeToByteArray().size.toLong().toByteArray(),
            *subfolderPath.encodeToByteArray()
        )
        testProxy.handleRequest(createFolderRequest)

        // 2. Upload file to the newly created subfolder
        val uploadRequest = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(UploadFile.ID),
            *subfolderPath.encodeToByteArray().size.toLong().toByteArray(),
            *subfolderPath.encodeToByteArray(),
            *fileName.encodeToByteArray().size.toLong().toByteArray(),
            *fileName.encodeToByteArray(),
            *fileData.size.toLong().toByteArray(),
            *fileData
        )

        val resultBytes =
            testProxy.handleRequest(uploadRequest)
        val result = testProxy.parser.parse(resultBytes) as TResult<*>

        assertTrue(result is TResult.Success, "Upload should succeed without 'Array not found'")
    }

    @Test
    fun searchOperationDeserializedCorrectly() = runTest {
        val flowId = Random.nextLong()
        val testPath = "/test"

        val testProxy = createProxy(backgroundScope)
        val rootId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = "/"
        )
        testProxy.executionContext.database.addToArray(
            reference = ArrayDatabaseReference(
                arrayId = DataId.EntityFieldDataId.create(
                    context = testProxy.tContext,
                    type = TList.ID,
                    parentEntity = rootId,
                    fieldRef = Folder.Fields.FILES
                ),
                isEntity = false
            ),
            value = Entity.create<Folder>(
                context = testProxy.tContext,
                composite = Folder.create(
                    context = testProxy.tContext,
                    path = testPath,
                    files = emptyList()
                ),
            ) {
                set(File.Size, 123L)
                set(File.LastModifiedAt, 123L)
                include(mapOf(FileCategory.Schema.id.get() to FileCategory.Image))
            }
        )

        val searchPredicate = byteArrayOf(
            *idToUuid(File.Field.PATH),
            *idToUuid(EqualTo.ID),
            *idToUuid(TString.ID),
            *testPath.encodeToByteArray().size.toLong().toByteArray(),
            *testPath.encodeToByteArray(),
        )

        val result = testProxy.handleRequest(
            request = byteArrayOf(
                *idToUuid(FileService.ID),
                *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
                *idToUuid(FileSystem.Relations.ROOT),
                *idToUuid(Folder.Fields.FILES),
                *idToUuid(Search.ID),
                *searchPredicate.size.toLong().toByteArray(),
                *searchPredicate,
                *idToUuid(IfEmptyError.ID),
                *idToUuid(Stream.ID),
                *flowId.toByteArray()
            )
        )

        assertSuccess(testProxy, result, "search flow")

        for (i in 1..50) {
            if (testProxy.updates.any { it is StreamElement.Value<*> }) break
            delay(100)
        }

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }

        assertEquals(1, valueUpdates.size)
    }
}
