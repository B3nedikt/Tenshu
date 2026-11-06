package org.tenshu.file.proxy.integration


import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.DefaultInteractorProvider
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.core.common.request.TaskRequestChain
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.FileService
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.schema.toType
import org.tenshu.file.test.createFileTestRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TenshuIntegrationTest {

    @BeforeTest
    fun setUp() {
        createFileTestRegistry()
    }

    @Test
    fun taskSerializationAndDeserializationWorks() = runTest {
        val testRegistry = createFileTestRegistry()
        val serializer = BinarySerializer()
        val parser = BinaryParser(testRegistry, testRegistry)

        var capturedTaskBytes: ByteArray? = null

        lateinit var tContextRef: org.tenshu.core.core.TContext

        val executor = object : TaskExecutor {
            override fun execute(task: TList<Data>): TResult<Data> {
                capturedTaskBytes = serializer.serialize(task)
                @Suppress("UNCHECKED_CAST")
                return TResult.Success.create<Data?>(tContextRef, value = null) as TResult<Data>
            }
        }

        val tContext = createTestContext(
            schemaRegistry = testRegistry,
            interactorProvider = DefaultInteractorProvider { executor }
        )
        tContextRef = tContext

        val requestChain = TaskRequestChain(executor, tContext)
        val fileServiceRo =
            testRegistry.createRequestObject<FileService>(
                FileService.Schema.toType(),
                requestChain,
                tContext
            )

        // This should add a CreateFolder operation to the chain by navigating the hierarchy
        val fileSystem = fileServiceRo.defaultFileSystem
        val createFolderOp = fileSystem.findOperation<CreateFolder>(CreateFolder.ID)

        val ro = createFolderOp.createFolder("/test")
        ro.asTaskInteractor<Data?>().execute()

        val bytes = capturedTaskBytes ?: error("Task was not captured")

        // Now try to deserialize it
        val deserializedTask = parser.parse<TList<Data>>(bytes)
        assertIs<TList<Data>>(deserializedTask)

        val list = deserializedTask.toList()
        // The task should contain: DefaultFileSystem relation and the CreateFolder operation.
        // (The FileService root reference is implicit in the chain anchor; the ServiceRo
        // factory does not emit a leading ServiceTypeReference into the callstack.)
        assertEquals(2, list.size)
    }
}
