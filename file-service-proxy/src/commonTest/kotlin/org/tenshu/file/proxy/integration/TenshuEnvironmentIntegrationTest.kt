package org.tenshu.file.proxy.integration


import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.TaskRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.createFolder
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TenshuEnvironmentIntegrationTest {

    @Test
    fun localEnvironment_ExecutesServiceOperationsCorrectly() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val executor = LocalTaskExecutor(testProxy.executionContext)

        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = testProxy.schemaRegistry,
            requestChainFactory = { context ->
                TaskRequestChain(executor, context)
            },
            tContext = testProxy.tContext
        )

        val fileService = env.getService<FileService>(FileService.ID)
        val testPath = "/integration_test"

        val result = fileService.defaultFileSystem.createFolder(testPath)

        assertTrue(result is TResult.Success<*>, "Operation should succeed")

        // Verify it was actually created in the database
        val created = testProxy.database.getData(
            DataId.StringDataId.create(
                type = File.ID,
                keyValue = testPath
            )
        )
        assertTrue(created is Folder)
        assertEquals(testPath, created.path.get())
    }
}
