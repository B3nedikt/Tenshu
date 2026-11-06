package org.tenshu.photo.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.photo.Album
import org.tenshu.photo.AlbumEntry
import org.tenshu.photo.DeleteAlbum
import org.tenshu.photo.albumId
import org.tenshu.photo.key

/**
 * Deletes the album **and cascades to its entries** — see [DeleteAlbum] for why the cascade is not
 * optional, and for the line this op does not cross: no `:file-service` row is touched.
 */
internal class DeleteAlbumImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val albumId: TString = params.read(DeleteAlbum.Params.ALBUM_ID)

        executionContext.allAlbumEntries()
            .filter { it.albumId.get() == albumId.get() }
            .forEach { entry ->
                executionContext.deleteData(
                    DataId.StringDataId.create(
                        context = executionContext.context,
                        type = AlbumEntry.ID,
                        keyValue = entry.key.get(),
                    )
                )
            }

        executionContext.deleteData(
            DataId.StringDataId.create(
                context = executionContext.context,
                type = Album.ID,
                keyValue = albumId.get(),
            )
        )
        return null as T
    }
}
