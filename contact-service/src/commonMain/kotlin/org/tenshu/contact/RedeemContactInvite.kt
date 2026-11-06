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
 * Invitee-side redemption (`contact-invitation-handshake.md` §4 step 2):
 * parse [linkPayload] (either §3 encoding), seal an `InviteResponsePayload`
 * (`K_link` + own A13 pub + [selfAssertedName]) to the link-pinned inviter
 * key, `POST /sync/invite/response` (anonymous — the invitee carries no JWT),
 * and persist the device-local `PendingRedemption` (§7.2). Returns the
 * `inviteId` hex — the handle [awaitInviteAck] resumes on.
 *
 * Redemption is bound to this device (`K_link` stays local); the *results*
 * of the handshake sync as ordinary entities (§4 multi-device asymmetry).
 *
 * A relay refusal fails with `InvalidParameter` naming
 * [RedeemContactInvite.Params.LINK_PAYLOAD] (`core-error-copy-catalogue.md` §5.6):
 * `Reasons.ALREADY_CONSUMED` when the invite's single response slot is already
 * taken — the T-CI-01 tell, which the UI must surface loudly — and `Reasons.GONE`
 * when the invite expired, was revoked, or is unknown to the relay. ⚠ Callers match on the
 * typed reason, never on the error's message.
 */
suspend fun ContactService.redeemContactInvite(
    linkPayload: String,
    selfAssertedName: String,
): TResult<TString> =
    findOperation<RedeemContactInvite>(RedeemContactInvite.ID)
        .redeemContactInvite(linkPayload, selfAssertedName)
        .asTaskInteractor<TString>()
        .execute()

interface RedeemContactInvite : Operation {

    override val schema get() = Schema

    fun redeemContactInvite(
        linkPayload: String,
        selfAssertedName: String,
    ): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/redeemContactInvite"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.LINK_PAYLOAD)
                string(Params.SELF_ASSERTED_NAME)
            },
            returnType = TString.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val LINK_PAYLOAD = "$ID/linkPayload"
        const val SELF_ASSERTED_NAME = "$ID/selfAssertedName"
    }
}
