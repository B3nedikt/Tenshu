package org.tenshu.photo

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Records (or replaces) one photo's edit — see [PhotoEdit].
 *
 * ⚠⚠ **This op uploads nothing.** The rendition is a file the app uploads with `:file-service` after
 * this write, and deletes after [ClearPhotoEdit] — the line [RecordTrashedPhoto] draws, so this
 * service still cannot touch a file. ⚠ The row is written **first** (`photos-editing.md` §12.4): a
 * peer that has the rendition's file has, in sync order, already seen the row that claims it.
 */
suspend fun PhotoService.setPhotoEdit(
    contentId: String,
    recipe: String,
    renditionPath: String,
    redacted: Boolean,
): TResult<Data?> =
    findOperation<SetPhotoEdit>(SetPhotoEdit.ID)
        .setPhotoEdit(contentId, recipe, renditionPath, redacted)
        .asTaskInteractor<Data?>()
        .execute()

interface SetPhotoEdit : Operation {

    override val schema: OperationSchema get() = Schema

    fun setPhotoEdit(contentId: String, recipe: String, renditionPath: String, redacted: Boolean): RequestObject

    companion object {
        const val ID = "tenshu/photo/service/setPhotoEdit"

        val Schema = operation(
            id = ID,
            receiver = PhotoService.Schema.toType(),
            params = {
                string(id = Params.CONTENT_ID)
                string(id = Params.RECIPE)
                string(id = Params.RENDITION_PATH)
                boolean(id = Params.REDACTED)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val CONTENT_ID = "$ID/contentId"
        const val RECIPE = "$ID/recipe"
        const val RENDITION_PATH = "$ID/renditionPath"
        const val REDACTED = "$ID/redacted"
    }
}
