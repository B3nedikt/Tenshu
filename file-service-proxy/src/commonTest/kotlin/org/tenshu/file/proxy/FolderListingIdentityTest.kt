package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.toTString
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.number.toTInteger
import org.tenshu.file.test.createFileProxy
import org.tenshu.file.test.createFileTestRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Investigation of the "folder detail listing stale / empty on tap" bug
 * (`docs/proposals/drive-folder-listing-consistency.md`).
 *
 * ⚠ **These tests all PASS** — i.e. the bug does **not** reproduce in the
 * in-process `TestProxy` harness. They are kept as **guards that rule out a
 * whole class of causes**: in-process, the write path, the count path, and the
 * detail read path all resolve the *same* array with the *same* identity, for
 * both the plain `search->files->getAll` chain and the windowed
 * `search->files->drop->take->getAll` paging chain.
 *
 * That narrows the real (on-device only) bug to something `TestProxy` does not
 * exercise: the persistent SQLite `ObjectStore` (this harness is in-memory)
 * and/or a **concurrency / read-after-write race** between a write
 * (`addToArray`) and a reactive reload — the on-device repro is timing-dependent
 * (fast create-while-viewing fails, slow succeeds), which sequential
 * single-threaded tests cannot surface. See the proposal §3 for the full
 * ruled-out list.
 */
class FolderListingIdentityTest {

    private suspend fun createFolder(
        testProxy: org.tenshu.core.common.TestProxy,
        path: String,
    ) {
        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to path.toTString(testProxy.tContext)),
        )
    }

    @Test
    fun detailReadIdentityEqualsWriteIdentity() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val ec = testProxy.executionContext

        createFolder(testProxy, "/Q")
        createFolder(testProxy, "/Q/R")

        // Write/count path identity: what ParentFilesRelationMaintainer used to
        // key /Q's FILES array (parentPath from the child "/Q/R").
        val writeParentId = DataId.StringDataId.create(type = File.ID, keyValue = "/Q")

        // Detail read path: resolve the /Q entity by that key, then recompute its
        // identity the way EvaluateCallstack's `.files` resolution does.
        val qEntity = ec.database.getData(writeParentId, asEntity = true, projection = null)
            ?: error("/Q entity not found by StringDataId(File.ID, \"/Q\")")
        val readParentId = ec.getDataId(qEntity)
            ?: error("getDataId returned null for the /Q entity")

        assertEquals(
            writeParentId.getDataIdHash(),
            readParentId.getDataIdHash(),
            "detail-read parent identity must equal the write parent identity; " +
                "writeParentId=$writeParentId readParentId=$readParentId",
        )
    }

    @Test
    fun detailChain_searchFilesGetAll_matchesWriteArray() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val ec = testProxy.executionContext

        createFolder(testProxy, "/Q")
        createFolder(testProxy, "/Q/R")

        // Step 1: resolve /Q the way `navigateTo` does — search the root files
        // array by path (drives SearchByKeyFastPath).
        val searchResult = org.tenshu.core.common.proxy.evaluateCallstack(
            executionContext = ec,
            contextData = rootFilesRef(testProxy),
            callstack = listOf(searchByPath("/Q")),
            allowDeferring = false,
        )
        val qEntity = (searchResult as org.tenshu.core.Maybe.Some<*>).value as Data

        // Step 2: `.files.getAll()` on the search-materialised /Q entity.
        val filesResult = org.tenshu.core.common.proxy.evaluateCallstack(
            executionContext = ec,
            contextData = qEntity,
            callstack = listOf(
                createFileTestRegistry().getReference(Folder.Fields.FILES),
                org.tenshu.core.common.impl.OperationDto(
                    params = emptyMap(),
                    schema = org.tenshu.core.collection.list.GetAll.Schema,
                ),
            ),
            allowDeferring = false,
        )
        val detailFiles = (filesResult as TList<*>).toList()

        // Write/count path: /Q's out-of-line files array.
        val writeArray = ec.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.EntityFieldDataId.create(
                    type = TList.ID,
                    parentEntity = DataId.StringDataId.create(type = File.ID, keyValue = "/Q") as DataId.EntityDataId,
                    fieldRef = Folder.Fields.FILES,
                ),
                isEntity = false,
            )
        )!!.toList()

        assertEquals(1, writeArray.size, "write/count path sees /Q's child R")
        assertEquals(
            writeArray.size,
            detailFiles.size,
            "detail search->files->getAll must match the write array",
        )
    }

    @Test
    fun detailChain_windowed_matchesWriteArray() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val ec = testProxy.executionContext

        createFolder(testProxy, "/Q")
        createFolder(testProxy, "/Q/R")
        createFolder(testProxy, "/Q/S")

        val searchResult = org.tenshu.core.common.proxy.evaluateCallstack(
            executionContext = ec,
            contextData = rootFilesRef(testProxy),
            callstack = listOf(searchByPath("/Q")),
            allowDeferring = false,
        )
        val qEntity = (searchResult as org.tenshu.core.Maybe.Some<*>).value as Data

        // `.files.drop(0).take(20).getAll()` — the paging window path
        // (getFilesPaged), which fuses into getArrayDataWindow, NOT the plain
        // getArrayData/count path.
        fun intOp(id: String, schema: org.tenshu.core.schema.OperationSchema, n: Int) =
            org.tenshu.core.common.impl.OperationDto(
                params = mapOf(id to n.toTInteger(testProxy.tContext)),
                schema = schema,
            )

        val filesResult = org.tenshu.core.common.proxy.evaluateCallstack(
            executionContext = ec,
            contextData = qEntity,
            callstack = listOf(
                createFileTestRegistry().getReference(Folder.Fields.FILES),
                intOp(org.tenshu.core.collection.list.Drop.Params.N, org.tenshu.core.collection.list.Drop.Schema, 0),
                intOp(org.tenshu.core.collection.list.Take.Params.N, org.tenshu.core.collection.list.Take.Schema, 20),
                org.tenshu.core.common.impl.OperationDto(
                    params = emptyMap(),
                    schema = org.tenshu.core.collection.list.GetAll.Schema,
                ),
            ),
            allowDeferring = false,
        )
        val detailFiles = (filesResult as TList<*>).toList()

        assertEquals(2, detailFiles.size, "windowed detail read must see both /Q children (R, S)")
    }

    private fun rootFilesRef(testProxy: org.tenshu.core.common.TestProxy): ArrayDatabaseReference {
        @Suppress("UNCHECKED_CAST")
        val rootFolder = testProxy.executionContext.resolveServiceData(
            service = org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto(
                org.tenshu.core.file.FileService.Relations.DEFAULT_FILE_SYSTEM
            ),
            ref = org.tenshu.core.common.impl.reference.EntityReferenceDto(
                org.tenshu.core.file.system.FileSystem.Relations.ROOT
            ),
        ) as org.tenshu.core.entity.Entity<Folder>

        return ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                type = TList.ID,
                parentEntity = testProxy.executionContext.getDataId(rootFolder) as DataId.EntityDataId,
                fieldRef = Folder.Fields.FILES,
            ),
            isEntity = false,
            elementSchemaId = File.ID,
        )
    }

    private fun searchByPath(path: String) = org.tenshu.core.common.impl.OperationDto(
        params = mapOf(
            org.tenshu.core.collection.list.Search.Params.PREDICATE to listOf(
                createFileTestRegistry().getReference(File.Field.PATH),
                org.tenshu.core.common.impl.OperationDto(
                    params = mapOf(org.tenshu.core.EqualTo.Params.OTHER to path.toTString()),
                    schema = org.tenshu.core.EqualTo.Schema,
                ),
            ).toTList()
        ),
        schema = org.tenshu.core.collection.list.Search.Schema,
    )

    @Test
    fun detailReadArrayEqualsWriteArray() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val ec = testProxy.executionContext

        createFolder(testProxy, "/Q")
        createFolder(testProxy, "/Q/R")

        fun filesArray(parentId: DataId): List<Data> = ec.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.EntityFieldDataId.create(
                    type = TList.ID,
                    parentEntity = parentId as DataId.EntityDataId,
                    fieldRef = Folder.Fields.FILES,
                ),
                isEntity = false,
            )
        )?.toList() ?: emptyList()

        val writeParentId = DataId.StringDataId.create(type = File.ID, keyValue = "/Q")
        val qEntity = ec.database.getData(writeParentId, asEntity = true, projection = null)!!
        val readParentId = ec.getDataId(qEntity)!!

        val writeArray = filesArray(writeParentId)
        val readArray = filesArray(readParentId)

        assertEquals(1, writeArray.size, "write/count path sees /Q's child R")
        assertEquals(
            writeArray.size,
            readArray.size,
            "detail read must see the same children the write stored",
        )
    }
}
