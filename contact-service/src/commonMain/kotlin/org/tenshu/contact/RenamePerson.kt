package org.tenshu.contact

import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Change a contact's petname.
 *
 * The petname is **user-owned**: it is seeded from whatever name the peer asserted at
 * handshake time and is theirs to overwrite from then on. Renaming never touches the linked
 * [TenshuIdentity] rows — `selfAssertedName` stays quarantined there as the claim it is.
 *
 * ⚠⚠ **This is a replace-write, and it is the reason `contacts-app.md` Q4 put the avatar in
 * a sibling row.** [Person] is re-created and saved whole, so every field it has must be
 * read from the existing row and carried across here — omitting one silently discards user
 * data (deletion by omission). Do **not** "simplify" per-person
 * data onto `Person` because a property looks tidier; a separately-keyed row is
 * structurally immune to this.
 */
suspend fun ContactService.renamePerson(
    personId: String,
    displayName: String,
): TResult<TBoolean> =
    findOperation<RenamePerson>(RenamePerson.ID)
        .renamePerson(personId, displayName)
        .asTaskInteractor<TBoolean>()
        .execute()

interface RenamePerson : Operation {

    override val schema get() = Schema

    fun renamePerson(personId: String, displayName: String): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/renamePerson"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.PERSON_ID)
                string(Params.DISPLAY_NAME)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val PERSON_ID = "$ID/personId"
        const val DISPLAY_NAME = "$ID/displayName"
    }
}
