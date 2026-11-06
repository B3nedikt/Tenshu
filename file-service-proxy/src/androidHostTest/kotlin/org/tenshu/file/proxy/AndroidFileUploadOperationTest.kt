package org.tenshu.file.proxy

import android.os.ParcelFileDescriptor
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.file.system.AlreadyExistsError
import org.tenshu.core.file.system.UploadSourceFile
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileTestRegistry
import org.tenshu.files.util.ParcelFileDescriptorCache
import java.io.FileOutputStream
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File as JFile

class AndroidFileUploadOperationTest {

    @Test
    @Ignore("Currently no way of mocking android API integrated like Robolectric")
    fun uploadSourceFile_failsIfAlreadyExists() {
        val fileDescriptorCache = ParcelFileDescriptorCache()
        val testProxy = TestProxy(
            schemaRegistry = createFileTestRegistry(),
            implementationFactoryOverride = { id, proxy ->
                implementationFactory(id)
                    ?: fileServiceProxyImplementationFactory(id, proxy.fileDatabase)
                    ?: androidFileServiceProxyImplementationFactory(
                        id,
                        proxy.fileDatabase,
                        fileDescriptorCache
                    )
            }
        ).apply {
            executionContext.initFileService()
        }

        val fileName = "android_test.txt"
        val parentPath = "/"
        val uri = "content://mock/1"

        // 1. Create a temp file to act as the source
        val tempFile = JFile.createTempFile("mock", ".txt")
        FileOutputStream(tempFile).use { it.write(byteArrayOf(1, 2, 3)) }
        val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)

        // 2. Add to cache (UploadSourceFileImpl uses this)
        fileDescriptorCache.put(uri, pfd)

        // 3. First upload
        testProxy.executionContext.execute<Data>(
            operationId = UploadSourceFile.ID,
            caller = null,
            params = mapOf(
                UploadSourceFile.Params.PARENT_PATH to parentPath.toTString(testProxy.tContext),
                UploadSourceFile.Params.FILE_NAME to fileName.toTString(testProxy.tContext),
                UploadSourceFile.Params.URI to uri.toTString(testProxy.tContext)
            )
        )

        // 4. Second upload with same name
        val result = testProxy.executionContext.execute<Data>(
            operationId = UploadSourceFile.ID,
            caller = null,
            params = mapOf(
                UploadSourceFile.Params.PARENT_PATH to parentPath.toTString(testProxy.tContext),
                UploadSourceFile.Params.FILE_NAME to fileName.toTString(testProxy.tContext),
                UploadSourceFile.Params.URI to uri.toTString(testProxy.tContext)
            )
        )

        assertTrue(result is TResult.Failure<*>, "Android upload should fail when file exists")
        val error = (result as TResult.Failure<*>).error
        assertTrue(
            error is AlreadyExistsError || error.schema.id.get() == AlreadyExistsError.ID,
            "Error should be AlreadyExists but was ${error.schema.id.get()}"
        )

        tempFile.delete()
    }
}
