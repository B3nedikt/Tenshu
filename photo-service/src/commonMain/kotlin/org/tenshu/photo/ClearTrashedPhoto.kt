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
 * Forgets a trash record — after a restore, or after the file was deleted for good.
 *
 * ⚠ Both endings clear the same row, deliberately: the record's only job is to answer "where did
 * this come from", and once the photo is back or gone there is no question left. A separate
 * "purged" state would be a third thing to keep in step with the file.
 */
suspend fun PhotoService.clearTrashedPhoto(contentId: String): TResult<Data?> =
    findOperation<ClearTrashedPhoto>(ClearTrashedPhoto.ID)
        .clearTrashedPhoto(contentId)
        .asTaskInteractor<Data?>()
        .execute()

interface ClearTrashedPhoto : Operation {

    override val schema: OperationSchema get() = Schema

    fun clearTrashedPhoto(contentId: String): RequestObject

    companion object {
        const val ID = "tenshu/photo/service/clearTrashedPhoto"

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
