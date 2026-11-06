package org.tenshu.file.proxy.integration

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseStreamTest {

    @Test
    fun testNestedFolderStreaming() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val executionContext = testProxy.executionContext

        // 1. Create /A/B
        executionContext.executeAsync<Data>(
            CreateFolder.ID,
            null,
            mapOf(CreateFolder.Params.PATH to "/A".toTString(testProxy.tContext))
        )
        executionContext.executeAsync<Data>(
            CreateFolder.ID,
            null,
            mapOf(CreateFolder.Params.PATH to "/A/B".toTString(testProxy.tContext))
        )

        // 2. Setup a listener for /A/B/files
        val streamId = 123L
        val parentDataId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = "/A/B"
        )
        val arrayRef = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                parentEntity = parentDataId as DataId.EntityDataId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false
        )

        val results = mutableListOf<Data>()
        testProxy.testClientConnection.addListener { results.add(it) }

        executionContext.setDatabaseListener(streamId, arrayRef, emptyList())

        // Initial emission: empty list
        assertEquals(1, results.size, "Should have received initial emission")
        assertTrue(results.last() is StreamElementValueDto)

        // 3. Upload file to /A/B
        executionContext.executeAsync<Data>(
            UploadFile.ID, null, mapOf(
                UploadFile.Params.PARENT_PATH to "/A/B".toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to "test.txt".toTString(testProxy.tContext),
                UploadFile.Params.DATA to "hello".encodeToByteArray()
                    .toTByteArray(testProxy.tContext)
            )
        )

        // Should have received another emission
        assertEquals(2, results.size, "Should have received an update after file upload")
    }
}
