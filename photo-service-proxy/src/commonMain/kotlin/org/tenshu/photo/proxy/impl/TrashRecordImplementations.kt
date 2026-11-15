package org.tenshu.photo.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.photo.ClearTrashedPhoto
import org.tenshu.photo.RecordTrashedPhoto
import org.tenshu.photo.TrashedPhoto
import org.tenshu.sync.common.TombstoneEmitter

/** Upserts the "where did this come from" record. ⚠ Moves no file — see [RecordTrashedPhoto]. */
internal class RecordTrashedPhotoImplementation(
    private val nowEpochMs: () -> Long,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val contentId: TString = params.read(RecordTrashedPhoto.Params.CONTENT_ID)
        val originalPath: TString = params.read(RecordTrashedPhoto.Params.ORIGINAL_PATH)
        val trashPath: TString = params.read(RecordTrashedPhoto.Params.TRASH_PATH)

        executionContext.saveData(
            TrashedPhoto.create(
                context = executionContext.context,
                contentId = contentId.get(),
                originalPath = originalPath.get(),
                trashPath = trashPath.get(),
                trashedAtMs = nowEpochMs(),
            )
        )
        return null as T
    }
}

/**
 * Drops the record — after a restore, or after a permanent delete.
 *
 * ⚠⚠ The tombstone is the op's own duty (`SyncObserver.onDataDeleted` is a no-op): without it every
 * other device keeps listing the photo as trashed after it was restored or deleted for good.
 */
internal class ClearTrashedPhotoImplementation(
    private val tombstoneEmitter: TombstoneEmitter? = null,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val contentId: TString = params.read(ClearTrashedPhoto.Params.CONTENT_ID)
        val recordId = DataId.StringDataId.create(
            context = executionContext.context,
            type = TrashedPhoto.ID,
            keyValue = contentId.get(),
        )
        executionContext.deleteData(recordId)
        tombstoneEmitter?.emit(targetEntityId = recordId, targetEntityType = TrashedPhoto.ID)
        return null as T
    }
}
