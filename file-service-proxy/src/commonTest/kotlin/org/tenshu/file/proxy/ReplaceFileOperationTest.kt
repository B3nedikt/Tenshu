package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.file.system.ReplaceFile
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertTrue

class ReplaceFileOperationTest {

    @Test
    fun replaceFile_overwritesExistingInPlace_withoutAlreadyExists() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val fileName = "doc.txt"
        val parentPath = "/"
        val targetPath = "/$fileName"
        val original = byteArrayOf(1, 1, 1)
        val replacement = byteArrayOf(9, 9, 9, 9)

        testProxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to parentPath.toTString(testProxy.tContext),
                UploadFile.Params.FILE_NAME to fileName.toTString(testProxy.tContext),
                UploadFile.Params.DATA to original.toTByteArray(testProxy.tContext)
            )
        )

        // Replace must NOT fail with AlreadyExistsError — it overwrites in place.
        testProxy.executionContext.executeAsync<Data>(
            operationId = ReplaceFile.ID,
            caller = null,
            params = mapOf(
                ReplaceFile.Params.PARENT_PATH to parentPath.toTString(testProxy.tContext),
                ReplaceFile.Params.FILE_NAME to fileName.toTString(testProxy.tContext),
                ReplaceFile.Params.DATA to replacement.toTByteArray(testProxy.tContext)
            )
        )

        val bytes = testProxy.fileDatabase.readBytes(targetPath)?.get()
        assertTrue(
            replacement.contentEquals(bytes),
            "File bytes should be the replacement, were ${bytes?.toList()}",
        )
    }

    @Test
    fun replaceFile_createsWhenAbsent() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val fileName = "fresh.txt"
        val data = byteArrayOf(4, 2)

        testProxy.executionContext.executeAsync<Data>(
            operationId = ReplaceFile.ID,
            caller = null,
            params = mapOf(
                ReplaceFile.Params.PARENT_PATH to "/".toTString(testProxy.tContext),
                ReplaceFile.Params.FILE_NAME to fileName.toTString(testProxy.tContext),
                ReplaceFile.Params.DATA to data.toTByteArray(testProxy.tContext)
            )
        )

        val bytes = testProxy.fileDatabase.readBytes("/$fileName")?.get()
        assertTrue(data.contentEquals(bytes), "Upserted bytes should be present")
    }
}
