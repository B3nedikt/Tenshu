package org.tenshu.contact

import org.tenshu.core.Operation
import org.tenshu.core.TByteArray
import org.tenshu.core.core.findOperation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.TerminalOperation

/**
 * The on-demand resolver for [ContactSource.Photos] (`contacts-app.md` §9.10) — the contacts twin of
 * `GetWorkoutStreams` / `GetData`. Projecting `relations(ContactSource.Photos)` dispatches this op,
 * which reads the relocated `PHOTO` lines from the external `FileDatabase` under
 * `contactPhotosPath(contentId)` rather than from the row.
 */
fun ContactSource.getPhotos(): TByteArray =
    findOperation<GetContactSourcePhotos>(GetContactSourcePhotos.ID).getPhotos(this)

interface GetContactSourcePhotos : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getPhotos(source: ContactSource): TByteArray

    companion object {
        const val ID = "${ContactService.ID}/getContactSourcePhotos"

        val Schema = operation(
            id = ID,
            receiver = ContactSource.Schema.toType(),
            params = {
                composite(id = Params.SOURCE, type = ContactSource.Schema)
            },
            traits = listOf(TerminalOperation.Schema),
        )
    }

    object Params {
        const val SOURCE = "$ID/source"
    }
}
