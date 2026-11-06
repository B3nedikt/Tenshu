package org.tenshu.core.common

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.InteractorProvider
import org.tenshu.core.SyncInteractor
import org.tenshu.core.TResult
import org.tenshu.core.asStreamInteractor
import org.tenshu.core.collection.list.SearchAll
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.searchAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.stream
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.core.common.request.TaskRequestChain
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.core.TContext
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.equalTo
import org.tenshu.core.file.Category
import org.tenshu.core.file.File
import org.tenshu.core.entity.get
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.DeleteFile
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.Type
import org.tenshu.file.client.impl.FileServiceRo
import org.tenshu.file.test.createFileTestRegistry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FileServiceUnitTest {

    private val testRegistry = createFileTestRegistry()

    private fun createProxyContext(
        interactorProvider: InteractorProvider? = null,
        taskExecutor: (() -> TaskExecutor)? = null
    ): TContext {
        return createTestContext(
            schemaRegistry = testRegistry,
            interactorProvider = interactorProvider
                ?: taskExecutor?.let { DefaultInteractorProvider(taskExecutorProvider = it) }
                ?: mockInteractorProvider
        )
    }

    private val mockInteractorProvider = object : InteractorProvider {
        override suspend fun <T : Data?> getInteractor(ro: RequestObject): org.tenshu.core.Interactor<T> {
            return object : SyncInteractor<T> {
                override fun execute(): TResult<T> = error("Not implemented")
            }
        }
    }

    @Test
    fun binarySerializationIsWorking() = runTest {

        val context = createProxyContext()
        val serializer = BinarySerializer(context.schemaProvider())

        val taskExecutor = object : TaskExecutor {
            override fun execute(task: TList<Data>): TResult<Data> {
                val bytes = task.toBinary(serializer)

                val expectedHeader = byteArrayOf(
                    *idToUuid(FileService.ID),
                    *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
                    *idToUuid(FileSystem.Relations.ROOT),
                )

                assertContentEquals(expectedHeader, bytes.copyOfRange(0, expectedHeader.size))

                return TResult.Success.create(context, value = toEmptyData())
            }
        }

        val fileService = FileServiceRo(
            requestChain = TaskRequestChain(taskExecutor, context),
            context = context,
        )

        fileService.defaultFileSystem.root
    }

    @Test
    fun flowOperation_CreatesCorrectRequest() = runTest {

        lateinit var contextRef: TContext
        var capturedBytes: ByteArray? = null

        val taskExecutor = object : TaskExecutor {
            override fun execute(task: TList<Data>): TResult<Data> {
                val serializer = BinarySerializer(contextRef.schemaProvider())
                capturedBytes = task.toBinary(serializer)
                return TResult.Success.create(contextRef, value = toEmptyData())
            }
        }

        val testContext = createTestContext(
            schemaRegistry = testRegistry,
            interactorProvider = DefaultInteractorProvider { taskExecutor }
        )
        contextRef = testContext

        val fileService = FileServiceRo(
            requestChain = TaskRequestChain(taskExecutor, testContext),
            context = testContext,
        )

        fileService.defaultFileSystem.root
            .files
            .select {
                fields(
                    File.Field.PATH
                )
                relations(File.Category)
            }
            .stream()
            .asStreamInteractor<Data>()

        val captured = capturedBytes ?: throw IllegalStateException("No bytes captured")

        val expectedHeader = byteArrayOf(
            *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
            *idToUuid(FileSystem.Relations.ROOT),
            *idToUuid(Folder.Fields.FILES),
            *idToUuid(Select.ID),
        )

        assertContentEquals(expectedHeader, captured.copyOfRange(0, expectedHeader.size))
    }

    @Test
    fun flowOperation_CreatesCorrectFlow() = runTest {
        val lastModified = 12345L
        lateinit var context: TContext

        val taskExecutor = object : TaskExecutor {
            override fun execute(task: TList<Data>): TResult<Data> {
                return TResult.Success.create(context, value = toEmptyData())
            }
        }

        val interactorProvider = object : InteractorProvider {
            override suspend fun <T : Data?> getInteractor(ro: RequestObject): org.tenshu.core.Interactor<T> {
                val dispatcher = StreamInteractorImpl<Data>(
                    onEndStream = { },
                    onCleanup = {}
                )

                dispatcher.setInitialResult(TResult.Success.create(context, null))

                val folder = Entity.create<Folder>(
                    context = context,
                    composite = Folder.create(context, path = "/test", files = emptyList()),
                ) {
                    set(File.Size, 0L)
                    set(File.LastModifiedAt, lastModified)
                }

                dispatcher.dispatchElement(
                    StreamElementValueDto(
                        streamId = 1L, // Mock stream ID
                        payload = listOf(folder).toTList(context) as Data
                    )
                )

                @Suppress("UNCHECKED_CAST")
                return dispatcher as org.tenshu.core.Interactor<T>
            }
        }

        context = createProxyContext(
            interactorProvider = interactorProvider,
            taskExecutor = { taskExecutor }
        )

        val fileService = FileServiceRo(
            requestChain = TaskRequestChain(taskExecutor, context),
            context = context,
        )

        val result = fileService.defaultFileSystem.root
            .files
            .select {
                fields(
                    File.Field.PATH
                )
                relations(File.Size, File.LastModifiedAt)
            }
            .flow().first()

        val file = result.get().toList().first() as Entity<out File>

        assertEquals(file.path.get(), "/test")
        assertEquals(0L, file.get(File.Size)?.get())
        assertEquals(lastModified, file.get(File.LastModifiedAt)?.get())
    }

    @Test
    fun binarySerializationOfCreateOperationIsWorking() = runTest {

        lateinit var contextRef: TContext
        val taskExecutor = object : TaskExecutor {
            override fun execute(task: TList<Data>): TResult<Data> {
                val serializer = BinarySerializer(contextRef.schemaProvider())
                val bytes = task.toBinary(serializer)

                val expectedHeader = byteArrayOf(
                    *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
                    *idToUuid(CreateFolder.ID),
                )

                assertContentEquals(expectedHeader, bytes.copyOfRange(0, expectedHeader.size))
                return TResult.Success.create(contextRef, value = toEmptyData())
            }
        }

        val context = createProxyContext(taskExecutor = { taskExecutor })
        contextRef = context

        val fileService = FileServiceRo(
            requestChain = TaskRequestChain(taskExecutor, context),
            context = context,
        )

        fileService.defaultFileSystem.createFolder("/test")
    }

    @Test
    fun binarySerializationOfDeleteOperationIsWorking() = runTest {

        lateinit var contextRef: TContext
        val taskExecutor = object : TaskExecutor {
            override fun execute(task: TList<Data>): TResult<Data> {
                val serializer = BinarySerializer(contextRef.schemaProvider())
                val bytes = task.toBinary(serializer)

                val expectedHeader = byteArrayOf(
                    *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
                    *idToUuid(DeleteFile.ID),
                )

                assertContentEquals(expectedHeader, bytes.copyOfRange(0, expectedHeader.size))
                return TResult.Success.create(contextRef, value = toEmptyData())
            }
        }

        val context = createProxyContext(taskExecutor = { taskExecutor })
        contextRef = context

        val fileService = FileServiceRo(
            requestChain = TaskRequestChain(taskExecutor, context),
            context = context,
        )

        fileService.defaultFileSystem.deleteFile("/test")
    }

    @Test
    fun binarySerializationOfUploadOperationIsWorking() = runTest {

        lateinit var contextRef: TContext
        val taskExecutor = object : TaskExecutor {
            override fun execute(task: TList<Data>): TResult<Data> {
                val serializer = BinarySerializer(contextRef.schemaProvider())
                val bytes = task.toBinary(serializer)

                val expectedHeader = byteArrayOf(
                    *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
                    *idToUuid(UploadFile.ID),
                )

                assertContentEquals(expectedHeader, bytes.copyOfRange(0, expectedHeader.size))
                return TResult.Success.create(contextRef, value = toEmptyData())
            }
        }

        val context = createProxyContext(taskExecutor = { taskExecutor })
        contextRef = context

        val fileService = FileServiceRo(
            requestChain = TaskRequestChain(taskExecutor, context),
            context = context,
        )

        fileService.defaultFileSystem.uploadFile("/", "test", byteArrayOf(1, 2, 3))
    }

    @Test
    fun binarySerializationOfPredicateIsWorking() = runTest {

        val context = createProxyContext()
        val serializer = BinarySerializer(context.schemaProvider())

        val taskExecutor = object : TaskExecutor {
            override fun execute(task: TList<Data>): TResult<Data> {
                val bytes = task.toBinary(serializer)

                val expectedHeader = byteArrayOf(
                    *idToUuid(FileService.ID),
                    *idToUuid(FileService.Relations.DEFAULT_FILE_SYSTEM),
                    *idToUuid(FileSystem.Relations.ROOT),
                    *idToUuid(Folder.Fields.FILES),
                    *idToUuid(SearchAll.ID),
                )

                assertContentEquals(expectedHeader, bytes.copyOfRange(0, expectedHeader.size))
                return TResult.Success.create(context, value = toEmptyData())
            }
        }

        val fileService = FileServiceRo(
            requestChain = TaskRequestChain(taskExecutor, context),
            context = context,
        )

        fileService.defaultFileSystem.root.files.searchAll<File> {
            path equalTo "/test"
        }
    }

    private fun TList<Data>.toBinary(serializer: BinarySerializer): ByteArray {
        return serializer.serialize(
            data = this,
            expectedType = Type.create(baseSchema = TList.Schema)
        )
    }

    private fun toEmptyData(): Data {
        return testRegistry.createList<Data>(emptyList())
    }
}
