package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.DeleteFile
import org.tenshu.core.file.notifications.FileDeletedNotification
import org.tenshu.core.file.notifications.FolderDeletedNotification
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeleteFileOperationTest {

    /**
     * A folder's delete is announced as `FolderDeletedNotification`, a file's as plain
     * `FileDeletedNotification` — Control's feed titles the row "Folder Deleted" / "File Deleted"
     * from exactly this type, so a folder announced as a file reads as the wrong thing.
     */
    @Test
    fun `deleting a folder emits a folder notification and deleting a file a file one`() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val ctx = testProxy.executionContext
        ctx.executeAsync<Data>(
            operationId = CreateFolder.ID, caller = null,
            params = mapOf(CreateFolder.Params.PATH to "/Taxes".toTString()),
        )
        ctx.executeAsync<Data>(
            operationId = UploadFile.ID, caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(),
                UploadFile.Params.FILE_NAME to "a.txt".toTString(),
                UploadFile.Params.DATA to byteArrayOf(1).toTByteArray(testProxy.tContext),
            ),
        )
        ctx.executeAsync<Data>(
            operationId = DeleteFile.ID, caller = null,
            params = mapOf(DeleteFile.Params.PATH to "/Taxes".toTString()),
        )
        ctx.executeAsync<Data>(
            operationId = DeleteFile.ID, caller = null,
            params = mapOf(DeleteFile.Params.PATH to "/a.txt".toTString()),
        )

        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = ctx.context,
                type = TList.ID,
                relationId = NotificationService.Relations.NOTIFICATIONS,
            ),
            isEntity = false,
            elementSchemaId = Notification.ID,
        )
        val all = (ctx.getArrayData(ref)?.toList() ?: emptyList())
        val types: List<String> = all.map { (it as Data).schema.id.toString() }
        val deletes = types
            .filter { it == FileDeletedNotification.ID || it == FolderDeletedNotification.ID }
            .sorted()
        assertEquals(
            listOf(FolderDeletedNotification.ID, FileDeletedNotification.ID).sorted(), deletes,
            "notifications held: $types",
        )
    }

    @Test
    fun `deleting a folder and re-creating it with same name works`() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val path = "/test_folder"

        // 1. Create folder
        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to path.toTString())
        )

        val dataId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = path
        )
        assertNotNull(testProxy.database.getData(dataId), "Folder should exist after creation")

        // 2. Delete folder
        testProxy.executionContext.executeAsync<Data>(
            operationId = DeleteFile.ID,
            caller = null,
            params = mapOf(DeleteFile.Params.PATH to path.toTString())
        )

        // 3. Verify it is gone from database
        assertNull(
            testProxy.database.getData(dataId),
            "Folder should be gone from database after deletion"
        )

        // 4. Create folder again with same name
        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to path.toTString())
        )
    }

    @Test
    fun `deleting a folder also deletes its children recursively`() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val folderPath = "/parent"
        val filePath = "/parent/file.txt"

        // 1. Create folder and file
        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to folderPath.toTString())
        )

        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to folderPath.toTString(),
                UploadFile.Params.FILE_NAME to "file.txt".toTString(),
                UploadFile.Params.DATA to byteArrayOf(1, 2, 3).toTByteArray(testProxy.tContext)
            )
        )

        val folderId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = folderPath
        )
        val fileId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = filePath
        )

        assertNotNull(testProxy.database.getData(folderId), "Folder should exist")
        assertNotNull(testProxy.database.getData(fileId), "File should exist")

        // 2. Delete parent folder
        testProxy.executionContext.executeAsync<Data>(
            operationId = DeleteFile.ID,
            caller = null,
            params = mapOf(DeleteFile.Params.PATH to folderPath.toTString())
        )

        // 3. Verify both are gone
        assertNull(testProxy.database.getData(folderId), "Folder should be gone")
        assertNull(
            testProxy.database.getData(fileId),
            "File inside folder should be gone recursively"
        )
    }

    /**
     * Regression for the "ghost file" symptom reported after the folder-upload
     * landing: upload a folder containing files, delete it, re-upload the
     * identical structure — the second upload surfaces "Item already exists".
     *
     * The folder-upload walk is `ensureFolder` (idempotent on AlreadyExists)
     * followed by per-file uploads, so a leftover *file* row under the
     * deleted path is what trips the conflict. This test does both halves —
     * recreate the folder *and* re-upload each child at its original path —
     * to lock in that no descendant survives the delete in a re-uploadable
     * shape.
     */
    @Test
    fun `delete then re-upload a folder with multiple files succeeds`() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val folderPath = "/photos"
        val firstFile = "a.txt"
        val secondFile = "b.txt"
        val firstFilePath = "$folderPath/$firstFile"
        val secondFilePath = "$folderPath/$secondFile"

        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to folderPath.toTString())
        )
        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to folderPath.toTString(),
                UploadFile.Params.FILE_NAME to firstFile.toTString(),
                UploadFile.Params.DATA to byteArrayOf(1).toTByteArray(testProxy.tContext)
            )
        )
        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to folderPath.toTString(),
                UploadFile.Params.FILE_NAME to secondFile.toTString(),
                UploadFile.Params.DATA to byteArrayOf(2).toTByteArray(testProxy.tContext)
            )
        )

        testProxy.executionContext.executeAsync<Data>(
            operationId = DeleteFile.ID,
            caller = null,
            params = mapOf(DeleteFile.Params.PATH to folderPath.toTString())
        )

        val folderId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = folderPath
        )
        val firstId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = firstFilePath
        )
        val secondId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = secondFilePath
        )
        assertNull(testProxy.database.getData(folderId), "folder row should be gone")
        assertNull(testProxy.database.getData(firstId), "first child row should be gone")
        assertNull(
            testProxy.database.getData(secondId),
            "second child row should be gone — sibling ghost is the actual user symptom"
        )

        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to folderPath.toTString())
        )

        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to folderPath.toTString(),
                UploadFile.Params.FILE_NAME to firstFile.toTString(),
                UploadFile.Params.DATA to byteArrayOf(1).toTByteArray(testProxy.tContext)
            )
        )

        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to folderPath.toTString(),
                UploadFile.Params.FILE_NAME to secondFile.toTString(),
                UploadFile.Params.DATA to byteArrayOf(2).toTByteArray(testProxy.tContext)
            )
        )
    }

    /**
     * The single-file recursive-delete test only walks one level deep; the
     * user's reported upload is a *folder tree*. This pins that
     * `deleteRecursively` follows subfolder edges — if it stops at the first
     * level, descendants under `/root/sub` would survive and block re-upload
     * of any file at a deeper path.
     */
    @Test
    fun `deleting a folder recursively removes nested subfolders and their files`() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val root = "/root"
        val sub = "/root/sub"
        val deep = "/root/sub/deep"
        val rootFile = "/root/top.txt"
        val subFile = "/root/sub/mid.txt"
        val deepFile = "/root/sub/deep/leaf.txt"

        for (folder in listOf(root, sub, deep)) {
            testProxy.executionContext.executeAsync<Data>(
                operationId = CreateFolder.ID,
                caller = null,
                params = mapOf(CreateFolder.Params.PATH to folder.toTString())
            )
        }
        for ((parent, name) in listOf(
            root to "top.txt",
            sub to "mid.txt",
            deep to "leaf.txt"
        )) {
            testProxy.executionContext.executeAsync<Data>(
                operationId = UploadFile.ID,
                caller = null,
                params = mapOf(
                    UploadFile.Params.PARENT_PATH to parent.toTString(),
                    UploadFile.Params.FILE_NAME to name.toTString(),
                    UploadFile.Params.DATA to byteArrayOf(7).toTByteArray(testProxy.tContext)
                )
            )
        }

        fun id(path: String) = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = path
        )

        assertNotNull(testProxy.database.getData(id(deepFile)), "deep file should be seeded")

        testProxy.executionContext.executeAsync<Data>(
            operationId = DeleteFile.ID,
            caller = null,
            params = mapOf(DeleteFile.Params.PATH to root.toTString())
        )

        for (path in listOf(root, sub, deep, rootFile, subFile, deepFile)) {
            assertNull(
                testProxy.database.getData(id(path)),
                "$path should be gone after recursive delete of /root"
            )
        }

        // And the full nested structure must be uploadable again.
        for (folder in listOf(root, sub, deep)) {
            testProxy.executionContext.executeAsync<Data>(
                operationId = CreateFolder.ID,
                caller = null,
                params = mapOf(CreateFolder.Params.PATH to folder.toTString())
            )
        }
        for ((parent, name) in listOf(
            root to "top.txt",
            sub to "mid.txt",
            deep to "leaf.txt"
        )) {
            testProxy.executionContext.executeAsync<Data>(
                operationId = UploadFile.ID,
                caller = null,
                params = mapOf(
                    UploadFile.Params.PARENT_PATH to parent.toTString(),
                    UploadFile.Params.FILE_NAME to name.toTString(),
                    UploadFile.Params.DATA to byteArrayOf(8).toTByteArray(testProxy.tContext)
                )
            )
        }
    }
}
