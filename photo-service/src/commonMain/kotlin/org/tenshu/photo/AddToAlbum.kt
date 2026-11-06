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
 * Adds a photo to an album, or updates the [AlbumEntry] already there.
 *
 * ⚠ Idempotent by key: adding the same photo twice updates one row rather than making two, which
 * is what a user means by "add to album" and what a multi-select over overlapping pages will do by
 * accident.
 *
 * ⚠ The entry lands at the **end**: the proxy reads the album's current maximum position and adds
 * [AlbumEntry.POSITION_STEP]. Re-adding a photo already in the album leaves its position alone —
 * "add" is not a way to reorder.
 *
 * ⚠⚠ [contentId] is `File.ContentId`, and an **empty one is rejected** rather than stored: every
 * such entry would key on `"$albumId|"` and collide with every other. A file predating content
 * addressing has none, and cannot be filed until it is re-uploaded. [filePath] is only a hint —
 * see [AlbumEntry].
 */
suspend fun PhotoService.addToAlbum(
    albumId: String,
    contentId: String,
    filePath: String,
): TResult<Data?> =
    findOperation<AddToAlbum>(AddToAlbum.ID)
        .addToAlbum(albumId, contentId, filePath)
        .asTaskInteractor<Data?>()
        .execute()

interface AddToAlbum : Operation {

    override val schema: OperationSchema get() = Schema

    fun addToAlbum(albumId: String, contentId: String, filePath: String): RequestObject

    companion object {
        const val ID = "tenshu/photo/service/addToAlbum"

        val Schema = operation(
            id = ID,
            receiver = PhotoService.Schema.toType(),
            params = {
                string(id = Params.ALBUM_ID)
                string(id = Params.CONTENT_ID)
                string(id = Params.FILE_PATH)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val ALBUM_ID = "$ID/albumId"
        const val CONTENT_ID = "$ID/contentId"
        const val FILE_PATH = "$ID/filePath"
    }
}
