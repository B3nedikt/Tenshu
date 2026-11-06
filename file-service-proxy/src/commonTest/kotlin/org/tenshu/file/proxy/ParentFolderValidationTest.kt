package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.NotFoundError
import org.tenshu.core.TenshuException
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.MoveFile
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.core.common.TestProxy
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A write may not land under a parent folder that does not exist.
 *
 * Every writer checked only that its *target* path was free, so `/Photos/a/b` with no `/Photos/a`
 * produced a row addressable by path and in **no folder's `FILES` list** — invisible in every
 * listing and unreachable by the UI that would delete it. The client-side name fences
 * (`ios-drive-parity.md` §27.1/§28) close the one route a user could take; these ops are the
 * state's actual guard, for every caller.
 */
class ParentFolderValidationTest {

    private suspend fun TestProxy.createFolder(path: String) =
        executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to path.toTString(tContext)),
        )

    private suspend fun TestProxy.upload(parentPath: String, fileName: String) =
        executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to parentPath.toTString(tContext),
                UploadFile.Params.FILE_NAME to fileName.toTString(tContext),
                UploadFile.Params.DATA to byteArrayOf(1, 2, 3).toTByteArray(tContext),
            ),
        )

    private suspend fun TestProxy.move(sourcePath: String, targetPath: String) =
        executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to sourcePath.toTString(tContext),
                MoveFile.Params.TARGET_PATH to targetPath.toTString(tContext),
            ),
        )

    private fun TestProxy.row(path: String): Data? =
        executionContext.database.getData(
            DataId.StringDataId.create(type = File.ID, keyValue = path)
        )

    @Test
    fun createFolder_refusesAnAbsentParent() = runTest {
        val proxy = createFileProxy(backgroundScope)

        val ex = assertFailsWith<TenshuException> { proxy.createFolder("/Photos/a") }

        assertTrue(ex.error is NotFoundError, "expected NotFoundError, got ${ex.error}")
        // …and wrote nothing. The orphan is the bug; a refusal that still saved would be worse.
        assertNull(proxy.row("/Photos/a"))
    }

    @Test
    fun createFolder_acceptsAParentCreatedFirst() = runTest {
        val proxy = createFileProxy(backgroundScope)

        // Top-down, exactly as `TenshuFilesRepository.ensureFolderChain` walks it.
        proxy.createFolder("/Photos")
        proxy.createFolder("/Photos/a")

        assertNotNull(proxy.row("/Photos/a"), "a nested folder under a real parent still lands")
    }

    @Test
    fun createFolder_refusesAParentThatIsAFile() = runTest {
        val proxy = createFileProxy(backgroundScope)
        proxy.upload("/", "notes.txt")

        val ex = assertFailsWith<TenshuException> { proxy.createFolder("/notes.txt/inner") }

        assertTrue(ex.error is NotFoundError, "expected NotFoundError, got ${ex.error}")
        assertNull(proxy.row("/notes.txt/inner"))
    }

    @Test
    fun uploadFile_refusesAnAbsentParent() = runTest {
        val proxy = createFileProxy(backgroundScope)

        val ex = assertFailsWith<TenshuException> { proxy.upload("/Photos", "a.txt") }

        assertTrue(ex.error is NotFoundError, "expected NotFoundError, got ${ex.error}")
        assertNull(proxy.row("/Photos/a.txt"))
    }

    @Test
    fun moveFile_refusesAnAbsentTargetParent() = runTest {
        val proxy = createFileProxy(backgroundScope)
        proxy.upload("/", "a.txt")

        // The shape a typed `sub/a.txt` in the rename field used to produce.
        val ex = assertFailsWith<TenshuException> { proxy.move("/a.txt", "/sub/a.txt") }

        assertTrue(ex.error is NotFoundError, "expected NotFoundError, got ${ex.error}")
        assertNull(proxy.row("/sub/a.txt"), "nothing was written at the orphan path")
        assertNotNull(proxy.row("/a.txt"), "and the source is untouched")
    }

    @Test
    fun moveFile_stillMovesIntoAFolderThatExists() = runTest {
        val proxy = createFileProxy(backgroundScope)
        proxy.upload("/", "a.txt")
        proxy.createFolder("/Photos")

        proxy.move("/a.txt", "/Photos/a.txt")

        assertNotNull(proxy.row("/Photos/a.txt"))
        assertNull(proxy.row("/a.txt"))
    }
}
