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
 * Revoke an issued, unredeemed invite (`contact-invitation-handshake.md` §3):
 * deletes the relay mailbox record (the relay keeps a `REVOKED` tombstone
 * until expiry) and flips the local [ContactInvite] to [ContactInvite.Status.REVOKED].
 * "Show the QR again" after the issuing session = revoke + re-issue.
 */
suspend fun ContactService.revokeContactInvite(
    inviteId: String,
): TResult<TBoolean> =
    findOperation<RevokeContactInvite>(RevokeContactInvite.ID)
        .revokeContactInvite(inviteId)
        .asTaskInteractor<TBoolean>()
        .execute()

interface RevokeContactInvite : Operation {

    override val schema get() = Schema

    fun revokeContactInvite(inviteId: String): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/revokeContactInvite"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.INVITE_ID)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val INVITE_ID = "$ID/inviteId"
    }
}
