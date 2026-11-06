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
 * Creates or renames an [Album], keyed on [albumId].
 *
 * ⚠ Upsert rather than a create/update pair, following `SaveCalendar` and `WriteSetting`: the row's
 * conflict strategy is already last-writer-wins, so a separate "update" would carry the same
 * semantics under a second name and give the caller a distinction the storage layer does not make.
 *
 * ⚠ `createdAt` is set by the **proxy** on first write and preserved on later ones, so a rename
 * cannot silently re-date an album and two devices renaming it cannot disagree about when it was
 * made. That is why it is not a parameter.
 */
suspend fun PhotoService.saveAlbum(
    albumId: String,
    name: String,
    coverContentId: String = "",
): TResult<Data?> =
    findOperation<SaveAlbum>(SaveAlbum.ID)
        .saveAlbum(albumId, name, coverContentId)
        .asTaskInteractor<Data?>()
        .execute()

interface SaveAlbum : Operation {

    override val schema: OperationSchema get() = Schema

    fun saveAlbum(albumId: String, name: String, coverContentId: String): RequestObject

    companion object {
        const val ID = "tenshu/photo/service/saveAlbum"

        val Schema = operation(
            id = ID,
            receiver = PhotoService.Schema.toType(),
            params = {
                string(id = Params.ALBUM_ID)
                string(id = Params.NAME)
                string(id = Params.COVER_CONTENT_ID)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val ALBUM_ID = "$ID/albumId"
        const val NAME = "$ID/name"
        const val COVER_CONTENT_ID = "$ID/coverContentId"
    }
}
