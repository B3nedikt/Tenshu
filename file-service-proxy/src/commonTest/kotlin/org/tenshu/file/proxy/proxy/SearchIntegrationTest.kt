package org.tenshu.file.proxy.proxy


import org.tenshu.core.Data
import org.tenshu.core.EqualTo
import org.tenshu.core.Maybe
import org.tenshu.core.collection.list.Search
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.reference.EntityReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.get
import org.tenshu.core.toTString
import org.tenshu.file.impl.CreateFolderImplementation
import org.tenshu.file.test.createFileProxy
import org.tenshu.file.test.createFileTestRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchIntegrationTest {

    private lateinit var proxy: TestProxy

    @BeforeTest
    fun setUp() {
        proxy = createFileProxy()
    }

    @Test
    fun `search for folder by path works`() {
        // 1. Create a folder to search for
        CreateFolderImplementation().createFolder(proxy.executionContext, "/test")

        // 2. Resolve the root folder's files array
        @Suppress("UNCHECKED_CAST")
        val rootFolder = proxy.executionContext.resolveServiceData(
            service = ServiceTypeReferenceDto(FileService.Relations.DEFAULT_FILE_SYSTEM),
            ref = EntityReferenceDto(FileSystem.Relations.ROOT)
        ) as Entity<Folder>

        val arrayRef = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                context = proxy.tContext,
                type = TList.ID,
                parentEntity = proxy.executionContext.getDataId(rootFolder) as DataId.EntityDataId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false,
            elementSchemaId = File.ID
        )

        val files = proxy.executionContext.getArrayData(arrayRef)!!.toList().toTList(proxy.tContext)

        // 3. Construct the Search operation with EqualTo selector
        val searchOp = OperationDto(
            params = mapOf(
                Search.Params.PREDICATE to listOf(
                    // Navigate to path field first
                    createFileTestRegistry().getReference(File.Field.PATH),
                    OperationDto(
                        params = mapOf(EqualTo.Params.OTHER to "/test".toTString()),
                        schema = EqualTo.Schema
                    )
                ).toTList()
            ),
            schema = Search.Schema
        )

        // 4. Evaluate the callstack on the server side
        val result = proxy.executionContext.execute<Data>(
            operationId = Search.ID,
            caller = files,
            params = searchOp.params
        )

        // 5. Verify the result
        @Suppress("UNCHECKED_CAST")
        val foundMaybe = result as Maybe.Some<Folder>
        assertEquals("/test", foundMaybe.value.path.get())
    }
}
