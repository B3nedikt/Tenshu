package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Traverse
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Engine-level test for `traverse` ([org.tenshu.core.common.proxy.TraverseImplementation]):
 * a recursive walk of the folder tree returns every descendant flattened. This
 * is the primitive HomeScreen recent-files (file-service-history Phase 6) builds
 * on: `root.files.traverse()` reaches files in nested folders, not just the
 * top level.
 */
class TraverseOperationTest {

    @Test
    fun traverse_returnsEveryDescendantOfTheTree() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val ctx = proxy.tContext

        suspend fun createFolder(path: String) = proxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID, caller = null,
            params = mapOf(CreateFolder.Params.PATH to path.toTString(ctx))
        )
        suspend fun upload(parent: String, name: String) = proxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID, caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to parent.toTString(ctx),
                UploadFile.Params.FILE_NAME to name.toTString(ctx),
                UploadFile.Params.DATA to byteArrayOf(1).toTByteArray(ctx),
            )
        )

        createFolder("/a")
        createFolder("/a/b")
        upload("/a/b", "f.txt")
        upload("/", "top.txt")

        // root.files as the caller
        val rootDataId = DataId.StringDataId.create(context = ctx, type = File.ID, keyValue = "/")
        val rootFiles = proxy.executionContext.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.EntityFieldDataId.create(
                    context = ctx,
                    type = TList.ID,
                    parentEntity = rootDataId as DataId.EntityDataId,
                    fieldRef = Folder.Fields.FILES
                ),
                isEntity = false,
                elementSchemaId = File.ID
            )
        )

        val result = proxy.executionContext.execute<Data>(
            operationId = Traverse.ID,
            caller = rootFiles,
            params = null,
        )

        val paths = (result as TList<*>).toList().map { pathOf(it) }.toSet()
        assertEquals(
            setOf("/a", "/a/b", "/a/b/f.txt", "/top.txt"),
            paths,
            "traverse reaches every file/folder in the tree, not just the top level"
        )
    }

    private fun pathOf(data: Data): String = when (data) {
        is Entity<*> -> data.getFieldValue<TString>(File.Field.PATH).get()
        else -> (data as org.tenshu.core.Composite).getField<TString>(File.Field.PATH).get()
    }
}
