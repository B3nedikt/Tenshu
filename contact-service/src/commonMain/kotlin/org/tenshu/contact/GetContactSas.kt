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
 * The six-digit comparison code for one of a contact's keys
 * (`contact-invitation-handshake.md` §5.3, `contacts-app.md` Q2a) — the number both sides
 * read to each other to confirm they hold the same key.
 *
 * ⚠ **Why this is an op rather than a field or a client-side derivation.**
 * `deriveContactSas` is over **both identity public keys**, so producing it needs the user's
 * own A13 — and there is no client-readable own-identity surface (`contacts-app.md` §9,
 * `OwnProfile.fingerprint`). The proxy holds the key pair, so it derives; the client only
 * displays. Storing the code on the `TenshuIdentity` row instead would be a positional
 * reshape of a shipped composite (House Rule #9) to cache something that is already a pure
 * function of two values the row and the realm already hold.
 *
 * ⚠ **Not a secret.** The derivation is over public keys only — this is mutual
 * key-fingerprint comparison over a human channel, not a secrecy mechanism. Returning it to
 * the app leaks nothing an observer of both public keys could not compute.
 *
 * Returns `""` when the fingerprint is unknown, or when the realm is locked and the A13 is
 * therefore unavailable — the caller renders the absence rather than a wrong code.
 */
suspend fun ContactService.getContactSas(
    fingerprint: String,
): TResult<TString> =
    findOperation<GetContactSas>(GetContactSas.ID)
        .getContactSas(fingerprint)
        .asTaskInteractor<TString>()
        .execute()

interface GetContactSas : Operation {

    override val schema get() = Schema

    fun getContactSas(fingerprint: String): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/getContactSas"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.FINGERPRINT)
            },
            returnType = TString.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val FINGERPRINT = "$ID/fingerprint"
    }
}
