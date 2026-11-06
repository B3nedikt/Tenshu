package org.tenshu.contact

import org.tenshu.core.Operation
import org.tenshu.core.TString
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
 * Issue a contact invitation (`contact-invitation-handshake.md` §4 step 1):
 * mint the §3 triple, register the mailbox at the relay (JWT), persist the
 * [ContactInvite] row (`Syncable(Eager)` — every device can complete), and
 * return the **messaging link**. The QR form and the raw link are derivable
 * client-side via `InviteLinkPayload.parse(link)` — the returned string is
 * the only carrier of `K_link`, which is never persisted (only its hash).
 *
 * Interactive (no `HeadlessSafe` — I9): issuance ends in an OS share sheet.
 */
suspend fun ContactService.createContactInvite(
    intentKind: String = "",
    intentLabel: String = "",
): TResult<TString> =
    findOperation<CreateContactInvite>(CreateContactInvite.ID)
        .createContactInvite(intentKind, intentLabel)
        .asTaskInteractor<TString>()
        .execute()

interface CreateContactInvite : Operation {

    override val schema get() = Schema

    fun createContactInvite(
        intentKind: String,
        intentLabel: String,
    ): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/createContactInvite"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.INTENT_KIND, isOptional = true)
                string(Params.INTENT_LABEL, isOptional = true)
            },
            returnType = TString.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val INTENT_KIND = "$ID/intentKind"
        const val INTENT_LABEL = "$ID/intentLabel"
    }
}
