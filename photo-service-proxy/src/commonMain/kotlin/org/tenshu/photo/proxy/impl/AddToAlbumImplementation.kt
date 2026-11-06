package org.tenshu.photo.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.InvalidParameter
import org.tenshu.core.TenshuException
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.photo.AddToAlbum
import org.tenshu.photo.AlbumEntry
import org.tenshu.photo.albumId
import org.tenshu.photo.contentId
import org.tenshu.photo.position

/**
 * Adds a photo to an album, or refreshes the entry already there.
 *
 * ⚠ Re-adding preserves the existing [AlbumEntry.position] — "add" is not a reorder — but does
 * rewrite the path hint, which is what makes a stale hint self-healing (see [AlbumEntry]).
 */
internal class AddToAlbumImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val albumId: TString = params.read(AddToAlbum.Params.ALBUM_ID)
        val contentId: TString = params.read(AddToAlbum.Params.CONTENT_ID)
        val filePath: TString = params.read(AddToAlbum.Params.FILE_PATH)

        // ⚠⚠ Rejected rather than stored: every empty-content-id entry in one album would key on
        // the same string and overwrite each other. A file predating content addressing has none.
        if (contentId.get().isEmpty()) {
            throw TenshuException(
                InvalidParameter.create(
                    context = executionContext.context,
                    name = AddToAlbum.Params.CONTENT_ID,
                    reason = InvalidParameter.Reasons.EMPTY,
                )
            )
        }

        val entries = executionContext.allAlbumEntries()
        val existing = entries.firstOrNull {
            it.albumId.get() == albumId.get() && it.contentId.get() == contentId.get()
        }
        val position = existing?.position?.get()
            ?: (
                entries.filter { it.albumId.get() == albumId.get() }
                    .maxOfOrNull { it.position.get() }
                    ?.plus(AlbumEntry.POSITION_STEP)
                    ?: 0L
                )

        executionContext.saveData(
            AlbumEntry.create(
                context = executionContext.context,
                albumId = albumId.get(),
                contentId = contentId.get(),
                filePath = filePath.get(),
                position = position,
            )
        )
        return null as T
    }
}
