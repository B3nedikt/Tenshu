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
 * This contact's photo bytes, read from their [PersonAvatar] row — empty when they have none.
 *
 * A **chain** operation with a [Person] receiver, following `GetPreview`: it is what resolves
 * [Person.Avatar], so it has to be callable from inside a chain rather than as a standalone
 * service call.
 */
fun Person.getAvatar(): TByteArray =
    findOperation<GetPersonAvatar>(GetPersonAvatar.ID).getAvatar(person = this)

interface GetPersonAvatar : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getAvatar(person: Person): TByteArray

    companion object {
        const val ID = "${ContactService.ID}/getPersonAvatar"

        val Schema = operation(
            id = ID,
            receiver = Person.Schema.toType(),
            params = {
                composite(id = Params.PERSON, type = Person.Schema)
            },
            traits = listOf(TerminalOperation.Schema),
        )
    }

    object Params {
        const val PERSON = "$ID/person"
    }
}
