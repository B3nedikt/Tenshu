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
 * Drop a key from a contact's keyring — the remedy for "this key is not them any more", and
 * the companion to the `contacts-app.md` Q2b demotion: a contact
 * showing *key changed* because a stale verified key sits beside their current one is fixed
 * either by verifying the new key or by removing the old one.
 *
 * ⚠ **Deletes the row rather than blanking `personId`.** `personId = ""` means a
 * *not-yet-linked candidate* — a transient state on the way into a keyring — so reusing it
 * as "deliberately detached" would leave an unreferenced key on every device that no surface
 * lists. Same reasoning as Q7a's delete-everything, and it keeps the two states from
 * collapsing into one.
 *
 * The linked [Person] is untouched: a contact with no keys is an ordinary contact (D5).
 */
suspend fun ContactService.unlinkTenshuIdentity(
    fingerprint: String,
): TResult<TBoolean> =
    findOperation<UnlinkTenshuIdentity>(UnlinkTenshuIdentity.ID)
        .unlinkTenshuIdentity(fingerprint)
        .asTaskInteractor<TBoolean>()
        .execute()

interface UnlinkTenshuIdentity : Operation {

    override val schema get() = Schema

    fun unlinkTenshuIdentity(fingerprint: String): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/unlinkTenshuIdentity"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.FINGERPRINT)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val FINGERPRINT = "$ID/fingerprint"
    }
}
