package org.tenshu.photo.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.photo.AlbumEntry
import org.tenshu.photo.RemoveFromAlbum
import org.tenshu.sync.common.TombstoneEmitter

/**
 * Removes one entry. ⚠ The photograph itself is not reachable from here — see [RemoveFromAlbum].
 *
 * ⚠⚠ The tombstone is the op's own duty (`SyncObserver.onDataDeleted` is a no-op): without it the
 * photo stays in the album on every other device.
 */
internal class RemoveFromAlbumImplementation(
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
        val albumId: TString = params.read(RemoveFromAlbum.Params.ALBUM_ID)
        val contentId: TString = params.read(RemoveFromAlbum.Params.CONTENT_ID)

        val entryId = DataId.StringDataId.create(
            context = executionContext.context,
            type = AlbumEntry.ID,
            keyValue = AlbumEntry.keyOf(albumId.get(), contentId.get()),
        )
        executionContext.deleteData(entryId)
        tombstoneEmitter?.emit(targetEntityId = entryId, targetEntityType = AlbumEntry.ID)
        return null as T
    }
}
