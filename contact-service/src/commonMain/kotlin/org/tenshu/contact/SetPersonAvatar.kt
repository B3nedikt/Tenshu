package org.tenshu.contact

import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Give a contact a photo, or replace the one they have (`contacts-app.md` D1).
 *
 * Upserts the [PersonAvatar] row keyed on [personId], so setting a photo twice from two
 * devices converges on one row rather than accumulating.
 *
 * ⚠ Rejects an image over [PersonAvatar.MAX_IMAGE_BYTES] rather than downscaling it: the row
 * syncs eagerly to every device, and re-encoding would need a platform image decoder inside a
 * service module. Callers that hold a large image are the ones positioned to shrink it.
 */
suspend fun ContactService.setPersonAvatar(
    personId: String,
    imageData: ByteArray,
): TResult<TBoolean> =
    findOperation<SetPersonAvatar>(SetPersonAvatar.ID)
        .setPersonAvatar(personId, imageData)
        .asTaskInteractor<TBoolean>()
        .execute()

interface SetPersonAvatar : Operation {

    override val schema get() = Schema

    fun setPersonAvatar(personId: String, imageData: ByteArray): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/setPersonAvatar"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.PERSON_ID)
                byteArray(Params.IMAGE_DATA)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val PERSON_ID = "$ID/personId"
        const val IMAGE_DATA = "$ID/imageData"
    }
}

/**
 * Remove a contact's photo. The contact keeps everything else, and any `PHOTO` their source
 * records carry is untouched — so this is *"stop showing me that picture"*, not a deletion of
 * imported data.
 */
suspend fun ContactService.clearPersonAvatar(personId: String): TResult<TBoolean> =
    findOperation<ClearPersonAvatar>(ClearPersonAvatar.ID)
        .clearPersonAvatar(personId)
        .asTaskInteractor<TBoolean>()
        .execute()

interface ClearPersonAvatar : Operation {

    override val schema get() = Schema

    fun clearPersonAvatar(personId: String): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/clearPersonAvatar"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.PERSON_ID)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val PERSON_ID = "$ID/personId"
    }
}
