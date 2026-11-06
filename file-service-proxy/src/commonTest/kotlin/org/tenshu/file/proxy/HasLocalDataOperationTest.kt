package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TBoolean
import org.tenshu.core.common.TestProxy
import org.tenshu.core.file.system.EvictFileBytes
import org.tenshu.core.file.system.HasLocalData
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals

class HasLocalDataOperationTest {

    /**
     * The v2 byte-presence probe (`docs/proposals/drive-offline-online.md` §3):
     * `hasLocalData` reflects whether the file's bytes are on local disk right
     * now — independent of the synced entity. An uploaded file has bytes
     * (`true`); after eviction the entity survives but the bytes are gone
     * (`false`); a path that was never written reads `false`.
     */
    @Test
    fun `hasLocalData tracks on-disk byte presence not the entity`() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val path = "/note.txt"

        // Absent before anything is written.
        assertEquals(
            false,
            probe(testProxy, path),
            "a never-written path has no local bytes"
        )

        testProxy.executionContext.executeAsync<TBoolean>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(),
                UploadFile.Params.FILE_NAME to "note.txt".toTString(),
                UploadFile.Params.DATA to byteArrayOf(1, 2, 3, 4).toTByteArray(testProxy.tContext)
            )
        )

        assertEquals(
            true,
            probe(testProxy, path),
            "bytes are on disk after upload"
        )

        testProxy.executionContext.executeAsync<TBoolean>(
            operationId = EvictFileBytes.ID,
            caller = null,
            params = mapOf(EvictFileBytes.Params.PATH to path.toTString())
        )

        assertEquals(
            false,
            probe(testProxy, path),
            "eviction removes the local bytes, so presence flips back to false " +
                "even though the file entity still exists"
        )
    }

    private suspend fun probe(
        testProxy: TestProxy,
        path: String
    ): Boolean = testProxy.executionContext.executeAsync<TBoolean>(
        operationId = HasLocalData.ID,
        caller = null,
        params = mapOf(HasLocalData.Params.PATH to path.toTString())
    )?.get() ?: false
}
