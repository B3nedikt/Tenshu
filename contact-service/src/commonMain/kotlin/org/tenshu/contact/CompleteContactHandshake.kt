package org.tenshu.contact

import org.tenshu.core.InvalidParameter
import org.tenshu.core.Operation
import org.tenshu.core.TError
import org.tenshu.core.TString
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Inviter-side completion (`contact-invitation-handshake.md` §4 step 4) —
 * the action behind the *"Save as new contact, or link to existing?"* prompt.
 * Verifies the stashed (or relay-fetched) sealed response against the
 * [ContactInvite]'s `secretHash`; a bad secret **burns** the invite
 * (`COMPROMISED`, sealed reject posted) regardless of [decision].
 *
 * On [Decision.ACCEPT]: creates the [TenshuIdentity] (linked to [personId],
 * or to a fresh thin [Person] named [newPersonName]) and posts the sealed
 * `accepted` ack carrying [selfAssertedName] + the invite's share intent.
 * [sasConfirmed] = the QR-in-person flow where both users compared the §5.3
 * SAS — the identity is born `VERIFIED`/`QR_SAS` instead of
 * `UNVERIFIED`/`INVITE_LINK`. Returns the new identity's fingerprint.
 *
 * On [Decision.REJECT]: posts the sealed `rejected` ack, no records created,
 * returns `""`.
 *
 * ⚠⚠ [shownFingerprint] is the claimed fingerprint the prompt **displayed** — the raw
 * `ContactInvite.claimedFingerprint` it rendered, not the display-formatted one (threat model
 * T-CI-10). On ACCEPT the proxy stores the response only if its key is that fingerprint — and an
 * ACCEPT carrying `""` is **refused** ([Refusal.isResponseChanged], or [Refusal.isCodeNotShown]
 * with `sasConfirmed`), never read as "nothing to compare": a prompt must not offer accept before
 * its claim has loaded. `""` is legitimate only on REJECT. So a claim that flipped under the open
 * prompt (a second inviter device's write arriving by merge) cannot be accepted on the strength
 * of a code the user compared for a different key. Required, deliberately: a caller that cannot
 * send it fails loudly rather than skipping the check.
 */
suspend fun ContactService.completeContactHandshake(
    inviteId: String,
    decision: String,
    shownFingerprint: String,
    personId: String = "",
    newPersonName: String = "",
    selfAssertedName: String = "",
    sasConfirmed: Boolean = false,
): TResult<TString> =
    findOperation<CompleteContactHandshake>(CompleteContactHandshake.ID)
        .completeContactHandshake(
            inviteId, decision, shownFingerprint, personId, newPersonName, selfAssertedName,
            sasConfirmed,
        )
        .asTaskInteractor<TString>()
        .execute()

interface CompleteContactHandshake : Operation {

    override val schema get() = Schema

    fun completeContactHandshake(
        inviteId: String,
        decision: String,
        shownFingerprint: String,
        personId: String,
        newPersonName: String,
        selfAssertedName: String,
        sasConfirmed: Boolean,
    ): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/completeContactHandshake"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.INVITE_ID)
                string(Params.DECISION)
                string(Params.SHOWN_FINGERPRINT)
                string(Params.PERSON_ID, isOptional = true)
                string(Params.NEW_PERSON_NAME, isOptional = true)
                string(Params.SELF_ASSERTED_NAME, isOptional = true)
                boolean(Params.SAS_CONFIRMED, isOptional = true)
            },
            returnType = TString.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val INVITE_ID = "$ID/inviteId"
        const val DECISION = "$ID/decision"
        const val SHOWN_FINGERPRINT = "$ID/shownFingerprint"
        const val PERSON_ID = "$ID/personId"
        const val NEW_PERSON_NAME = "$ID/newPersonName"
        const val SELF_ASSERTED_NAME = "$ID/selfAssertedName"
        const val SAS_CONFIRMED = "$ID/sasConfirmed"
    }

    /**
     * The two refusals of [Decision.ACCEPT] an app renders its own copy for (threat model
     * T-CI-10). Both travel as [InvalidParameter] — a parameter name plus a wire-stable reason,
     * so they need no composite of their own (`core-error-copy-catalogue.md` EC-1/EC-3: *check
     * `InvalidParameter` first*). The proxy throws them; these predicates are
     * how a repository tells them from every other failure without reading a message.
     */
    object Refusal {
        /**
         * The response this invite now holds is **not the one the prompt showed** — somebody
         * else answered the link as well. Nothing was saved. [Params.INVITE_ID] with
         * `Reasons.ALREADY_CONSUMED`, the same loud T-CI-01 tell `RedeemContactInvite` raises
         * from the other side.
         */
        fun isResponseChanged(error: TError): Boolean =
            error.isInvalid(Params.INVITE_ID, InvalidParameter.Reasons.ALREADY_CONSUMED)

        /**
         * `sasConfirmed` was sent for an invite **no code has been shown for**.
         * [Params.SAS_CONFIRMED] with `Reasons.NOT_READY`. No prompt offers the tick without a code, so
         * reaching this means a caller confirmed something it never displayed.
         */
        fun isCodeNotShown(error: TError): Boolean =
            error.isInvalid(Params.SAS_CONFIRMED, InvalidParameter.Reasons.NOT_READY)

        private fun TError.isInvalid(parameter: String, reason: String): Boolean =
            this is InvalidParameter && name.get() == parameter && this.reason.get() == reason
    }

    object Decision {
        const val ACCEPT = "ACCEPT"
        const val REJECT = "REJECT"
    }
}
