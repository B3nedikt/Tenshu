package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.MoveFile
import org.tenshu.core.file.system.StillSyncingError
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A folder move must not leave a child behind that it could not read.
 *
 * `MoveFile` walks the folder's `Folder.FILES` list and moves each member, then retires the source
 * path. A member whose row the store does not hold is **skipped** by that read
 * (`DatabaseImpl.reportMissingMembers`); moved around, it would be left behind while its parent was
 * retired, and when the row turned up it would name a folder that no longer exists: a file in the
 * realm that no folder lists.
 *
 * So the move refuses, with [StillSyncingError] and before its first write, whenever any folder in
 * the subtree reads short. The fixture makes the short read the way the store does: the child's row
 * is removed at the store layer, so the folder's list keeps the reference.
 */
class MoveFolderIncompleteSubtreeTest {

    @Test
    fun aFolderMoveIsRefused_whenAChildRowIsMissing_andNothingMoves() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        createFolder(testProxy, "/album")
        upload(testProxy, "/album", "a.bin")
        upload(testProxy, "/album", "b.bin")
        val unseen = loseRow(testProxy, "/album/b.bin")

        val result = runCatching { move(testProxy, "/album", "/album2") }

        assertRefusedAsStillSyncing(result)
        assertNotNull(row(testProxy, "/album"), "the source folder stays")
        assertNotNull(row(testProxy, "/album/a.bin"), "the readable child stays where it was")
        assertNull(row(testProxy, "/album2"), "no target folder was written")
        assertEquals(2, memberHashes(testProxy, "/album")?.size, "the folder still names both children")

        // The missing row arrives. The folder it names still exists, so the file is reachable, and
        // the retried move carries it along.
        testProxy.executionContext.saveData(unseen)
        move(testProxy, "/album", "/album2")

        assertEquals(listOf("/album2/a.bin", "/album2/b.bin"), childPaths(testProxy, "/album2").sorted())
        assertNull(row(testProxy, "/album"), "the source is retired only once everything under it moved")
    }

    @Test
    fun aFolderMoveIsRefused_whenARowIsMissingDeeperInTheSubtree() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        createFolder(testProxy, "/album")
        createFolder(testProxy, "/album/sub")
        upload(testProxy, "/album", "a.bin")
        upload(testProxy, "/album/sub", "c.bin")
        loseRow(testProxy, "/album/sub/c.bin")

        assertRefusedAsStillSyncing(runCatching { move(testProxy, "/album", "/album2") })

        assertNull(row(testProxy, "/album2"), "nothing of the tree moved")
        assertNull(row(testProxy, "/album2/a.bin"), "not even the siblings the walk could read")
        assertNotNull(row(testProxy, "/album/sub"))
    }

    /** A complete subtree, an empty folder among it, moves. */
    @Test
    fun aCompleteFolderMovesAsBefore_includingAnEmptySubfolder() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        createFolder(testProxy, "/album")
        createFolder(testProxy, "/album/empty")
        upload(testProxy, "/album", "a.bin")

        move(testProxy, "/album", "/album2")

        assertEquals(listOf("/album2/a.bin", "/album2/empty"), childPaths(testProxy, "/album2").sorted())
        assertNull(row(testProxy, "/album"))
    }

    @Test
    fun anEmptyFolderMoves() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        createFolder(testProxy, "/empty")

        move(testProxy, "/empty", "/empty2")

        assertNotNull(row(testProxy, "/empty2"))
        assertNull(row(testProxy, "/empty"))
    }

    // ---- fixture ----

    private fun assertRefusedAsStillSyncing(result: Result<Data?>) {
        assertTrue(result.isFailure, "the move must be refused, not applied short")
        assertTrue(
            result.exceptionOrNull()?.message?.contains(StillSyncingError.ID) == true,
            "refused with the typed StillSyncingError: ${result.exceptionOrNull()?.message}",
        )
    }

    /**
     * Removes [path]'s row at the store layer only, as a row this device lacks: the parent's list
     * keeps its reference, which `ExecutionContext.deleteData` would have unlinked. Returns the row
     * as it was, for the test to deliver later.
     */
    private fun loseRow(testProxy: TestProxy, path: String): Entity<*> {
        val dataId = fileId(testProxy, path)
        val entity = testProxy.database.getData(dataId, asEntity = true) as Entity<*>
        testProxy.database.deleteData(dataId)
        assertNull(row(testProxy, path))
        return entity
    }

    private fun fileId(testProxy: TestProxy, path: String) =
        DataId.StringDataId.create(context = testProxy.tContext, type = File.ID, keyValue = path)

    private fun row(testProxy: TestProxy, path: String): Data? = testProxy.database.getData(fileId(testProxy, path))

    private fun filesRef(testProxy: TestProxy, folderPath: String) = ArrayDatabaseReference(
        arrayId = DataId.EntityFieldDataId.create(
            context = testProxy.tContext,
            type = TList.ID,
            parentEntity = fileId(testProxy, folderPath),
            fieldRef = Folder.Fields.FILES,
        ),
        isEntity = false,
        elementSchemaId = File.ID,
    )

    private fun memberHashes(testProxy: TestProxy, folderPath: String) =
        testProxy.database.getArrayMemberHashes(filesRef(testProxy, folderPath))

    private fun childPaths(testProxy: TestProxy, folderPath: String): List<String> =
        testProxy.executionContext.getArrayData(filesRef(testProxy, folderPath))!!.toList().map {
            when (it) {
                is Entity<*> -> it.getFieldValue<FilePath>(File.Field.PATH).get()
                is File -> it.path.get()
                else -> ""
            }
        }

    private suspend fun createFolder(testProxy: TestProxy, path: String) {
        testProxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to path.toTString(testProxy.tContext)),
        )
    }

    private suspend fun upload(testProxy: TestProxy, parent: String, name: String) {
        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to parent.toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to name.toTString(testProxy.tContext),
                UploadFile.Params.DATA to byteArrayOf(1, 2, 3).toTByteArray(testProxy.tContext),
            ),
        )
    }

    private suspend fun move(testProxy: TestProxy, source: String, target: String) =
        testProxy.executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to source.toTString(testProxy.tContext),
                MoveFile.Params.TARGET_PATH to target.toTString(testProxy.tContext),
            ),
        )
}
