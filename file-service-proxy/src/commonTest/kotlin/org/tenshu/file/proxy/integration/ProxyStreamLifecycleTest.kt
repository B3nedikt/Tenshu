package org.tenshu.file.proxy.integration


import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.tenshu.core.StreamElement
import org.tenshu.core.collection.list.EndStream
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.core.TContext
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.FileSystem
import org.tenshu.file.test.createFileProxy
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProxyStreamLifecycleTest {

    private lateinit var tContext: TContext

    @BeforeTest
    fun setup() {
        tContext = createTestContext()
    }

    @Test
    fun streamLifecycle_CorrectlyStartsAndEndsListener() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val flowId = 123L

        // 1. Start the stream
        val startStreamRequest = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(FileSystem.Relations.ROOT),
            *idToUuid(Folder.Fields.FILES),
            *idToUuid(Select.ID),
            *32L.toByteArray(), // One selector length
            *idToUuid(Selector.FieldSelector.ID), *idToUuid(File.Field.PATH),
            *idToUuid(Stream.ID),
            *flowId.toByteArray()
        )

        testProxy.handleRequest(startStreamRequest)

        // 2. Trigger an update and verify it is received
        val testPath1 = "/update1"
        val createRequest1 = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(CreateFolder.ID),
            *testPath1.encodeToByteArray().size.toLong().toByteArray(),
            *testPath1.encodeToByteArray()
        )
        testProxy.handleRequest(createRequest1)

        for (i in 1..50) {
            val n = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
                .count { it.streamId.get() == flowId }
            if (n >= 2) break
            delay(20)
        }

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }

        assertEquals(
            2,
            valueUpdates.size,
            "Should receive initial and one update after first creation"
        )

        // 3. End the stream
        val endStreamRequest = byteArrayOf(
            *idToUuid(EndStream.ID),
            *flowId.toByteArray()
        )
        testProxy.handleRequest(endStreamRequest)

        // 4. Trigger another update and verify it is NOT received
        val testPath2 = "/update2"
        val createRequest2 = byteArrayOf(
            *idToUuid(FileService.ID),
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(CreateFolder.ID),
            *testPath2.encodeToByteArray().size.toLong().toByteArray(),
            *testPath2.encodeToByteArray()
        )
        testProxy.handleRequest(createRequest2)

        // Give any (incorrectly-fired) async listener a chance to run before asserting nothing arrived.
        delay(200)

        val valueUpdatesAfterEnd = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }

        assertEquals(
            2,
            valueUpdatesAfterEnd.size,
            "Should NOT receive any more updates after stream is ended"
        )
    }
}
