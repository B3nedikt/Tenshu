package org.tenshu.file.proxy.ios

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
import org.tenshu.core.file.system.UploadSourceFile
import org.tenshu.core.number.TInteger
import org.tenshu.file.impl.UploadFileImplementation
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.sync.common.SyncBlockEmitterHolder

/**
 * iOS implementation of [UploadSourceFile] — the structural twin of
 * `AndroidFileUploadImplementation`, differing only in how the source reference becomes bytes.
 *
 * Android's twin exists to cross a Binder boundary: Drive holds the `content://` grant, Control
 * does not, so the client plants a descriptor and this step resolves it. iOS route-1 has no such
 * boundary — the runtime is in-process — so the op exists here for the *other* reason the Android
 * one gets for free: the read happens **inside** the operation, where `LocalTaskExecutor`'s
 * catch-all turns a failure into a `TResult` instead of an `abort()`.
 * See `docs/proposals/drive-upload-source-read.md`.
 */
internal class IosUploadSourceFileImplementation(
    private val fileDatabase: FileDatabase,
    private val reader: IosUploadSourceReader,
    private val syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    private val deviceId: String? = null,
    private val storageCapacityHolder: StorageCapacityHolder? = null,
) : AsyncOperationImplementation {

    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val parentPath: TString = params.read(UploadSourceFile.Params.PARENT_PATH)
        val fileName: TString = params.read(UploadSourceFile.Params.FILE_NAME)
        val uri: TString = params.read(UploadSourceFile.Params.URI)
        // Optional. The client reads the source's own date where it holds the grant to do so — for
        // a PhotoKit asset that is the photo's creationDate, which is closer to the truth than the
        // import-moment mtime a picker copy carries. Absent means "fall back to now", proxy-side.
        val sourceModifiedAt: Long? =
            (params?.get(UploadSourceFile.Params.SOURCE_MODIFIED_AT) as? TInteger)?.get()

        val result = UploadFileImplementation(
            fileDatabase, syncBlockEmitterHolder, deviceId, storageCapacityHolder,
        ).uploadFile(
            executionContext = executionContext,
            parentPath = parentPath.get(),
            fileName = fileName.get(),
            data = reader.readBytes(uri.get()),
            sourceModifiedAt = sourceModifiedAt,
        )
        if (result is TResult.Failure<*>) {
            throw TenshuException(result.error)
        }
        @Suppress("UNCHECKED_CAST")
        return (result as TResult.Success<*>).value as T
    }
}

/**
 * iOS implementation of [ReplaceSourceFile] — the "Overwrite" conflict choice. Same shared upload
 * core with `replaceExisting = true`: an in-place overwrite, no delete and no tombstone.
 */
internal class IosReplaceSourceFileImplementation(
    private val fileDatabase: FileDatabase,
    private val reader: IosUploadSourceReader,
    private val syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    private val deviceId: String? = null,
    private val storageCapacityHolder: StorageCapacityHolder? = null,
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

        val result = UploadFileImplementation(
            fileDatabase, syncBlockEmitterHolder, deviceId, storageCapacityHolder,
        ).uploadFile(
            executionContext = executionContext,
            parentPath = parentPath.get(),
            fileName = fileName.get(),
            data = reader.readBytes(uri.get()),
            replaceExisting = true,
        )
        if (result is TResult.Failure<*>) {
            throw TenshuException(result.error)
        }
        @Suppress("UNCHECKED_CAST")
        return (result as TResult.Success<*>).value as T
    }
}
