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
import org.tenshu.sync.common.TombstoneEmitter

/**
 * Deletes the album **and cascades to its entries** — see [DeleteAlbum] for why the cascade is not
 * optional, and for the line this op does not cross: no `:file-service` row is touched.
 *
 * ⚠⚠ Each row deleted owes its own tombstone (`SyncObserver.onDataDeleted` is a no-op): without
 * them the album, and every entry, stays on every other device.
 *
 * ⚠ One [TombstoneEmitter.emitAll], not a per-row `emit`: each `emit` rewrites the
 * pending-tombstones array — O(N²) for an album of N photos, the reset ANR's shape
 * (`control-data-reset.md` §11).
 *
 * ⚠ A `null` answer (realm locked, keys still loading) is not refused, matching every other
 * single-row delete: the delete stays device-local. Refusing broke the JS SDK's album delete,
 * whose runtime sits in that state — `tech-debt.md` *"A delete during the lock window stays
 * device-local"* is where it gets fixed, across services at once.
 */
internal class DeleteAlbumImplementation(
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
        val albumId: TString = params.read(DeleteAlbum.Params.ALBUM_ID)

        val targets = executionContext.allAlbumEntries()
            .filter { it.albumId.get() == albumId.get() }
            .map { entry ->
                TombstoneEmitter.Target(
                    entityId = DataId.StringDataId.create(
                        context = executionContext.context,
                        type = AlbumEntry.ID,
                        keyValue = entry.key.get(),
                    ),
                    entityType = AlbumEntry.ID,
                )
            } + TombstoneEmitter.Target(
                entityId = DataId.StringDataId.create(
                    context = executionContext.context,
                    type = Album.ID,
                    keyValue = albumId.get(),
                ),
                entityType = Album.ID,
            )

        tombstoneEmitter?.emitAll(targets)
        targets.forEach { executionContext.deleteData(it.entityId) }
        return null as T
    }
}
