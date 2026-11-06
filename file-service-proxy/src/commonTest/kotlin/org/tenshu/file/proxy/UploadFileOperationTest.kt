package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.AlreadyExistsError
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.id
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UploadFileOperationTest {

    @Test
    fun uploadFile_savesCorrectTypeAndData() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val fileName = "test.txt"
        val parentPath = "/"
        val targetPath = "/$fileName"
        val fileData = byteArrayOf(1, 2, 3)

        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to parentPath.toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to fileName.toTString(testProxy.tContext),
                UploadFile.Params.DATA to fileData.toTByteArray(testProxy.tContext)
            )
        )

        // Verify state in database
        val rootId = DataId.StringDataId.create(type = File.ID, keyValue = "/")
        val ref = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                type = TList.ID,
                parentEntity = rootId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false
        )
        val filesList = testProxy.executionContext.getArrayData(ref)!!.toList()

        assertEquals(1, filesList.size, "File should be in the root files list")
        val savedData = filesList[0]
        assertEquals(File.ID, savedData.id, "Type should be File.ID")

        val savedPath = when (savedData) {
            is Entity<*> -> savedData.getFieldValue<FilePath>(File.Field.PATH).get()
            is File -> savedData.path.get()
            else -> ""
        }
        assertEquals(targetPath, savedPath)

        // Verify data in file database
        val bytes = testProxy.fileDatabase.readBytes(targetPath)?.get()
        assertTrue(fileData.contentEquals(bytes), "File data should be preserved")
    }

    @Test
    fun uploadFile_failsIfAlreadyExists() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val fileName = "test.txt"
        val parentPath = "/"
        val fileData = byteArrayOf(1, 2, 3)

        // 1. Upload first time
        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to parentPath.toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to fileName.toTString(testProxy.tContext),
                UploadFile.Params.DATA to fileData.toTByteArray(testProxy.tContext)
            )
        )

        // 2. Upload second time with same name — Phase 3 of
        // `docs/proposals/core-error-channel.md`: impls throw `TenshuException`;
        // in-process `executionContext.executeAsync` propagates the throw (the
        // catch-all is at `LocalTaskExecutor`, on the wire path).
        val ex = kotlin.test.assertFailsWith<org.tenshu.core.TenshuException> {
            testProxy.executionContext.executeAsync<Data>(
                operationId = UploadFile.ID,
                caller = null,
                params = mapOf(
                    UploadFile.Params.PARENT_PATH to parentPath.toTString(testProxy.tContext),
                    UploadFile.Params.FILE_NAME to fileName.toTString(testProxy.tContext),
                    UploadFile.Params.DATA to fileData.toTByteArray(testProxy.tContext)
                )
            )
        }
        assertTrue(ex.error is AlreadyExistsError)
    }

    @Test
    fun uploadFile_preservesInsertionOrderAcrossResaves() = runTest {
        // Pins `ParentFilesRelationMaintainer`'s membership check: re-saving an
        // already-present File entity (e.g. sync pulling our own block back,
        // detail-view OnDemand byte materialisation, or any local property
        // update) must NOT reshuffle the parent folder's FILES array.
        //
        // Earlier behaviour did `removeFromArray + addToArray` on every save,
        // moving the entry to the end — visible to the user as the file list
        // reordering after navigating to a detail view and back.
        val testProxy = createFileProxy(backgroundScope)
        val fileData = byteArrayOf(1, 2, 3)
        val names = listOf("a.txt", "b.txt", "c.txt")

        for (name in names) {
            testProxy.executionContext.executeAsync<Data>(
                operationId = UploadFile.ID,
                caller = null,
                params = mapOf(
                    UploadFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                    UploadFile.Params.FILE_NAME to name.toTString(testProxy.tContext),
                    UploadFile.Params.DATA to fileData.toTByteArray(testProxy.tContext)
                )
            )
        }

        val rootId = DataId.StringDataId.create(type = File.ID, keyValue = "/")
        val ref = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                type = TList.ID,
                parentEntity = rootId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false
        )

        fun pathsInOrder(): List<String> =
            testProxy.executionContext.getArrayData(ref)!!.toList().map { entry ->
                when (entry) {
                    is Entity<*> -> entry.getFieldValue<FilePath>(File.Field.PATH).get()
                    is File -> entry.path.get()
                    else -> error("Unexpected entry shape: $entry")
                }
            }

        assertEquals(
            listOf("/a.txt", "/b.txt", "/c.txt"),
            pathsInOrder(),
            "Initial upload order should match insertion order"
        )

        // Re-save the first file's entity — same shape as a sync round-trip
        // re-applying our own block, or any other path that re-touches an
        // already-present entity.
        val firstFileEntity = testProxy.executionContext.database.getData(
            DataId.StringDataId.create(
                context = testProxy.tContext,
                type = File.ID,
                keyValue = "/a.txt"
            ),
            asEntity = true
        )!!
        testProxy.executionContext.saveData(firstFileEntity)

        assertEquals(
            listOf("/a.txt", "/b.txt", "/c.txt"),
            pathsInOrder(),
            "Re-saving an existing file must not reshuffle the FILES array"
        )
    }

    @Test
    fun fileDataAccessor_returnsNullForUnresolvedOnDemandPlaceholder() {
        // `SelectImplementation` writes a `PendingOnDemandProperty`
        // placeholder for any `Syncable(OnDemand)` relation whose value
        // can't be resolved locally. `OnDemandMaterialiser` is supposed
        // to substitute the placeholder before emission, but per its
        // doc-comment a property that fails to resolve "stays as a
        // placeholder" — the consumer is expected to detect the marker
        // and handle the gap.
        //
        // Folders inherit `File.Relation.Data` from `File.Schema` but
        // have no bytes to fetch, so the resolver never succeeds and
        // the placeholder survives all the way to the UI's flow
        // consumer. Without a safe cast in `Entity<out File>.data`,
        // accessing the property at `FileMapper.map` crashes with
        // `ClassCastException: PendingOnDemandPropertyDto cannot be
        // cast to TByteArray` — taking down the Drive process.
        val testProxy = createFileProxy()
        val path = "/folder-with-data-placeholder"

        val placeholder = org.tenshu.core.common.proxy.PendingOnDemandProperty.create(
            context = testProxy.tContext,
            entityId = DataId.StringDataId.create(
                context = testProxy.tContext,
                type = File.ID,
                keyValue = path
            ),
            propertyId = File.Data.id,
            params = null
        )

        val fileEntity = Entity.create<File>(
            context = testProxy.tContext,
            composite = File.create(
                context = testProxy.tContext,
                path = path,
            ),
            properties = mapOf(
                File.Data.id to placeholder
            )
        )

        // Pre-fix this access crashes with `ClassCastException`.
        // Post-fix the placeholder is collapsed to `null` — semantically
        // correct because the bytes aren't locally available.
        val bytes = fileEntity.get(File.Data)
        assertEquals(null, bytes, "Unresolved OnDemand placeholder must read as null, not crash")
    }

    @Test
    fun debug_checkDataIdHashConsistency() {
        val testProxy = createFileProxy()
        val path = "/debug.txt"
        val dataId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = path
        )
        val hash1 = dataId.getDataIdHash()

        val fileEntity = Entity.create<File>(
            context = testProxy.tContext,
            composite = File.create(
                context = testProxy.tContext,
                path = path,
            ),
            properties = emptyMap()
        )
        val generatedDataId = testProxy.executionContext.getDataId(fileEntity)!!
        val hash2 = generatedDataId.getDataIdHash()

        assertEquals(
            hash1,
            hash2,
            "Hashes should be identical for the same path. IDs: $dataId vs $generatedDataId"
        )
    }
}
