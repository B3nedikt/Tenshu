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
 * Forgets one photo's edit — *Revert*, or the photo deleted for good.
 *
 * ⚠ A delete, unlike [SetPhotoFlags]' `false` row: a reverted photo and a never-edited one are the
 * same state, and a revert racing a peer's save losing to it (resurrection) leaves the peer's edit
 * showing — something the user can act on. ⚠ Deletes no file: the app removes the rendition after
 * this, so the photo shows its original the moment the row is gone (`photos-editing.md` §12.4).
 */
suspend fun PhotoService.clearPhotoEdit(contentId: String): TResult<Data?> =
    findOperation<ClearPhotoEdit>(ClearPhotoEdit.ID)
        .clearPhotoEdit(contentId)
        .asTaskInteractor<Data?>()
        .execute()

interface ClearPhotoEdit : Operation {

    override val schema: OperationSchema get() = Schema

    fun clearPhotoEdit(contentId: String): RequestObject

    companion object {
        const val ID = "tenshu/photo/service/clearPhotoEdit"

        val Schema = operation(
            id = ID,
            receiver = PhotoService.Schema.toType(),
            params = { string(id = Params.CONTENT_ID) },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val CONTENT_ID = "$ID/contentId"
    }
}
