package org.tenshu.contact

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Create a contact by hand (`docs/proposals/contacts-app.md` D5) — a thin [Person]
 * with a petname and **no key**.
 *
 * D5 is the point: manual entry is not a third acquisition path, it is the ordinary one
 * minus a handshake. A contact typed in here is the same aggregate a handshake would have
 * produced; it simply has an empty keyring until one is linked, which is why the app can
 * show both kinds in one list.
 *
 * Returns the generated `personId`.
 */
suspend fun ContactService.createPerson(
    displayName: String,
): TResult<TString> =
    findOperation<CreatePerson>(CreatePerson.ID)
        .createPerson(displayName)
        .asTaskInteractor<TString>()
        .execute()

interface CreatePerson : Operation {

    override val schema get() = Schema

    fun createPerson(displayName: String): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/createPerson"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.DISPLAY_NAME)
            },
            returnType = TString.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val DISPLAY_NAME = "$ID/displayName"
    }
}
