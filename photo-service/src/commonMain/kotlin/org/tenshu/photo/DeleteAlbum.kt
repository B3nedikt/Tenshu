package org.tenshu.photo

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Deletes an [Album] **and cascades to its [AlbumEntry] rows**.
 *
 * ⚠⚠ The cascade is the point, and it is the `DeleteCalendar` lesson applied: `AlbumEntry.albumId`
 * is an id, not a reference, so nothing collects orphans. Without the cascade they would linger in
 * the store, sync to every device, and count towards any query not filtered by a known album.
 *
 * ⚠⚠ **It deletes no photographs.** An album is a statement about files; removing the statement
 * cannot remove what it was about. The `:file-service` rows are untouched, which is also why this
 * op needs no confirmation beyond the UI's own — the worst case is a list the user rebuilds, not a
 * picture they lose.
 */
suspend fun PhotoService.deleteAlbum(albumId: String): TResult<Data?> =
    findOperation<DeleteAlbum>(DeleteAlbum.ID)
        .deleteAlbum(albumId)
        .asTaskInteractor<Data?>()
        .execute()

interface DeleteAlbum : Operation {

    override val schema: OperationSchema get() = Schema

    fun deleteAlbum(albumId: String): RequestObject

    companion object {
        const val ID = "tenshu/photo/service/deleteAlbum"

        val Schema = operation(
            id = ID,
            receiver = PhotoService.Schema.toType(),
            params = { string(id = Params.ALBUM_ID) },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val ALBUM_ID = "$ID/albumId"
    }
}
