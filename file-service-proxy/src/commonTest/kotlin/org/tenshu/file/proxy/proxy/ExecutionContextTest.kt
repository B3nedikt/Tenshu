package org.tenshu.file.proxy.proxy


import org.tenshu.core.Data
import org.tenshu.core.EqualTo
import org.tenshu.core.Maybe
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.collection.list.Search
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.reference.EntityReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.CallstackMaterializer
import org.tenshu.core.common.proxy.ClientConnection
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.db.DatabaseImpl
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.TestDatabaseLock
import org.tenshu.core.common.proxy.db.DbEntryMapper
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.path
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.get
import org.tenshu.core.toTString
import org.tenshu.core.trait.traitResolver
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.impl.CreateFolderImplementation
import org.tenshu.file.proxy.initFileService
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ExecutionContextTest {

    private lateinit var executionContext: ExecutionContext

    @BeforeTest
    fun setUp() {
        val schemaRegistry = createCoreTestRegistry()
            .apply { registerConfig(fileServiceCommonModule) }

        val tContext = createTestContext(schemaRegistry)
        val traitResolver = tContext.traitResolver()

        val database = DatabaseImpl(
            context = tContext,
            objectStore = InMemoryObjectStore(),
            dbEntryMapper = DbEntryMapper(
                schemaProvider = schemaRegistry,
                dataFactory = schemaRegistry,
                traitResolver = traitResolver,
                context = tContext
            ),
            traitResolver = traitResolver,
            // A real lock: `initFileService`'s observers run on the `Dispatchers.Default` session scope.
            lock = TestDatabaseLock()
        )

        executionContext = ExecutionContext(
            context = tContext,
            database = database,
            traitResolver = traitResolver,
            clientConnection = object : ClientConnection {
                override fun init() {}
                override fun send(data: Data) {}
                override fun destroy() {}
            },
            implementationFactory = { id ->
                implementationFactory(id)!!
            },
            materializer = CallstackMaterializer(schemaRegistry, tContext),
            pureOperationRunner = tContext.components[PureOperationRunner.ID] as PureOperationRunner,
            sessionScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        )

        executionContext.init()
        executionContext.initFileService()
    }

    @Test
    fun `resolveEntity can find the root folder`() {
        val data = executionContext.resolveServiceData(
            service = ServiceTypeReferenceDto(FileService.Relations.DEFAULT_FILE_SYSTEM),
            ref = EntityReferenceDto(FileSystem.Relations.ROOT)
        )

        assertNotNull(data, "Root folder data should not be null")
    }

    @Test
    fun `createFolder triggers array listener`() {
        @Suppress("UNCHECKED_CAST")
        val rootFolder = executionContext.resolveServiceData(
            service = ServiceTypeReferenceDto(FileService.Relations.DEFAULT_FILE_SYSTEM),
            ref = EntityReferenceDto(FileSystem.Relations.ROOT)
        ) as Entity<Folder>

        val arrayRef = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                context = executionContext.context,
                type = TList.ID,
                parentEntity = executionContext.getDataId(rootFolder) as DataId.EntityDataId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false,
            elementSchemaId = File.ID
        )

        var listenerCalled = false
        executionContext.database.setArrayListener(arrayRef, { _, _ ->
            listenerCalled = true
        }, { _, _ -> })

        CreateFolderImplementation().createFolder(executionContext, "/test")

        assertEquals(true, listenerCalled, "Listener should have been called after createFolder")
    }

    @Test
    fun `search for folder by path works`() {
        CreateFolderImplementation().createFolder(executionContext, "/test")

        @Suppress("UNCHECKED_CAST")
        val rootFolder = executionContext.resolveServiceData(
            service = ServiceTypeReferenceDto(FileService.Relations.DEFAULT_FILE_SYSTEM),
            ref = EntityReferenceDto(FileSystem.Relations.ROOT)
        ) as Entity<Folder>

        val arrayRef = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                context = executionContext.context,
                type = TList.ID,
                parentEntity = executionContext.getDataId(rootFolder) as DataId.EntityDataId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false,
            elementSchemaId = File.ID
        )

        val files = executionContext
            .getArrayData(arrayRef)!!.toList()
            .toTList(executionContext.context)

        val searchOp = OperationDto(
            params = mapOf(
                Search.Params.PREDICATE to listOf(
                    // Navigate to path field first
                    executionContext.schemaProvider.getReference(File.Field.PATH),
                    OperationDto(
                        params = mapOf(EqualTo.Params.OTHER to "/test".toTString(executionContext.context)),
                        schema = EqualTo.Schema
                    )
                ).toTList()
            ),
            schema = Search.Schema
        )

        val result = executionContext.execute<Data>(
            operationId = Search.ID,
            caller = files,
            params = searchOp.params
        )

        val foundMaybe = result as Maybe.Some<Data>
        val pathValue = when (val foundData = foundMaybe.value) {
            is Entity<*> -> {
                @Suppress("UNCHECKED_CAST")
                (foundData as Entity<out File>).path.get()
            }

            is File -> foundData.path.get()
            else -> ""
        }
        assertEquals("/test", pathValue)
    }
}
