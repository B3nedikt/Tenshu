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
 * Sets both flags on one photo — see [PhotoFlags] for why they share a row.
 *
 * ⚠ Both values are written every time, so a caller that means "favourite this" must pass the
 * current `hidden` alongside it. That is the honest consequence of one row, and it is why the
 * repository exposes `toggleFavourite`-shaped helpers rather than making every caller remember.
 *
 * ⚠ A row is written even when both flags are `false`. Deleting it instead would be smaller, but
 * "un-favourited" and "never marked" must stay distinguishable across devices: a delete races a
 * peer's set and the peer wins by resurrection, while a `false` row is an ordinary
 * last-writer-wins fact.
 */
suspend fun PhotoService.setPhotoFlags(
    contentId: String,
    favourite: Boolean,
    hidden: Boolean,
): TResult<Data?> =
    findOperation<SetPhotoFlags>(SetPhotoFlags.ID)
        .setPhotoFlags(contentId, favourite, hidden)
        .asTaskInteractor<Data?>()
        .execute()

interface SetPhotoFlags : Operation {

    override val schema: OperationSchema get() = Schema

    fun setPhotoFlags(contentId: String, favourite: Boolean, hidden: Boolean): RequestObject

    companion object {
        const val ID = "tenshu/photo/service/setPhotoFlags"

        val Schema = operation(
            id = ID,
            receiver = PhotoService.Schema.toType(),
            params = {
                string(id = Params.CONTENT_ID)
                boolean(id = Params.FAVOURITE)
                boolean(id = Params.HIDDEN)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val CONTENT_ID = "$ID/contentId"
        const val FAVOURITE = "$ID/favourite"
        const val HIDDEN = "$ID/hidden"
    }
}
