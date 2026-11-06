package org.tenshu.file.impl

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.file.system.ReplaceFile
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.sync.common.SyncBlockEmitterHolder
import org.tenshu.file.proxy.MotionThumbnailRenderer
import org.tenshu.file.proxy.ThumbnailLadderRenderer

/**
 * Atomic in-place overwrite. Delegates to the shared upload core with
 * `replaceExisting = true` so the only behavioural difference from
 * [UploadFileImplementation] is that a pre-existing target is updated in
 * place instead of rejected — no delete, no tombstone. See
 * [UploadFileImplementation.uploadFile] and
 * `docs/proposals/drive-folder-upload.md`.
 */
internal class ReplaceFileImplementation(
    private val fileDatabase: FileDatabase,
    private val syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    private val deviceId: String? = null,
    private val storageCapacityHolder: StorageCapacityHolder? = null,
    /** M4: an overwrite re-mints every class, the motion one included — see [UploadFileImplementation]. */
    private val motionThumbnailRenderer: MotionThumbnailRenderer? = null,
    /** P1a: an overwrite renders its ladder from one decode too — see [UploadFileImplementation]. */
    private val thumbnailLadderRenderer: ThumbnailLadderRenderer? = null,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val parentPath: TString = params.read(ReplaceFile.Params.PARENT_PATH)
        val fileName: TString = params.read(ReplaceFile.Params.FILE_NAME)
        val data: TByteArray = params.read(ReplaceFile.Params.DATA)

        val result = UploadFileImplementation(
            fileDatabase, syncBlockEmitterHolder, deviceId, storageCapacityHolder,
            motionThumbnailRenderer = motionThumbnailRenderer,
            thumbnailLadderRenderer = thumbnailLadderRenderer,
        ).uploadFile(
            executionContext = executionContext,
            parentPath = parentPath.get(),
            fileName = fileName.get(),
            data = data.get(),
            replaceExisting = true
        )
        if (result is TResult.Failure<*>) {
            throw TenshuException(result.error)
        }
        return (result as TResult.Success<*>).value as T
    }
}
