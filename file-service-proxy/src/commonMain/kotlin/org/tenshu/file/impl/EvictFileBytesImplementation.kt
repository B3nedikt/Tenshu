package org.tenshu.file.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.TenshuException
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.Data as FileDataProperty
import org.tenshu.core.file.File
import org.tenshu.core.file.system.ContentPinnedError
import org.tenshu.core.file.system.EvictFileBytes
import org.tenshu.sync.common.SyncBlockEmitterHolder

/**
 * Drop the on-disk bytes for a file while leaving everything else intact —
 * the make-online-only half of Drive's offline toggle
 * (`docs/proposals/drive-offline-online.md` §2).
 *
 * Deliberately minimal next to [DeleteFileImplementation]: no parent-array
 * removal, no recursive descent, no `FileDeletedNotification`, and **no
 * tombstone**. The `File` entity and its synced metadata stay put, so the
 * file still lists and its `data` (`Syncable(OnDemand)`) re-fetches from the
 * relay's content store on the next open. Evicting must not converge into a
 * delete on other realm devices.
 */
internal class EvictFileBytesImplementation(
    private val fileDatabase: FileDatabase,
    /**
     * The content-emit journal, read-only.
     *
     * ⚠⚠ **A parked UPLOAD's bytes are its only copy.** Without this check *Remove download*, and
     * flipping a folder to `SyncPolicy.ONLINE_ONLY` (which evicts each child), would delete the only
     * copy of a file whose upload has not drained (Wi-Fi-only, a 507, offline) — the relay has never
     * seen those bytes, so there is nothing to re-fetch. [OfflineKeeperImplementation], the automatic
     * sweep, asks the same question.
     *
     * ⚠ Null reads as "nothing is owed", which is the default that deletes the last copy — sound
     * only for a harness with no emitter.
     */
    private val syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val path: TString = params.read(EvictFileBytes.Params.PATH)

        // ⚠⚠ Asked as `hasQueuedContentFor`, never `queuedContentBytesFor(...) > 0` — the byte
        // figure is a quota answer and reads `0` for a journalled intent that weighs nothing (an
        // empty file, or one whose path no longer sizes), which is precisely the row this spares.
        // Same call as the sweep's guard in [OfflineKeeperImplementation]; keep the two in step.
        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = File.ID,
            keyValue = path.get(),
        )
        if (syncBlockEmitterHolder?.fileContentEmitter?.hasQueuedContentFor(dataId, File.FileDataProperty.id) == true) {
            throw TenshuException(
                ContentPinnedError.create(
                    context = executionContext.context,
                    message = "${path.get()} has not finished uploading — the relay has never seen " +
                        "these bytes, so evicting them would destroy the only copy",
                )
            )
        }

        fileDatabase.deleteFile(path.get())
        return null as T
    }
}
