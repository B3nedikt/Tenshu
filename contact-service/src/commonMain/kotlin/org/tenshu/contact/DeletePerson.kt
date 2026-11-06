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
 * Delete a contact — the [Person] row **and everything linked to it**: every [TenshuIdentity],
 * every [ContactSource] and its [PersonAvatar] (`docs/proposals/contacts-app.md` Q7a). A
 * dependant left behind is stranded — a peer's public key, or the whole contact card — in rows
 * nothing renders. Every row goes with a tombstone, so the delete reaches the user's other
 * devices; a source's photos are reclaimed once no row names them.
 *
 * ⚠ **Delete means delete.** Orphaning the keys to `personId = ""` instead, so a
 * re-handshake could recover the verification, buys little — re-verification is a local
 * six-digit comparison (Q2a) — and costs a peer's public key retained on every one of the
 * user's devices *after they asked to delete them*, in rows no
 * surface lists — unauditable, unclearable, and A24 data. `personId = ""` also models a
 * *transient* not-yet-linked identity, not a tombstone, so reusing it would overload the one
 * value with two meanings.
 *
 * ⚠⚠ **This revokes nothing.** Shared-realm memberships are keyed by the member's A26
 * identity, not by `personId`, so deleting a contact removes only the ability to *name*
 * them — their access to shared folders continues. A user deleting a contact in order to cut
 * someone off gets the opposite of what they intended, which is why Q7b requires the calling
 * surface to say so before it calls this (`contacts-app.md` §9.2).
 */
suspend fun ContactService.deletePerson(
    personId: String,
): TResult<TBoolean> =
    findOperation<DeletePerson>(DeletePerson.ID)
        .deletePerson(personId)
        .asTaskInteractor<TBoolean>()
        .execute()

interface DeletePerson : Operation {

    override val schema get() = Schema

    fun deletePerson(personId: String): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/deletePerson"

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
