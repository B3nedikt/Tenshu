package org.tenshu.core.common.proxy.file

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.file.system.ReplaceSourceFile
import org.tenshu.file.impl.UploadFileImplementation
import org.tenshu.file.proxy.MotionThumbnailRenderer
import org.tenshu.files.util.ParcelFileDescriptorCache
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.sync.common.SyncBlockEmitterHolder
import java.io.FileInputStream

/** Android counterpart of [org.tenshu.file.impl.ReplaceFileImplementation]:
 *  reads bytes from the cached FD then delegates to the shared upload core
 *  with `replaceExisting = true` (in-place overwrite, no tombstone). */
internal class AndroidFileReplaceImplementation(
    private val fileDatabase: FileDatabase,
    private val fileDescriptorCache: ParcelFileDescriptorCache,
    private val syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    private val deviceId: String? = null,
    private val storageCapacityHolder: StorageCapacityHolder? = null,
    /** M4: the `MOTION`-class mint — an overwrite re-mints every class. */
    private val motionThumbnailRenderer: MotionThumbnailRenderer? = null,
) : AsyncOperationImplementation {

    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val parentPath: TString = params.read(ReplaceSourceFile.Params.PARENT_PATH)
        val fileName: TString = params.read(ReplaceSourceFile.Params.FILE_NAME)
        val uri: TString = params.read(ReplaceSourceFile.Params.URI)

        // Sender-scoped, and consumed on read — see the twin in AndroidFileUploadImplementation.
        val clientId = executionContext.clientId
            ?: error("Replace has no client identity — refusing to read a descriptor for ${uri.get()}")
        val parcelFileDescriptor = fileDescriptorCache.getFrom(clientId, uri.get())
            ?: error("File descriptor not found for ${uri.get()}")

        val fileBytes = try {
            FileInputStream(parcelFileDescriptor.fileDescriptor)
                .use(FileInputStream::readBytes)
        } finally {
            fileDescriptorCache.removeFrom(clientId, uri.get())
        }

        val result = UploadFileImplementation(
            fileDatabase, syncBlockEmitterHolder, deviceId, storageCapacityHolder,
            motionThumbnailRenderer = motionThumbnailRenderer,
        ).uploadFile(
            executionContext = executionContext,
            parentPath = parentPath.get(),
            fileName = fileName.get(),
            data = fileBytes,
            replaceExisting = true
        )
        if (result is TResult.Failure<*>) {
            throw TenshuException(result.error)
        }
        @Suppress("UNCHECKED_CAST")
        return (result as TResult.Success<*>).value as T
    }
}
