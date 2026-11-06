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
 * Remembers where a photo was before it was moved to the trash (`photos-app.md` PH-5).
 *
 * ⚠⚠ **This op does not move anything.** The move is `:file-service`'s `MoveFile`, called by the
 * app either side of this write. Keeping them separate is what keeps `:photo-service` unable to
 * touch a file — the same line `RemoveFromAlbum` draws — and it means a crash between the two
 * leaves either a file in the trash with no record (recoverable: it is visible in Drive at a
 * readable path) or a record with no file (harmless: the restore finds nothing and clears it).
 * The alternative, one op doing both, would put file mutation inside this service.
 */
suspend fun PhotoService.recordTrashedPhoto(
    contentId: String,
    originalPath: String,
    trashPath: String,
): TResult<Data?> =
    findOperation<RecordTrashedPhoto>(RecordTrashedPhoto.ID)
        .recordTrashedPhoto(contentId, originalPath, trashPath)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordTrashedPhoto : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordTrashedPhoto(
        contentId: String,
        originalPath: String,
        trashPath: String,
    ): RequestObject

    companion object {
        const val ID = "tenshu/photo/service/recordTrashedPhoto"

        val Schema = operation(
            id = ID,
            receiver = PhotoService.Schema.toType(),
            params = {
                string(id = Params.CONTENT_ID)
                string(id = Params.ORIGINAL_PATH)
                string(id = Params.TRASH_PATH)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val CONTENT_ID = "$ID/contentId"
        const val ORIGINAL_PATH = "$ID/originalPath"
        const val TRASH_PATH = "$ID/trashPath"
    }
}
