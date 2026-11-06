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

/** Removes one entry. ⚠ The photograph itself is not reachable from here — see [RemoveFromAlbum]. */
internal class RemoveFromAlbumImplementation : SyncOperationImplementation {

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

        executionContext.deleteData(
            DataId.StringDataId.create(
                context = executionContext.context,
                type = AlbumEntry.ID,
                keyValue = AlbumEntry.keyOf(albumId.get(), contentId.get()),
            )
        )
        return null as T
    }
}
