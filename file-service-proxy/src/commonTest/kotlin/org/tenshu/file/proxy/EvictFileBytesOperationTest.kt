package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.system.EvictFileBytes
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EvictFileBytesOperationTest {

    /**
     * Eviction drops the on-disk bytes but leaves the synced `File` entity and
     * its metadata intact — the make-online-only half of the offline toggle
     * (`docs/proposals/drive-offline-online.md` §2). This is the load-bearing
     * difference from `DeleteFile`, which removes the entity entirely.
     */
    @Test
    fun `evict drops local bytes but keeps the file entity`() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val parent = "/"
        val name = "note.txt"
        val path = "/note.txt"

        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to parent.toTString(),
                UploadFile.Params.FILE_NAME to name.toTString(),
                UploadFile.Params.DATA to byteArrayOf(1, 2, 3, 4).toTByteArray(testProxy.tContext)
            )
        )

        val fileId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = File.ID,
            keyValue = path
        )
        assertNotNull(testProxy.database.getData(fileId), "file entity should exist after upload")
        assertNotNull(testProxy.fileDatabase.fileSize(path), "bytes should be on disk after upload")

        testProxy.executionContext.executeAsync<Data>(
            operationId = EvictFileBytes.ID,
            caller = null,
            params = mapOf(EvictFileBytes.Params.PATH to path.toTString())
        )

        assertNull(
            testProxy.fileDatabase.fileSize(path),
            "local bytes should be gone after eviction"
        )
        assertNotNull(
            testProxy.database.getData(fileId),
            "the file entity + metadata must survive eviction — evict is not a delete"
        )
    }
}
