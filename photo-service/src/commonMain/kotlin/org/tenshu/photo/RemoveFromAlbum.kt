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
 * Removes one photo from one album.
 *
 * ⚠⚠ **It deletes no photograph** — the same line [DeleteAlbum] draws. "Remove from album" and
 * "delete photo" are one gesture apart in every photos app ever shipped, so the separation is
 * enforced here rather than trusted to the UI: this op cannot reach `:file-service` at all.
 *
 * ⚠ Removing a photo leaves the positions of the others alone, gaps included. Ordering is defined
 * by the sequence of positions, not by their values.
 */
suspend fun PhotoService.removeFromAlbum(albumId: String, contentId: String): TResult<Data?> =
    findOperation<RemoveFromAlbum>(RemoveFromAlbum.ID)
        .removeFromAlbum(albumId, contentId)
        .asTaskInteractor<Data?>()
        .execute()

interface RemoveFromAlbum : Operation {

    override val schema: OperationSchema get() = Schema

    fun removeFromAlbum(albumId: String, contentId: String): RequestObject

    companion object {
        const val ID = "tenshu/photo/service/removeFromAlbum"

        val Schema = operation(
            id = ID,
            receiver = PhotoService.Schema.toType(),
            params = {
                string(id = Params.ALBUM_ID)
                string(id = Params.CONTENT_ID)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val ALBUM_ID = "$ID/albumId"
        const val CONTENT_ID = "$ID/contentId"
    }
}
