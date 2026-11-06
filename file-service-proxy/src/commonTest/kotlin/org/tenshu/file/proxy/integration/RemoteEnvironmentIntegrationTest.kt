package org.tenshu.file.proxy.integration

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.common.request.TaskRequestChain
import org.tenshu.core.core.findOperation
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.FileSystem
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteEnvironmentIntegrationTest {

    @Test
    fun remoteEnvironment_ExecutesServiceOperationsCorrectly() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val localExecutor = LocalTaskExecutor(testProxy.executionContext)

        // Simulate the client side
        val remoteExecutor = object : AsyncTaskExecutor {
            override fun execute(task: TList<Data>): TResult<Data> {
                // Pass it to the local executor on the "proxy" side
                return localExecutor.execute(task)
            }

            override suspend fun executeAsync(task: TList<Data>): TResult<Data> {
                return localExecutor.executeAsync(task)
            }
        }

        val env = TenshuEnvironment(
            taskExecutor = remoteExecutor,
            schemaRegistry = testProxy.schemaRegistry,
            tContext = testProxy.tContext,
            requestChainFactory = { context ->
                TaskRequestChain(
                    taskExecutor = remoteExecutor,
                    context = context
                )
            }
        )

        val fileService = env.getService<FileService>(FileService.ID)
        val testPath = "/remote_integration_test"

        // Discover and execute the operation RO on the default file system.
        // This ensures the callstack contains: [FileService, DefaultFileSystem, CreateFolder]
        val fileSystem = fileService.defaultFileSystem
        val createFolderOp = fileSystem.findOperation<CreateFolder>(CreateFolder.ID)

        val result = createFolderOp.createFolder(testPath)
            .asTaskInteractor<Data?>()
            .execute()

        assertTrue(result is TResult.Success<*>, "Operation should succeed but was $result")

        // Verify it was actually created in the database
        val created = testProxy.database.getData(
            DataId.StringDataId.create(
                context = testProxy.tContext,
                type = File.ID,
                keyValue = testPath
            )
        )
        assertTrue(created is Folder, "Data at $testPath should be a Folder but was $created")
        assertEquals(testPath, created.getField<TString>(File.Field.PATH).get())
    }
}
