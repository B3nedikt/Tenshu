package org.tenshu.photo.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.photo.Album
import org.tenshu.photo.SaveAlbum
import org.tenshu.photo.albumId
import org.tenshu.photo.createdAt

/**
 * Upserts an [Album]; the relation maintainer files it under the service.
 *
 * ⚠ `createdAt` is read back from the existing row when there is one, so a rename preserves it.
 * The clock is only consulted for a genuinely new album — see [SaveAlbum] for why this is not a
 * parameter.
 */
internal class SaveAlbumImplementation(
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
        val albumId: TString = params.read(SaveAlbum.Params.ALBUM_ID)
        val name: TString = params.read(SaveAlbum.Params.NAME)
        val coverContentId: TString = params.read(SaveAlbum.Params.COVER_CONTENT_ID)

        val existing = executionContext.allAlbums()
            .firstOrNull { it.albumId.get() == albumId.get() }

        executionContext.saveData(
            Album.create(
                context = executionContext.context,
                albumId = albumId.get(),
                name = name.get(),
                createdAtMs = existing?.createdAt?.get() ?: nowEpochMs(),
                coverContentId = coverContentId.get(),
            )
        )
        return null as T
    }
}
