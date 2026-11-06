package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.path
import org.tenshu.core.file.system.AlreadyExistsError
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.id
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreateFolderOperationTest {

    @Test
    fun createFolder_savesCorrectType() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val path = "/myfolder"

        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(
                CreateFolder.Params.PATH to path.toTString(testProxy.tContext)
            )
        )


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

        assertEquals(1, filesList.size, "Folder should be in the root files list")
        val savedData = filesList[0]
        assertEquals(Folder.ID, savedData.id, "Type should be Folder.ID")

        @Suppress("UNCHECKED_CAST")
        val savedPath = when (savedData) {
            is Entity<*> -> (savedData as Entity<out File>).path.get()
            is File -> savedData.path.get()
            else -> ""
        }
        assertEquals(path, savedPath)
    }

    @Test
    fun createFolder_failsIfAlreadyExists() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val path = "/myfolder"

        // 1. Create first time
        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(
                CreateFolder.Params.PATH to path.toTString(testProxy.tContext)
            )
        )

        // 2. Create second time — Phase 3 of `docs/proposals/core-error-channel.md`:
        // impls throw typed `TenshuException`; in-process `executionContext.executeAsync`
        // does not catch (the catch-all is at `LocalTaskExecutor`, on the wire path).
        val ex = kotlin.test.assertFailsWith<org.tenshu.core.TenshuException> {
            testProxy.executionContext.executeAsync<Data>(
                operationId = CreateFolder.ID,
                caller = null,
                params = mapOf(
                    CreateFolder.Params.PATH to path.toTString(testProxy.tContext)
                )
            )
        }
        assertTrue(ex.error is AlreadyExistsError)
    }
}
