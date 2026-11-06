package org.tenshu.file.proxy.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.searchAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.reference.EntityReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.contains
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.name
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.fold
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.file.impl.CreateFolderImplementation
import org.tenshu.file.test.createFileProxy
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end proof that the derived `File.Name` dynamic property resolves inside a
 * predicate scan on the proxy — `searchAll<File> { name contains q }` matches on the
 * last path segment, NOT the whole path. This is what lets Drive's search drop its
 * client-side name re-filter: a hit on an ancestor folder's name no longer surfaces
 * that folder's unrelated descendants.
 */
class FileNameSearchIntegrationTest {

    private lateinit var proxy: TestProxy

    @BeforeTest
    fun setUp() {
        proxy = createFileProxy()
    }

    @Test
    fun `searchAll by name matches the segment not the ancestor path`() = runTest {
        // A tree where an ancestor folder's name matches but a leaf's does not:
        //   /Reports          (name "Reports"  — matches "report")
        //   /Reports/archive  (name "archive"  — path contains "Reports", name does NOT)
        //   /notes            (name "notes"    — no match)
        val createFolder = CreateFolderImplementation()
        createFolder.createFolder(proxy.executionContext, "/Reports")
        createFolder.createFolder(proxy.executionContext, "/Reports/archive")
        createFolder.createFolder(proxy.executionContext, "/notes")

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

        val chain = LocalRequestChain(proxy.localTaskExecutor, proxy.tContext).addArray(files)
        val list = proxy.tContext.roProvider().createRequestObject<TList<File>>(
            type = TList.Schema.toType(),
            requestChain = chain,
            context = proxy.tContext,
        )

        // Explicit ignoreCase — the shape Drive's search uses now that plain
        // `contains` defaults case-sensitive: "report" still matches "Reports".
        val results = list.searchAll<File> { name.contains("report", ignoreCase = true) }

        val result = (results as RequestObject).requestChain.getResult().fold(
            onSuccess = { it as TList<*> },
            onFailure = { error("searchAll(name contains) failed on proxy: $it") }
        )

        assertEquals(
            listOf("/Reports"),
            result.toList().map { (it as File).path.get() },
            "matches the folder whose NAME contains the query; the deep-walked " +
                "/Reports/archive (path contains 'Reports', name 'archive' does not) is excluded",
        )
    }
}
