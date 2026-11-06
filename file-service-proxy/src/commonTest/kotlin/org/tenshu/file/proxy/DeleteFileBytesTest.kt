package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.DeleteFile
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * `DeleteFile` used to clear a file's bytes with
 * `writeRawByteArray(path, byteArrayOf())` ("In simple mock DB, we just clear
 * it"), and `deleteRecursively` deleted child *entities* without touching their
 * bytes at all. Two leaks:
 *
 *  1. A deleted file left a **0-byte ghost** on disk. That is not the same as
 *     absent — `fileSize` answers `0L` rather than `null`, so `HasLocalData`
 *     reports a deleted path as still downloaded, and (per the OnDemand audit)
 *     a non-null empty read is exactly what stops the resolver from ever
 *     re-fetching a path.
 *  2. Deleting a **folder** stranded every descendant's plaintext on disk
 *     forever — and bytes, not entities, are what fills a device up.
 */
class DeleteFileBytesTest {

    @Test
    fun deletingAFile_removesItsBytes_leavingNoZeroByteGhost() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = "device-1")

        proxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(proxy.tContext),
                UploadFile.Params.FILE_NAME to "a.txt".toTString(proxy.tContext),
                UploadFile.Params.DATA to ByteArray(64).toTByteArray(proxy.tContext),
            ),
        )

        proxy.executionContext.executeAsync<Data>(
            operationId = DeleteFile.ID,
            caller = null,
            params = mapOf(DeleteFile.Params.PATH to "/a.txt".toTString(proxy.tContext)),
        )

        assertNull(
            proxy.fileDatabase.fileSize("/a.txt"),
            "bytes must be gone, not zeroed (a 0-byte file reads as 'downloaded')",
        )
    }

    @Test
    fun deletingAFolder_removesItsDescendantsBytes() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = "device-1")

        proxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to "/parent".toTString(proxy.tContext)),
        )
        proxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to "/parent/sub".toTString(proxy.tContext)),
        )
        proxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/parent".toTString(proxy.tContext),
                UploadFile.Params.FILE_NAME to "a.txt".toTString(proxy.tContext),
                UploadFile.Params.DATA to ByteArray(64).toTByteArray(proxy.tContext),
            ),
        )
        proxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/parent/sub".toTString(proxy.tContext),
                UploadFile.Params.FILE_NAME to "b.txt".toTString(proxy.tContext),
                UploadFile.Params.DATA to ByteArray(128).toTByteArray(proxy.tContext),
            ),
        )

        proxy.executionContext.executeAsync<Data>(
            operationId = DeleteFile.ID,
            caller = null,
            params = mapOf(DeleteFile.Params.PATH to "/parent".toTString(proxy.tContext)),
        )

        // Every descendant's plaintext goes with the folder — at any depth.
        assertNull(proxy.fileDatabase.fileSize("/parent/a.txt"), "child bytes must be deleted")
        assertNull(
            proxy.fileDatabase.fileSize("/parent/sub/b.txt"),
            "nested child bytes must be deleted too",
        )
    }
}
