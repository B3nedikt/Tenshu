package org.tenshu.file.proxy.integration


import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.tenshu.core.StreamElement
import org.tenshu.core.collection.list.EndStream
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.FileSystem
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals

class ProxyStreamMultiplexingTest {

    @Test
    fun streamMultiplexing_CorrectlyHandlesMultipleSubscribers() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val flowId1 = 1L
        val flowId2 = 2L

        // 1. Construct request for root folder files
        val request = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(FileSystem.Relations.ROOT),
            *idToUuid(Folder.Fields.FILES),
            *idToUuid(Select.ID),
            *32L.toByteArray(),
            *idToUuid(Selector.FieldSelector.ID), *idToUuid(File.Field.PATH),
            *idToUuid(Stream.ID)
        )

        // 2. Start two streams with the same request but different flowIds
        testProxy.handleRequest(
            byteArrayOf(*request, *flowId1.toByteArray())
        )
        testProxy.handleRequest(
            byteArrayOf(*request, *flowId2.toByteArray())
        )

        // 3. Trigger an update
        createFolder(testProxy, "/test1")

        for (i in 1..50) {
            if (testProxy.updates.filterIsInstance<StreamElement.Value<*>>().size >= 4) break
            delay(20)
        }

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()

        // 4. Verify both streams received the update (initial empty + update for each stream)
        assertEquals(
            4,
            valueUpdates.size,
            "Both streams should receive initial empty and the update"
        )

        // 5. End the first stream
        val endRequest1 = byteArrayOf(*idToUuid(EndStream.ID), *flowId1.toByteArray())
        testProxy.handleRequest(endRequest1)

        // 6. Trigger another update
        createFolder(testProxy, "/test2")

        for (i in 1..50) {
            if (testProxy.updates.filterIsInstance<StreamElement.Value<*>>().size >= 5) break
            delay(20)
        }

        val valueUpdatesAfterUpdate2 = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()

        // 7. Verify only the second stream received the update
        assertEquals(
            5,
            valueUpdatesAfterUpdate2.size,
            "Only stream 2 should receive the second update"
        )

        // 8. End the second stream
        val endRequest2 = byteArrayOf(*idToUuid(EndStream.ID), *flowId2.toByteArray())
        testProxy.handleRequest(endRequest2)

        // 9. Trigger a final update
        createFolder(testProxy, "/test3")

        // Give any (incorrectly-fired) async listener a chance to run before asserting nothing arrived.
        delay(200)

        val valueUpdatesAfterEnd = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()

        // 10. Verify no more updates are received
        assertEquals(
            5,
            valueUpdatesAfterEnd.size,
            "No updates should be received after both streams end"
        )
    }

    private suspend fun createFolder(testProxy: TestProxy, path: String) {
        val createRequest = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(CreateFolder.ID),
            *path.encodeToByteArray().size.toLong().toByteArray(),
            *path.encodeToByteArray()
        )
        testProxy.handleRequest(createRequest)
    }
}
